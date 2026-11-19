use std::{
    collections::HashMap,
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
};

#[derive(Clone, Debug, PartialEq)]
pub struct Question {
    pub name: String,
    pub kind: u16,
}
#[derive(Clone, Debug, PartialEq)]
pub struct Message {
    pub id: u16,
    pub response: bool,
    pub rcode: u8,
    pub truncated: bool,
    pub questions: Vec<Question>,
    pub answers: Vec<String>,
}

fn u16be(b: &[u8], p: usize) -> Option<u16> {
    Some(u16::from_be_bytes(b.get(p..p + 2)?.try_into().ok()?))
}
fn u32be(b: &[u8], p: usize) -> Option<u32> {
    Some(u32::from_be_bytes(b.get(p..p + 4)?.try_into().ok()?))
}
fn name(b: &[u8], cursor: &mut usize) -> Option<String> {
    let mut pos = *cursor;
    let mut jumped = false;
    let mut labels = Vec::new();
    let mut length = 0;
    for _ in 0..128 {
        let size = *b.get(pos)?;
        if size & 0xc0 == 0xc0 {
            let target = (u16be(b, pos)? & 0x3fff) as usize;
            // 压缩指针只能回指，拒绝环和伪造偏移。 / Backward pointers prevent compression loops.
            if target >= pos {
                return None;
            }
            if !jumped {
                *cursor = pos + 2;
                jumped = true;
            }
            pos = target;
        } else if size == 0 {
            if !jumped {
                *cursor = pos + 1;
            }
            return Some(labels.join("."));
        } else {
            if size > 63 {
                return None;
            }
            let label = b.get(pos + 1..pos + 1 + size as usize)?;
            length += label.len() + 1;
            if length > 255 {
                return None;
            }
            let escaped = label
                .iter()
                .map(|c| match c {
                    0x21..=0x7e if *c != b'.' && *c != b'\\' => (*c as char).to_string(),
                    _ => format!("\\{c:03}"),
                })
                .collect::<String>();
            labels.push(escaped);
            pos += 1 + size as usize;
        }
    }
    None
}

pub fn dns(b: &[u8]) -> Option<Message> {
    let flags = u16be(b, 2)?;
    if b.len() < 12 {
        return None;
    }
    let qd = u16be(b, 4)?;
    let an = u16be(b, 6)?;
    let other = u16be(b, 8)? as usize + u16be(b, 10)? as usize;
    if qd > 16 || an as usize + other > 512 {
        return None;
    }
    let mut p = 12;
    let mut questions = Vec::new();
    for _ in 0..qd {
        let domain = name(b, &mut p)?;
        let kind = u16be(b, p)?;
        let _class = u16be(b, p + 2)?;
        p += 4;
        questions.push(Question { name: domain, kind });
    }
    let mut answers = Vec::new();
    for i in 0..an as usize + other {
        let _owner = name(b, &mut p)?;
        let kind = u16be(b, p)?;
        let class = u16be(b, p + 2)?;
        let length = u16be(b, p + 8)? as usize;
        p += 10;
        let rdata = b.get(p..p + length)?;
        if i < an as usize && class == 1 {
            match (kind, length) {
                (1, 4) => {
                    answers.push(Ipv4Addr::new(rdata[0], rdata[1], rdata[2], rdata[3]).to_string())
                }
                (28, 16) => {
                    answers.push(Ipv6Addr::from(<[u8; 16]>::try_from(rdata).ok()?).to_string())
                }
                (2 | 5 | 12, _) => {
                    let mut rp = p;
                    let n = name(b, &mut rp)?;
                    if rp > p + length {
                        return None;
                    }
                    answers.push(n);
                }
                _ => {}
            }
        }
        p += length;
    }
    Some(Message {
        id: u16be(b, 0)?,
        response: flags & 0x8000 != 0,
        rcode: (flags & 15) as u8,
        truncated: flags & 0x0200 != 0,
        questions,
        answers,
    })
}

#[derive(Clone, Debug)]
pub struct Transport<'a> {
    pub source: IpAddr,
    pub destination: IpAddr,
    pub source_port: u16,
    pub destination_port: u16,
    pub tcp: bool,
    pub sequence: u32,
    pub syn: bool,
    pub fin: bool,
    pub payload: &'a [u8],
}
#[derive(Debug, PartialEq)]
pub enum PacketError {
    Unsupported,
    Truncated,
    Fragmented,
    Malformed,
}

