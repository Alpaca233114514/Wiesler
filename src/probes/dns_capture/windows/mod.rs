mod etw;
mod pktmon;

use super::{Coverage, Interface, Observation, Report, now, wire};
use std::{
    collections::{HashMap, VecDeque},
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
    ptr::null_mut,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc::{self, SyncSender},
    },
    time::{Duration, Instant},
};
use windows_sys::Win32::{
    Foundation::{CloseHandle, FILETIME},
    NetworkManagement::IpHelper::{
        GetExtendedTcpTable, GetExtendedUdpTable, MIB_TCP6TABLE_OWNER_PID, MIB_TCPTABLE_OWNER_PID,
        MIB_UDP6TABLE_OWNER_PID, MIB_UDPTABLE_OWNER_PID, TCP_TABLE_OWNER_PID_ALL,
        UDP_TABLE_OWNER_PID,
    },
    Networking::WinSock::{AF_INET, AF_INET6},
    System::Threading::{
        GetProcessTimes, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION, QueryFullProcessImageNameW,
    },
};

pub(super) struct NativeError {
    pub permission: bool,
    pub message: String,
}
pub(super) fn native_error(api: &str, code: u32) -> NativeError {
    let permission = code == 5 || code == 0x80070005 || code == 1314 || code == 0x80070522;
    NativeError {
        permission,
        message: format!(
            "{api}：{} (0x{code:08X})",
            if permission {
                "Windows 未授予采集权限"
            } else {
                "系统接口不可用"
            }
        ),
    }
}
pub(super) fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(Some(0)).collect()
}
pub(super) fn utf16(v: &[u16]) -> String {
    String::from_utf16_lossy(&v[..v.iter().position(|c| *c == 0).unwrap_or(v.len())])
}
pub(super) fn filetime(t: i64) -> u64 {
    t.saturating_sub(116_444_736_000_000_000).max(0) as u64 / 10_000
}

