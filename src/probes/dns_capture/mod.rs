//! Local DNS events and on-interface packet evidence; never contacts a test service.
#[cfg(windows)]
mod authorization;
#[cfg(windows)]
mod windows;
mod wire;

use serde::{Deserialize, Serialize};
use std::{
    collections::VecDeque,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{SystemTime, UNIX_EPOCH},
};

const ROW_LIMIT: usize = 2000;
pub(super) fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Interface {
    pub component_id: u32,
    pub name: String,
    pub index: Option<u32>,
    pub hardware: Option<bool>,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Observation {
    pub id: u64,
    pub observed_at: u64,
    pub source: String,
    pub phase: String,
    pub domain: Option<String>,
    pub query_type: Option<u16>,
    pub answers: Vec<String>,
    pub status: Option<u32>,
    pub dns_id: Option<u16>,
    pub pid: Option<u32>,
    pub process: Option<String>,
    pub process_evidence: Option<String>,
    pub resolver: Option<String>,
    pub local_endpoint: Option<String>,
    pub transport: String,
    pub interface: Option<Interface>,
    pub direction: Option<String>,
    pub activity_id: Option<String>,
    pub event_id: Option<u16>,
    pub truncated: bool,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Coverage {
    #[serde(default)]
    pub packet_types: std::collections::BTreeMap<u16, u64>,
    #[serde(default)]
    pub non_dns_packets: u64,
    pub packets: u64,
    pub dns_messages: u64,
    pub windows_events: u64,
    pub lost_packets: u64,
    pub lost_events: u64,
    pub queue_drops: u64,
    pub retained_rows_dropped: u64,
    pub parse_failures: u64,
    pub fragments: u64,
    pub unsupported_packets: u64,
    pub incomplete_tcp: u64,
    pub unknown_interfaces: u64,
    pub physical_queries: u64,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Report {
    pub started_at: u64,
    pub ends_at: u64,
    pub stopped_at: Option<u64>,
    pub running: bool,
    pub packet_state: String,
    pub event_state: String,
    pub errors: Vec<String>,
    pub interfaces: Vec<Interface>,
    pub coverage: Coverage,
    pub observations: VecDeque<Observation>,
    pub leak_evidence: Vec<Observation>,
}
impl Report {
    fn new(seconds: u32) -> Self {
        let start = now();
        Self {
            started_at: start,
            ends_at: start + seconds as u64 * 1000,
            stopped_at: None,
            running: true,
            packet_state: "starting".into(),
            event_state: "starting".into(),
            errors: vec![],
            interfaces: vec![],
            coverage: Coverage::default(),
            observations: VecDeque::new(),
            leak_evidence: vec![],
        }
    }
    pub(super) fn add(&mut self, mut row: Observation) {
        row.id = self.observations.back().map_or(1, |r| r.id + 1);
        if physical_query(&row) {
            self.coverage.physical_queries += 1;
            if self.leak_evidence.len() < 100 {
                self.leak_evidence.push(row.clone());
            }
        }
        if self.observations.len() >= ROW_LIMIT {
            self.observations.pop_front();
            self.coverage.retained_rows_dropped += 1;
        }
        self.observations.push_back(row);
    }
    pub(super) fn error(&mut self, message: String) {
        if !self.errors.contains(&message) {
            self.errors.push(message);
        }
    }
}

pub struct Capture {
    report: Arc<Mutex<Report>>,
    stop: Arc<AtomicBool>,
    worker: Option<std::thread::JoinHandle<()>>,
}
impl Capture {
    pub fn start(seconds: u32) -> Result<Self, String> {
        Self::launch(seconds, None, false)
    }
    pub fn start_auto(seconds: u32) -> Result<Self, String> {
        Self::launch(seconds, None, true)
    }
    pub fn start_authorized(seconds: u32) -> Result<Self, String> {
        Self::launch(seconds, Some(true), false)
    }
    pub fn start_helper(seconds: u32) -> Result<Self, String> {
        Self::launch(seconds, Some(false), false)
    }
    fn launch(seconds: u32, authorize: Option<bool>, auto_authorize: bool) -> Result<Self, String> {
        if !(1..=300).contains(&seconds) {
            return Err("采集时长必须为 1–300 秒".into());
        }
        let report = Arc::new(Mutex::new(Report::new(seconds)));
        let stop = Arc::new(AtomicBool::new(false));
        let r = report.clone();
        let s = stop.clone();
        let worker = std::thread::Builder::new()
            .name("local-dns-capture".into())
            .spawn(move || {
                #[cfg(windows)]
                if let Some(authorize) = authorize {
                    authorization::run(r.clone(), s, seconds, authorize);
                } else {
                    windows::run(r.clone(), s, auto_authorize);
                }
                #[cfg(not(windows))]
                {
                    let _ = (s, authorize, auto_authorize);
                    let mut state = r.lock().unwrap();
                    state.packet_state = "unsupported".into();
                    state.event_state = "unsupported".into();
                    state.error("本地 DNS 采集仅支持 Windows".into());
                }
                let mut state = r.lock().unwrap();
                state.running = false;
                state.stopped_at = Some(now());
            })
            .map_err(|e| e.to_string())?;
        Ok(Self {
            report,
            stop,
            worker: Some(worker),
        })
    }
    pub fn snapshot(&self) -> Report {
        self.report.lock().unwrap().clone()
    }
    pub fn stop(&mut self) -> Report {
        self.stop.store(true, Ordering::Release);
        if let Some(worker) = self.worker.take()
            && worker.join().is_err()
        {
            let mut r = self.report.lock().unwrap();
            r.running = false;
            r.stopped_at = Some(now());
            r.error("DNS 采集线程异常退出".into());
        }
        self.snapshot()
    }
}

pub fn run_authorized_helper(args: &[String]) -> Option<Result<(), String>> {
    if args.first().map(String::as_str) != Some("--dns-capture-helper") {
        return None;
    }
    #[cfg(windows)]
    return Some(authorization::helper(args));
    #[cfg(not(windows))]
    Some(Err("仅支持 Windows".into()))
}
impl Drop for Capture {
    fn drop(&mut self) {
        self.stop();
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Policy {
    ProxyRequired,
    Observe,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Assessment {
    pub status: String,
    pub title: String,
    pub detail: String,
    pub evidence_ids: Vec<u64>,
    pub physical_queries: u64,
    pub encrypted_events: u64,
}

fn physical_query(r: &Observation) -> bool {
    // 只有物理网卡发送的已解析查询能证明明文离开本机。 / Only decoded NIC TX queries prove plaintext egress.
    r.source == "packet"
        && r.phase == "query"
        && r.direction.as_deref() == Some("tx")
        && r.interface.as_ref().and_then(|i| i.hardware) == Some(true)
        && r.domain.as_ref().is_some_and(|d| !d.is_empty())
        && r.resolver
            .as_ref()
            .and_then(|s| s.parse::<std::net::IpAddr>().ok())
            .is_some_and(|ip| !ip.is_loopback() && !ip.is_unspecified() && !ip.is_multicast())
}
pub fn assess(report: &Report, policy: Policy) -> Assessment {
    let evidence: Vec<_> = report.leak_evidence.iter().map(|r| r.id).collect();
    let encrypted = report
        .observations
        .iter()
        .filter(|r| matches!(r.transport.as_str(), "DoH" | "DoT"))
        .count() as u64;
    let c = &report.coverage;
    let partial = report.packet_state != "active"
        || report.event_state != "active"
        || !report.errors.is_empty()
        || c.lost_packets
            + c.lost_events
            + c.queue_drops
            + c.retained_rows_dropped
            + c.parse_failures
            + c.fragments
            + c.unsupported_packets
            + c.incomplete_tcp
            + c.unknown_interfaces
            > 0;
    let (status, title, detail) = if !evidence.is_empty() && policy == Policy::ProxyRequired {
        (
            "leak",
            "检测到明文 DNS 绕过隧道",
            "物理网卡实际发出了 DNS 查询，与“DNS 应经代理/隧道”的检查条件不符。下方记录保留域名、解析器和网卡证据。",
        )
    } else if !evidence.is_empty() {
        (
            "observed",
            "观察到明文 DNS 从物理网卡发出",
            "当前为仅观察模式。已确认查询离开本机；是否允许这种分流由你的网络策略决定。",
        )
    } else if report.packet_state == "awaiting_authorization" {
        (
            "awaiting_authorization",
            "等待 Windows 授权",
            "请在 Windows 提示中确认，授权后自动开始采集。",
        )
    } else if report.packet_state == "cancelled" {
        (
            "cancelled",
            "已取消检查",
            "未取得采集授权；需要重试时点击“开始本地检查”。",
        )
    } else if report.packet_state == "permission_required"
        || report.event_state == "permission_required"
    {
        (
            "unavailable",
            "DNS 采集权限不足",
            "Windows 拒绝了本地事件或网卡采集。尚未完成 DNS 泄漏检查，不能以零条记录代替检测结果。",
        )
    } else if partial {
        (
            "incomplete",
            "DNS 覆盖不完整",
            "采集失败、丢失或无法解读的记录见采集状态；本轮无法排除 DNS 泄漏。",
        )
    } else if c.dns_messages == 0 {
        (
            "no_sample",
            "尚无网卡 DNS 查询样本",
            "请在观察期间正常使用应用。系统缓存和加密 DNS 可能不产生可解读的 53 端口报文。",
        )
    } else {
        (
            "not_observed",
            "本轮未发现明文 DNS 绕行",
            "仅覆盖本轮捕获的明文 DNS。系统 DoH/DoT 由 DNS 事件单列；应用自行加密的 DNS 内容不可见，不能据此认证无泄漏。",
        )
    };
    Assessment {
        status: status.into(),
        title: title.into(),
        detail: detail.into(),
        physical_queries: report.coverage.physical_queries,
        evidence_ids: evidence,
        encrypted_events: encrypted,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn leakage_requires_decoded_outbound_physical_evidence_and_explicit_policy() {
        let mut r = Report::new(30);
        r.packet_state = "active".into();
        r.event_state = "active".into();
        let row = Observation {
            source: "packet".into(),
            phase: "query".into(),
            domain: Some("example.com".into()),
            resolver: Some("10.0.0.1".into()),
            direction: Some("tx".into()),
            interface: Some(Interface {
                hardware: Some(true),
                ..Default::default()
            }),
            ..Default::default()
        };
        for (source, phase, direction, hardware) in [
            ("windows-dns", "query", "tx", Some(true)),
            ("packet", "response", "rx", Some(true)),
            ("packet", "query", "tx", Some(false)),
            ("packet", "query", "tx", None),
        ] {
            let mut test = r.clone();
            let mut o = row.clone();
            o.source = source.into();
            o.phase = phase.into();
            o.direction = Some(direction.into());
            o.interface.as_mut().unwrap().hardware = hardware;
            test.add(o);
            assert_ne!(assess(&test, Policy::ProxyRequired).status, "leak");
        }
        r.add(row);
        assert_eq!(assess(&r, Policy::ProxyRequired).status, "leak");
        assert_eq!(assess(&r, Policy::Observe).status, "observed");
        r.coverage.lost_packets = 3;
        assert_eq!(assess(&r, Policy::ProxyRequired).status, "leak");
    }
    #[test]
    fn empty_failed_and_lossy_capture_never_pass() {
        let mut r = Report::new(30);
        r.packet_state = "permission_required".into();
        assert_eq!(assess(&r, Policy::ProxyRequired).status, "unavailable");
        r.packet_state = "active".into();
        r.event_state = "active".into();
        assert_eq!(assess(&r, Policy::ProxyRequired).status, "no_sample");
        r.coverage.dns_messages = 1;
        r.coverage.lost_events = 1;
        assert_eq!(assess(&r, Policy::ProxyRequired).status, "incomplete");
        r.coverage.lost_events = 0;
        assert_eq!(assess(&r, Policy::ProxyRequired).status, "not_observed");
        r.packet_state = "awaiting_authorization".into();
        assert_eq!(
            assess(&r, Policy::ProxyRequired).status,
            "awaiting_authorization"
        );
        r.packet_state = "cancelled".into();
        assert_eq!(assess(&r, Policy::ProxyRequired).status, "cancelled");
    }
    #[test]
    fn retained_evidence_is_bounded_and_loss_is_reported() {
        let mut r = Report::new(30);
        for _ in 0..ROW_LIMIT + 3 {
            r.add(Observation::default());
        }
        assert_eq!(r.observations.len(), ROW_LIMIT);
        assert_eq!(r.coverage.retained_rows_dropped, 3);
        assert_eq!(r.observations.front().unwrap().id, 4);
    }
}