pub fn transport(b: &[u8], packet_type: u16) -> Result<Option<Transport<'_>>, PacketError> {
    use PacketError::*;
    let (mut p, mut ether) = match packet_type {
        1 => (14, u16be(b, 12).ok_or(Truncated)?),
        3 => (
            0,
            match b.first().ok_or(Truncated)? >> 4 {
                4 => 0x800,
                6 => 0x86dd,
                _ => return Err(Malformed),
            },
        ),
        // 802.11 数据帧 + LLC/SNAP；跳过管理/控制/空数据及硬件加密副本。 / Decode plain 802.11 data frames; ignore non-data or hardware-encrypted copies.
        2 => {
            let fc = u16::from_le_bytes(b.get(..2).ok_or(Truncated)?.try_into().unwrap());
            let subtype = (fc >> 4) & 0x0f;
            if fc & 0x000c != 8 || subtype & 4 != 0 {
                return Ok(None);
            }
            let mut offset = 24 + if fc & 0x0300 == 0x0300 { 6 } else { 0 };
            if subtype & 8 != 0 {
                offset += 2;
                if fc & 0x8000 != 0 {
                    offset += 4;
                }
            }
            // 无明文 LLC/SNAP（如驱动加密帧）跳过，明文视图已由 NDIS Ethernet 层提供。 / Skip encrypted/non-SNAP 802.11 frames; NDIS Ethernet layer delivers plaintext.
            if b.get(offset..offset + 6) != Some(&[0xaa, 0xaa, 3, 0, 0, 0]) {
                return Ok(None);
            }
            (offset + 8, u16be(b, offset + 6).ok_or(Truncated)?)
        }
        // 非 DNS 相关协议帧直接忽略。 / Non-DNS packet types reported by PktMon.
        4 | 7 | 8 | 9 | 10 => return Ok(None),
        _ => return Err(Unsupported),
    };
    for _ in 0..2 {
        if !matches!(ether, 0x8100 | 0x88a8) {
            break;
        }
        ether = u16be(b, p + 2).ok_or(Truncated)?;
        p += 4;
    }
    let (source, destination, mut proto, end) = match ether {
        0x800 => {
            let header = b.get(p..p + 20).ok_or(Truncated)?;
            let ihl = (header[0] & 15) as usize * 4;
            let size = u16be(b, p + 2).ok_or(Truncated)? as usize;
            if header[0] >> 4 != 4 || ihl < 20 || size < ihl {
                return Err(Malformed);
            }
            if u16be(b, p + 6).ok_or(Truncated)? & 0x3fff != 0 {
                return Err(Fragmented);
            }
            let end = p + size;
            if end > b.len() {
                return Err(Truncated);
            }
            let source = Ipv4Addr::new(header[12], header[13], header[14], header[15]).into();
            let dest = Ipv4Addr::new(header[16], header[17], header[18], header[19]).into();
            p += ihl;
            (source, dest, header[9], end)
        }
        0x86dd => {
            let header = b.get(p..p + 40).ok_or(Truncated)?;
            if header[0] >> 4 != 6 {
                return Err(Malformed);
            }
            let size = u16be(b, p + 4).ok_or(Truncated)? as usize;
            if size == 0 {
                return Err(Unsupported);
            }
            let end = p + 40 + size;
            if end > b.len() {
                return Err(Truncated);
            }
            let source = Ipv6Addr::from(<[u8; 16]>::try_from(&header[8..24]).unwrap()).into();
            let dest = Ipv6Addr::from(<[u8; 16]>::try_from(&header[24..40]).unwrap()).into();
            p += 40;
            (source, dest, header[6], end)
        }
        _ => return Ok(None),
    };
    let b = &b[..end];
    for _ in 0..8 {
        if !matches!(proto, 0 | 43 | 44 | 51 | 60) {
            break;
        }
        let next = *b.get(p).ok_or(Truncated)?;
        let len = match proto {
            44 => {
                if u16be(b, p + 2).ok_or(Truncated)? & 0xfff9 != 0 {
                    return Err(Fragmented);
                }
                8
            }
            51 => (*b.get(p + 1).ok_or(Truncated)? as usize + 2) * 4,
            _ => (*b.get(p + 1).ok_or(Truncated)? as usize + 1) * 8,
        };
        p += len;
        if p > end {
            return Err(Truncated);
        }
        proto = next;
    }
    if !matches!(proto, 6 | 17) {
        return Ok(None);
    }
    let source_port = u16be(b, p).ok_or(Truncated)?;
    let destination_port = u16be(b, p + 2).ok_or(Truncated)?;
    if ![source_port, destination_port]
        .iter()
        .any(|p| matches!(p, 53 | 853))
    {
        return Ok(None);
    }
    let (sequence, syn, fin, payload) = if proto == 17 {
        let len = u16be(b, p + 4).ok_or(Truncated)? as usize;
        if len < 8 {
            return Err(Malformed);
        }
        (0, false, false, b.get(p + 8..p + len).ok_or(Truncated)?)
    } else {
        let header = b.get(p..p + 20).ok_or(Truncated)?;
        let len = (header[12] >> 4) as usize * 4;
        if len < 20 {
            return Err(Malformed);
        }
        (
            u32be(b, p + 4).ok_or(Truncated)?,
            header[13] & 2 != 0,
            header[13] & 5 != 0,
            b.get(p + len..).ok_or(Truncated)?,
        )
    };
    Ok(Some(Transport {
        source,
        destination,
        source_port,
        destination_port,
        tcp: proto == 6,
        sequence,
        syn,
        fin,
        payload,
    }))
}