pub(super) enum Input {
    Dns(Box<Observation>),
    Packet {
        at: u64,
        group: u64,
        component: u32,
        kind: u16,
        direction: u16,
        bytes: Vec<u8>,
        dropped: bool,
    },
    Error {
        source: &'static str,
        message: String,
    },
    Warning(String),
}
#[derive(Clone)]
pub(super) struct Sink {
    pub tx: SyncSender<Input>,
    pub queue_drops: Arc<AtomicU64>,
    pub packet_loss: Arc<AtomicU64>,
    pub packet_read_count: Arc<AtomicU64>,
    pub oversized_read_credit: Arc<AtomicU64>,
    pub event_loss: Arc<AtomicU64>,
    pub oversized_warnings: Arc<AtomicU64>,
}
impl Sink {
    pub fn send(&self, input: Input) {
        if self.tx.try_send(input).is_err() {
            self.queue_drops.fetch_add(1, Ordering::Relaxed);
        }
    }
}
fn process_name(pid: u32, observed_at: u64) -> Option<String> {
    unsafe {
        let process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if process.is_null() {
            return None;
        }
        let mut created = FILETIME::default();
        let mut exited = FILETIME::default();
        let mut kernel = FILETIME::default();
        let mut user = FILETIME::default();
        let valid =
            GetProcessTimes(process, &mut created, &mut exited, &mut kernel, &mut user) != 0;
        let born = ((created.dwHighDateTime as u64) << 32) | created.dwLowDateTime as u64;
        // 避免把已复用的 PID 归属到新进程。 / Reject process names from a reused PID.
        if !valid || filetime(born as i64) > observed_at {
            CloseHandle(process);
            return None;
        }
        let mut b = vec![0u16; 32768];
        let mut len = b.len() as u32;
        let ok = QueryFullProcessImageNameW(process, 0, b.as_mut_ptr(), &mut len);
        CloseHandle(process);
        if ok == 0 {
            return None;
        }
        std::path::Path::new(&String::from_utf16_lossy(&b[..len as usize]))
            .file_name()
            .map(|p| p.to_string_lossy().into_owned())
    }
}
fn socket_owner_pid(
    tcp: bool,
    local_ip: IpAddr,
    local_port: u16,
    remote_ip: IpAddr,
    remote_port: u16,
) -> Option<u32> {
    // 毫秒级查表关联 53/853 网卡报文所属进程（支持 IPv4/IPv6）。 / Lookup owning PID for 53/853 packets via IP Helper tables.
    match (tcp, local_ip, remote_ip) {
        (true, IpAddr::V4(lip), IpAddr::V4(rip)) => {
            let mut size = 0u32;
            unsafe {
                GetExtendedTcpTable(
                    null_mut(),
                    &mut size,
                    0,
                    AF_INET as u32,
                    TCP_TABLE_OWNER_PID_ALL,
                    0,
                );
            }
            if size == 0 || size > 16 * 1024 * 1024 {
                return None;
            }
            let mut buf = vec![0u8; size as usize];
            if unsafe {
                GetExtendedTcpTable(
                    buf.as_mut_ptr().cast(),
                    &mut size,
                    0,
                    AF_INET as u32,
                    TCP_TABLE_OWNER_PID_ALL,
                    0,
                )
            } != 0
            {
                return None;
            }
            let table = unsafe { &*buf.as_ptr().cast::<MIB_TCPTABLE_OWNER_PID>() };
            let count = table.dwNumEntries as usize;
            let rows = unsafe { std::slice::from_raw_parts(table.table.as_ptr(), count) };
            rows.iter()
                .find(|row| {
                    Ipv4Addr::from(row.dwLocalAddr.to_ne_bytes()) == lip
                        && u16::from_be(row.dwLocalPort as u16) == local_port
                        && Ipv4Addr::from(row.dwRemoteAddr.to_ne_bytes()) == rip
                        && u16::from_be(row.dwRemotePort as u16) == remote_port
                        && row.dwOwningPid != 0
                })
                .map(|row| row.dwOwningPid)
        }
        (true, IpAddr::V6(lip), IpAddr::V6(rip)) => {
            let mut size = 0u32;
            unsafe {
                GetExtendedTcpTable(
                    null_mut(),
                    &mut size,
                    0,
                    AF_INET6 as u32,
                    TCP_TABLE_OWNER_PID_ALL,
                    0,
                );
            }
            if size == 0 || size > 16 * 1024 * 1024 {
                return None;
            }
            let mut buf = vec![0u8; size as usize];
            if unsafe {
                GetExtendedTcpTable(
                    buf.as_mut_ptr().cast(),
                    &mut size,
                    0,
                    AF_INET6 as u32,
                    TCP_TABLE_OWNER_PID_ALL,
                    0,
                )
            } != 0
            {
                return None;
            }
            let table = unsafe { &*buf.as_ptr().cast::<MIB_TCP6TABLE_OWNER_PID>() };
            let count = table.dwNumEntries as usize;
            let rows = unsafe { std::slice::from_raw_parts(table.table.as_ptr(), count) };
            rows.iter()
                .find(|row| {
                    Ipv6Addr::from(row.ucLocalAddr) == lip
                        && u16::from_be(row.dwLocalPort as u16) == local_port
                        && Ipv6Addr::from(row.ucRemoteAddr) == rip
                        && u16::from_be(row.dwRemotePort as u16) == remote_port
                        && row.dwOwningPid != 0
                })
                .map(|row| row.dwOwningPid)
        }
        (false, IpAddr::V4(lip), _) => {
            let mut size = 0u32;
            unsafe {
                GetExtendedUdpTable(
                    null_mut(),
                    &mut size,
                    0,
                    AF_INET as u32,
                    UDP_TABLE_OWNER_PID,
                    0,
                );
            }
            if size == 0 || size > 16 * 1024 * 1024 {
                return None;
            }
            let mut buf = vec![0u8; size as usize];
            if unsafe {
                GetExtendedUdpTable(
                    buf.as_mut_ptr().cast(),
                    &mut size,
                    0,
                    AF_INET as u32,
                    UDP_TABLE_OWNER_PID,
                    0,
                )
            } != 0
            {
                return None;
            }
            let table = unsafe { &*buf.as_ptr().cast::<MIB_UDPTABLE_OWNER_PID>() };
            let count = table.dwNumEntries as usize;
            let rows = unsafe { std::slice::from_raw_parts(table.table.as_ptr(), count) };
            let exact: Vec<u32> = rows
                .iter()
                .filter(|row| {
                    Ipv4Addr::from(row.dwLocalAddr.to_ne_bytes()) == lip
                        && u16::from_be(row.dwLocalPort as u16) == local_port
                        && row.dwOwningPid != 0
                })
                .map(|row| row.dwOwningPid)
                .collect();
            if exact.len() == 1 {
                return Some(exact[0]);
            }
            let wildcard: Vec<u32> = rows
                .iter()
                .filter(|row| {
                    Ipv4Addr::from(row.dwLocalAddr.to_ne_bytes()).is_unspecified()
                        && u16::from_be(row.dwLocalPort as u16) == local_port
                        && row.dwOwningPid != 0
                })
                .map(|row| row.dwOwningPid)
                .collect();
            (wildcard.len() == 1).then(|| wildcard[0])
        }
        (false, IpAddr::V6(lip), _) => {
            let mut size = 0u32;
            unsafe {
                GetExtendedUdpTable(
                    null_mut(),
                    &mut size,
                    0,
                    AF_INET6 as u32,
                    UDP_TABLE_OWNER_PID,
                    0,
                );
            }
            if size == 0 || size > 16 * 1024 * 1024 {
                return None;
            }
            let mut buf = vec![0u8; size as usize];
            if unsafe {
                GetExtendedUdpTable(
                    buf.as_mut_ptr().cast(),
                    &mut size,
                    0,
                    AF_INET6 as u32,
                    UDP_TABLE_OWNER_PID,
                    0,
                )
            } != 0
            {
                return None;
            }
            let table = unsafe { &*buf.as_ptr().cast::<MIB_UDP6TABLE_OWNER_PID>() };
            let count = table.dwNumEntries as usize;
            let rows = unsafe { std::slice::from_raw_parts(table.table.as_ptr(), count) };
            let exact: Vec<u32> = rows
                .iter()
                .filter(|row| {
                    Ipv6Addr::from(row.ucLocalAddr) == lip
                        && u16::from_be(row.dwLocalPort as u16) == local_port
                        && row.dwOwningPid != 0
                })
                .map(|row| row.dwOwningPid)
                .collect();
            if exact.len() == 1 {
                return Some(exact[0]);
            }
            let wildcard: Vec<u32> = rows
                .iter()
                .filter(|row| {
                    Ipv6Addr::from(row.ucLocalAddr).is_unspecified()
                        && u16::from_be(row.dwLocalPort as u16) == local_port
                        && row.dwOwningPid != 0
                })
                .map(|row| row.dwOwningPid)
                .collect();
            (wildcard.len() == 1).then(|| wildcard[0])
        }
        _ => None,
    }
}

