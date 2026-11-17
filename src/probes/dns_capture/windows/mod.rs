mod etw;
mod pktmon;

use super::{Coverage, Interface, Observation, Report, now, wire};
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc::{self, SyncSender},
    },
    time::{Duration, Instant},
};
use windows_sys::Win32::{
    Foundation::{CloseHandle, FILETIME},
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
    pub event_loss: Arc<AtomicU64>,
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
fn consume(
    input: Input,
    report: &mut Report,
    tcp: &mut wire::TcpDns,
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
            let iface = interfaces.get(&component).cloned();
            if iface.as_ref().and_then(|i| i.hardware).is_none() {
                report.coverage.unknown_interfaces += 1;
            }
            let encrypted = t.source_port == 853 || t.destination_port == 853;
            let resolver = if matches!(t.destination_port, 53 | 853) {
                t.destination
            } else {
                t.source
            };
            let local = if matches!(t.destination_port, 53 | 853) {
                format!("{}:{}", t.source, t.source_port)
            } else {
                format!("{}:{}", t.destination, t.destination_port)
            };
            let base = Observation {
                observed_at: at,
                source: "packet".into(),
                resolver: Some(resolver.to_string()),
                local_endpoint: Some(local),
                interface: iface,
                direction: Some(
                    match direction {
                        3 => "rx",
                        4 => "tx",
                        _ => "unknown",
                    }
                    .into(),
                ),
                transport: if encrypted {
                    if t.tcp {
                        "TLS/853 candidate"
                    } else {
                        "QUIC/853 candidate"
                    }
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
                    report.add(Observation {
                        phase: "encrypted_candidate".into(),
                        ..base
                    });
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
            for message in messages {
                report.coverage.dns_messages += 1;
                for q in message.questions {
                    report.add(Observation {
                        phase: if message.response {
                            "response"
                        } else {
                            "query"
                        }
                        .into(),
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
        event_loss: Arc::new(AtomicU64::new(0)),
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
            consume(input, &mut shared.lock().unwrap(), &mut tcp, &interfaces);
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
        consume(input, &mut shared.lock().unwrap(), &mut tcp, &interfaces);
    }
    tcp.finish();
    update_coverage(&mut shared.lock().unwrap().coverage, &sink, &tcp);
}
fn update_coverage(c: &mut Coverage, s: &Sink, t: &wire::TcpDns) {
    c.queue_drops = s.queue_drops.load(Ordering::Relaxed);
    c.lost_packets = s.packet_loss.load(Ordering::Relaxed);
    c.lost_events = s.event_loss.load(Ordering::Relaxed);
    c.incomplete_tcp = t.incomplete;
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
}