#[derive(Clone, Hash, PartialEq, Eq)]
struct StreamKey {
    component: u32,
    source: IpAddr,
    destination: IpAddr,
    sp: u16,
    dp: u16,
}
struct Stream {
    next: u32,
    bytes: Vec<u8>,
    pending: Vec<(u32, Vec<u8>)>,
    touched: u64,
}
#[derive(Default)]
pub struct TcpDns {
    streams: HashMap<StreamKey, Stream>,
    pub incomplete: u64,
}
impl TcpDns {
    pub fn push(&mut self, component: u32, t: &Transport<'_>, now: u64) -> Vec<Message> {
        let key = StreamKey {
            component,
            source: t.source,
            destination: t.destination,
            sp: t.source_port,
            dp: t.destination_port,
        };
        let before = self.streams.len();
        self.streams
            .retain(|_, s| now.saturating_sub(s.touched) < 60_000);
        self.incomplete += (before - self.streams.len()) as u64;
        if t.syn {
            if self.streams.len() >= 256 {
                self.incomplete += 1;
                return vec![];
            }
            self.streams.insert(
                key.clone(),
                Stream {
                    next: t.sequence.wrapping_add(1),
                    bytes: vec![],
                    pending: vec![],
                    touched: now,
                },
            );
        }
        let Some(s) = self.streams.get_mut(&key) else {
            if !t.payload.is_empty() {
                self.incomplete += 1;
            }
            return vec![];
        };
        s.touched = now;
        let seq = t.sequence.wrapping_add(u32::from(t.syn));
        if !t.payload.is_empty() {
            s.pending.push((seq, t.payload.to_vec()));
        }
        if s.pending.len() > 64
            || s.pending.iter().map(|(_, v)| v.len()).sum::<usize>() + s.bytes.len() > 131_072
        {
            self.incomplete += 1;
            self.streams.remove(&key);
            return vec![];
        }
        loop {
            let item = s
                .pending
                .iter()
                .position(|(seq, _)| seq.wrapping_sub(s.next) as i32 <= 0);
            let Some(index) = item else {
                break;
            };
            let (seq, data) = s.pending.remove(index);
            let skip = s.next.wrapping_sub(seq) as usize;
            if skip < data.len() {
                s.bytes.extend_from_slice(&data[skip..]);
                s.next = s.next.wrapping_add((data.len() - skip) as u32);
            }
        }
        let mut messages = Vec::new();
        while s.bytes.len() >= 2 {
            let size = u16be(&s.bytes, 0).unwrap() as usize;
            if size < 12 {
                self.incomplete += 1;
                self.streams.remove(&key);
                return messages;
            }
            if s.bytes.len() < size + 2 {
                break;
            }
            if let Some(message) = dns(&s.bytes[2..size + 2]) {
                messages.push(message);
            } else {
                self.incomplete += 1;
            }
            s.bytes.drain(..size + 2);
        }
        if t.fin {
            if !s.bytes.is_empty() || !s.pending.is_empty() {
                self.incomplete += 1;
            }
            self.streams.remove(&key);
        }
        messages
    }
    pub fn finish(&mut self) {
        self.incomplete += self
            .streams
            .values()
            .filter(|s| !s.bytes.is_empty() || !s.pending.is_empty())
            .count() as u64;
        self.streams.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    pub fn query() -> Vec<u8> {
        let mut q = vec![0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0];
        q.extend_from_slice(b"\x07example\x03com\0\0\x01\0\x01");
        q
    }
    #[test]
    fn parses_query_compressed_response_and_ipv6() {
        let q = query();
        assert_eq!(dns(&q).unwrap().questions[0].name, "example.com");
        let mut a = q.clone();
        a[2] = 0x81;
        a[3] = 0x80;
        a[7] = 2;
        a.extend_from_slice(&[0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 30, 0, 4, 1, 2, 3, 4]);
        a.extend_from_slice(&[0xc0, 12, 0, 28, 0, 1, 0, 0, 0, 30, 0, 16]);
        a.extend_from_slice(&Ipv6Addr::LOCALHOST.octets());
        let m = dns(&a).unwrap();
        assert!(m.response);
        assert_eq!(m.answers, ["1.2.3.4", "::1"]);
        for n in 0..q.len() {
            assert!(dns(&q[..n]).is_none());
        }
        let mut cycle = q;
        cycle[12] = 0xc0;
        cycle[13] = 12;
        assert!(dns(&cycle).is_none());
    }
    #[test]
    fn udp_ipv4_and_ipv6_extensions_fragments() {
        let q = query();
        let len = 28 + q.len();
        let mut b = vec![0; 28];
        b[0] = 0x45;
        b[2..4].copy_from_slice(&(len as u16).to_be_bytes());
        b[9] = 17;
        b[12..16].copy_from_slice(&[10, 0, 0, 2]);
        b[16..20].copy_from_slice(&[8, 8, 8, 8]);
        b[20..22].copy_from_slice(&50000u16.to_be_bytes());
        b[22..24].copy_from_slice(&53u16.to_be_bytes());
        b[24..26].copy_from_slice(&((8 + q.len()) as u16).to_be_bytes());
        b.extend(q);
        let t = transport(&b, 3).unwrap().unwrap();
        assert_eq!(dns(t.payload).unwrap().id, 0x1234);
        assert_eq!(t.destination.to_string(), "8.8.8.8");
        let mut wifi = vec![0u8; 24];
        wifi[0] = 8;
        wifi[1] = 0x40;
        wifi.extend_from_slice(&[0xaa, 0xaa, 3, 0, 0, 0, 8, 0]);
        wifi.extend_from_slice(&b);
        assert_eq!(
            dns(transport(&wifi, 2).unwrap().unwrap().payload)
                .unwrap()
                .id,
            0x1234
        );
        wifi[24] = 0;
        assert!(transport(&wifi, 2).unwrap().is_none());
        wifi[0] = 0x48;
        assert!(transport(&wifi, 2).unwrap().is_none());
        b[6] = 0x20;
        assert_eq!(transport(&b, 3).unwrap_err(), PacketError::Fragmented);
        b[6] = 0;
        assert_eq!(
            transport(&b[..b.len() - 1], 3).unwrap_err(),
            PacketError::Truncated
        );
        let mut v = vec![0; 48];
        v[0] = 0x60;
        v[4..6].copy_from_slice(&((b.len() - 20 + 8) as u16).to_be_bytes());
        v[6] = 0;
        v[40] = 17;
        v.extend_from_slice(&b[20..]);
        assert_eq!(transport(&v, 3).unwrap().unwrap().destination_port, 53);
    }
    #[test]
    fn tcp_reassembles_out_of_order_retransmissions_and_multiple_messages() {
        let q = query();
        let mut bytes = (q.len() as u16).to_be_bytes().to_vec();
        bytes.extend(&q);
        bytes.extend((q.len() as u16).to_be_bytes());
        bytes.extend(q);
        let base = Transport {
            source: "10.0.0.2".parse().unwrap(),
            destination: "1.1.1.1".parse().unwrap(),
            source_port: 40000,
            destination_port: 53,
            tcp: true,
            sequence: 100,
            syn: true,
            fin: false,
            payload: &[],
        };
        let mut r = TcpDns::default();
        assert!(r.push(1, &base, 0).is_empty());
        assert!(
            r.push(
                1,
                &Transport {
                    sequence: 111,
                    syn: false,
                    payload: &bytes[10..],
                    ..base.clone()
                },
                1
            )
            .is_empty()
        );
        let result = r.push(
            1,
            &Transport {
                sequence: 101,
                syn: false,
                payload: &bytes[..10],
                ..base.clone()
            },
            2,
        );
        assert_eq!(result.len(), 2);
        assert!(
            r.push(
                1,
                &Transport {
                    sequence: 101,
                    syn: false,
                    payload: &bytes[..10],
                    ..base.clone()
                },
                3
            )
            .is_empty()
        );
        assert_eq!(r.incomplete, 0);
        let mut r = TcpDns::default();
        assert!(
            r.push(
                1,
                &Transport {
                    syn: false,
                    payload: &bytes,
                    ..base
                },
                0
            )
            .is_empty()
        );
        assert_eq!(r.incomplete, 1);
    }
}