fn normalize_direction(direction: u16) -> &'static str {
    // 兼容 PktMon 全量方向枚举：In/Rx/Ingress 归入 rx，Out/Tx/Egress 归入 tx。 / Map all PktMon inbound/outbound direction tags.
    match direction {
        1 | 3 | 5 => "rx",
        2 | 4 | 6 => "tx",
        _ => "unknown",
    }
}

#[derive(Hash, PartialEq, Eq, Clone)]
struct PacketKey {
    group: u64,
    direction: &'static str,
    phase: &'static str,
    dns_id: Option<u16>,
    domain: String,
}

#[derive(Default)]
struct PacketDeduper {
    order: VecDeque<PacketKey>,
    // 记录已见过该分组时的接口识别等级：0=未知，1=虚拟/本地，2=物理。 / Track best interface rank seen per packet group.
    seen: HashMap<PacketKey, u8>,
}

impl PacketDeduper {
    fn rank(iface: Option<&Interface>) -> u8 {
        match iface.and_then(|i| i.hardware) {
            Some(true) => 2,
            Some(false) => 1,
            None => 0,
        }
    }
    fn should_emit(&mut self, key: PacketKey, iface: Option<&Interface>) -> bool {
        if key.group == 0 {
            return true;
        }
        let rank = Self::rank(iface);
        if let Some(&prev) = self.seen.get(&key) {
            if rank <= prev {
                return false;
            }
            self.seen.insert(key, rank);
            return true;
        }
        if self.order.len() >= 4096
            && let Some(oldest) = self.order.pop_front()
        {
            self.seen.remove(&oldest);
        }
        self.order.push_back(key.clone());
        self.seen.insert(key, rank);
        true
    }
}

fn consume(
    input: Input,
    report: &mut Report,
    tcp: &mut wire::TcpDns,
    deduper: &mut PacketDeduper,
    interfaces: &HashMap<u32, Interface>,
) {
    match input {
        Input::Warning(message) => report.error(message),
        Input::Error { source, message } => {
            report.error(message);
            if source == "packet" {
                report.packet_state = "failed".into();
            } else {
                report.event_state = "failed".into();
            }
        }
        Input::Dns(mut row) => {
            report.coverage.windows_events += 1;
            row.process = row.pid.and_then(|pid| process_name(pid, row.observed_at));
            report.add(*row);
        }
        Input::Packet {
            at,
            group,
            component,
            kind,
            direction,
            bytes,
            dropped,
        } => {
            report.coverage.packets += 1;
            *report.coverage.packet_types.entry(kind).or_default() += 1;
            if dropped {
                return;
            }
            let t = match wire::transport(&bytes, kind) {
                Ok(Some(t)) => t,
                Ok(None) => {
                    report.coverage.non_dns_packets += 1;
                    return;
                }
                Err(wire::PacketError::Fragmented) => {
                    report.coverage.fragments += 1;
                    return;
                }
                Err(wire::PacketError::Unsupported) => {
                    report.coverage.unsupported_packets += 1;
                    return;
                }
                Err(_) => {
                    report.coverage.parse_failures += 1;
                    return;
                }
            };
            let is_loopback = t.source.is_loopback() || t.destination.is_loopback();
            let iface = if is_loopback {
                let mut info = interfaces
                    .get(&component)
                    .cloned()
                    .unwrap_or_else(|| Interface {
                        component_id: component,
                        name: "Loopback".into(),
                        index: None,
                        hardware: Some(false),
                    });
                info.hardware = Some(false);
                if info.name.is_empty() {
                    info.name = "Loopback".into();
                }
                Some(info)
            } else {
                interfaces.get(&component).cloned()
            };
            let encrypted = t.source_port == 853 || t.destination_port == 853;
            let outbound_to_resolver = matches!(t.destination_port, 53 | 853);
            let (resolver, resolver_port, local_ip, local_port) = if outbound_to_resolver {
                (t.destination, t.destination_port, t.source, t.source_port)
            } else {
                (t.source, t.source_port, t.destination, t.destination_port)
            };
            let local = format!("{local_ip}:{local_port}");
            let dir = normalize_direction(direction);
            let owner_pid = socket_owner_pid(t.tcp, local_ip, local_port, resolver, resolver_port);
            let owner_proc = owner_pid.and_then(|pid| process_name(pid, at));
            let base = Observation {
                observed_at: at,
                source: "packet".into(),
                resolver: Some(resolver.to_string()),
                local_endpoint: Some(local),
                pid: owner_pid,
                process: owner_proc,
                process_evidence: owner_pid.map(|_| "socket_table".into()),
                interface: iface.clone(),
                direction: Some(dir.into()),
                transport: if encrypted {
                    if t.tcp { "DoT (853)" } else { "DoQ (853)" }
                } else if t.tcp {
                    "TCP"
                } else {
                    "UDP"
                }
                .into(),
                ..Default::default()
            };
            if encrypted {
                if !t.payload.is_empty() {
                    let key = PacketKey {
                        group,
                        direction: dir,
                        phase: "encrypted_candidate",
                        dns_id: None,
                        domain: resolver.to_string(),
                    };
                    if deduper.should_emit(key, iface.as_ref()) {
                        report.add(Observation {
                            phase: "encrypted_candidate".into(),
                            ..base
                        });
                    } else {
                        report.coverage.duplicate_packets += 1;
                    }
                }
                return;
            }
            let messages = if t.tcp {
                tcp.push(component, &t, at)
            } else {
                match wire::dns(t.payload) {
                    Some(m) => vec![m],
                    None => {
                        report.coverage.parse_failures += 1;
                        vec![]
                    }
                }
            };
            let mut counted_unknown = false;
            for message in messages {
                for q in message.questions {
                    let phase = if message.response {
                        "response"
                    } else {
                        "query"
                    };
                    let key = PacketKey {
                        group,
                        direction: dir,
                        phase,
                        dns_id: Some(message.id),
                        domain: q.name.clone(),
                    };
                    if !deduper.should_emit(key, iface.as_ref()) {
                        report.coverage.duplicate_packets += 1;
                        continue;
                    }
                    if !is_loopback
                        && !resolver.is_multicast()
                        && !counted_unknown
                        && iface.as_ref().and_then(|i| i.hardware).is_none()
                    {
                        report.coverage.unknown_interfaces += 1;
                        counted_unknown = true;
                    }
                    report.coverage.dns_messages += 1;
                    report.add(Observation {
                        phase: phase.into(),
                        domain: Some(q.name),
                        query_type: Some(q.kind),
                        answers: message.answers.clone(),
                        status: message.response.then_some(message.rcode as u32),
                        dns_id: Some(message.id),
                        truncated: message.truncated,
                        ..base.clone()
                    });
                }
            }
        }
    }
}

pub(super) fn run(shared: Arc<Mutex<Report>>, stop: Arc<AtomicBool>, auto_authorize: bool) {
    let started = Instant::now();
    let (tx, rx) = mpsc::sync_channel(8192);
    let sink = Sink {
        tx,
        queue_drops: Arc::new(AtomicU64::new(0)),
        packet_loss: Arc::new(AtomicU64::new(0)),
        packet_read_count: Arc::new(AtomicU64::new(0)),
        oversized_read_credit: Arc::new(AtomicU64::new(0)),
        event_loss: Arc::new(AtomicU64::new(0)),
        oversized_warnings: Arc::new(AtomicU64::new(0)),
    };
    let mut packet = match pktmon::Session::start(sink.clone()) {
        Ok(p) => {
            let mut r = shared.lock().unwrap();
            r.packet_state = "active".into();
            r.interfaces = p.interfaces.values().cloned().collect();
            Some(p)
        }
        Err(e) => {
            let mut r = shared.lock().unwrap();
            r.packet_state = if e.permission {
                "permission_required"
            } else {
                "unsupported"
            }
            .into();
            r.error(e.message);
            None
        }
    };
    let mut events = match etw::Session::start(sink.clone()) {
        Ok(e) => {
            shared.lock().unwrap().event_state = "active".into();
            Some(e)
        }
        Err(e) => {
            let mut r = shared.lock().unwrap();
            r.event_state = if e.permission {
                "permission_required"
            } else {
                "unsupported"
            }
            .into();
            r.error(e.message);
            None
        }
    };
    let needs_authorization = {
        let r = shared.lock().unwrap();
        auto_authorize
            && (r.packet_state == "permission_required" || r.event_state == "permission_required")
    };
    if needs_authorization && !stop.load(Ordering::Acquire) {
        // 先释放普通权限会话，再由同一轮启动一次 UAC 助手。 / Release local sessions before the one-shot UAC fallback.
        drop(packet.take());
        drop(events.take());
        let seconds = {
            let mut r = shared.lock().unwrap();
            let seconds = ((r.ends_at - r.started_at) / 1000) as u32;
            *r = Report::new(seconds);
            r.packet_state = "awaiting_authorization".into();
            r.event_state = "awaiting_authorization".into();
            seconds
        };
        if !stop.load(Ordering::Acquire) {
            super::authorization::run(shared, stop, seconds, true);
        }
        return;
    }
    let mut interfaces = packet
        .as_ref()
        .map(|p| p.interfaces.clone())
        .unwrap_or_default();
    let mut tcp = wire::TcpDns::default();
    let mut deduper = PacketDeduper::default();
    let mut refreshed = now();
    let duration = {
        let r = shared.lock().unwrap();
        Duration::from_millis(r.ends_at - r.started_at)
    };
    while (packet.is_some() || events.is_some())
        && !stop.load(Ordering::Acquire)
        && started.elapsed() < duration
    {
        if let Ok(input) = rx.recv_timeout(Duration::from_millis(100)) {
            consume(
                input,
                &mut shared.lock().unwrap(),
                &mut tcp,
                &mut deduper,
                &interfaces,
            );
        }
        if now().saturating_sub(refreshed) > 5000 {
            if let Some(p) = &mut packet {
                match p.refresh() {
                    Ok(()) => {
                        interfaces = p.interfaces.clone();
                        shared.lock().unwrap().interfaces = interfaces.values().cloned().collect();
                    }
                    Err(e) => shared.lock().unwrap().error(e.message),
                }
            }
            if let Some(e) = &mut events {
                e.loss();
            }
            refreshed = now();
        }
        update_coverage(&mut shared.lock().unwrap().coverage, &sink, &tcp);
    }
    // 先关闭来源，再排空队列，保证最后一个查询不丢在停止按钮后。 / Drain after producers stop.
    drop(packet.take());
    drop(events.take());
    for input in rx.try_iter() {
        consume(
            input,
            &mut shared.lock().unwrap(),
            &mut tcp,
            &mut deduper,
            &interfaces,
        );
    }
    tcp.finish();
    update_coverage(&mut shared.lock().unwrap().coverage, &sink, &tcp);
}
fn update_coverage(c: &mut Coverage, s: &Sink, t: &wire::TcpDns) {
    c.queue_drops = s.queue_drops.load(Ordering::Relaxed);
    c.lost_packets = s.packet_loss.load(Ordering::Relaxed);
    c.lost_events = s.event_loss.load(Ordering::Relaxed);
    c.incomplete_tcp = t.incomplete;
    c.oversized_warnings = s.oversized_warnings.load(Ordering::Relaxed);
}

#[cfg(test)]
mod tests {
    use super::super::Capture;
    use super::*;
    #[test]
    #[ignore = "requires Windows capture permission; observes local DNS only"]
    fn live_capture() {
        let mut c = Capture::start(3).unwrap();
        std::thread::sleep(Duration::from_secs(4));
        let r = c.stop();
        println!("{}", serde_json::to_string_pretty(&r).unwrap());
        assert_eq!(r.packet_state, "active");
        assert_eq!(r.event_state, "active");
        assert!(!r.running);
    }
    #[test]
    fn windows_permission_errors_are_explicit() {
        assert!(native_error("test", 0x80070005).permission);
        assert!(!native_error("test", 127).permission);
    }
    #[test]
    fn pktmon_directions_and_group_dedup_handle_all_stack_tags() {
        assert_eq!(normalize_direction(1), "rx");
        assert_eq!(normalize_direction(2), "tx");
        assert_eq!(normalize_direction(3), "rx");
        assert_eq!(normalize_direction(4), "tx");
        assert_eq!(normalize_direction(5), "rx");
        assert_eq!(normalize_direction(6), "tx");
        assert_eq!(normalize_direction(0), "unknown");

        let mut d = PacketDeduper::default();
        let key = PacketKey {
            group: 42,
            direction: "tx",
            phase: "query",
            dns_id: Some(1),
            domain: "example.com".into(),
        };
        let unknown = Interface {
            name: "Filter".into(),
            hardware: None,
            ..Default::default()
        };
        let physical = Interface {
            name: "WLAN".into(),
            hardware: Some(true),
            ..Default::default()
        };
        assert!(d.should_emit(key.clone(), Some(&unknown)));
        assert!(!d.should_emit(key.clone(), Some(&unknown)));
        // 当同分组后续命中更明确的物理网卡时允许升级上报。 / Allow upgrading same group when physical NIC appears.
        assert!(d.should_emit(key.clone(), Some(&physical)));
        assert!(!d.should_emit(key, Some(&physical)));
    }
}
