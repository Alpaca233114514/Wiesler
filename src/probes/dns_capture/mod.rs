//! Local DNS events and on-interface packet evidence; never contacts a test service.
#[cfg(windows)]
mod authorization;
#[cfg(windows)]
mod windows;
mod wire;

use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet, HashMap, VecDeque},
    net::IpAddr,
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
    pub packet_types: BTreeMap<u16, u64>,
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
    #[serde(default)]
    pub tunnel_queries: u64,
    #[serde(default)]
    pub loopback_queries: u64,
    #[serde(default)]
    pub domestic_direct_queries: u64,
    #[serde(default)]
    pub duplicate_packets: u64,
    #[serde(default)]
    pub oversized_warnings: u64,
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
        } else if domestic_physical_query(&row) {
            self.coverage.domestic_direct_queries += 1;
        } else if loopback_query(&row) {
            self.coverage.loopback_queries += 1;
        } else if tunnel_query(&row) {
            self.coverage.tunnel_queries += 1;
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

pub fn trigger_local_probe() -> String {
    // 仅调用系统 DNS 解析随机子域以穿透缓存，不建立任何网络/HTTP 连接。 / Trigger an OS DNS lookup only (no HTTP/TCP connection) to bypass cache.
    let domain = format!("wiesler-probe-{}.example.com", now());
    let target = domain.clone();
    std::thread::spawn(move || {
        use std::net::ToSocketAddrs;
        let _ = (target.as_str(), 443).to_socket_addrs();
    });
    domain
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
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ResolverSummary {
    pub resolver: String,
    pub interface_name: String,
    pub route_kind: String,
    pub queries: u64,
    pub domains: Vec<String>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct QuerySummary {
    pub domain: String,
    pub query_types: Vec<u16>,
    #[serde(default)]
    pub region: String,
    pub route_kind: String,
    pub resolver: Option<String>,
    pub interface_name: Option<String>,
    pub process: Option<String>,
    pub pid: Option<u32>,
    pub answers: Vec<String>,
    pub transport: String,
    pub count: u64,
    pub last_seen: u64,
    pub is_leak: bool,
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
    #[serde(default)]
    pub tunnel_queries: u64,
    #[serde(default)]
    pub domestic_queries: u64,
    #[serde(default)]
    pub unique_domains: u64,
    #[serde(default)]
    pub resolvers: Vec<ResolverSummary>,
    #[serde(default)]
    pub queries: Vec<QuerySummary>,
}

fn parsed_resolver_ip(r: &Observation) -> Option<IpAddr> {
    r.resolver.as_ref()?.trim().parse::<IpAddr>().ok()
}

fn is_external_resolver(ip: IpAddr) -> bool {
    !ip.is_loopback() && !ip.is_unspecified() && !ip.is_multicast()
}

fn is_proxy_tunnel_ip(ip: IpAddr) -> bool {
    // 198.18.0.0/15 为 Clash/Mihomo/sing-box 通用 TUN 虚拟 DNS 与 Fake-IP 网段。 / RFC 2544 198.18.0.0/15 is the standard TUN proxy DNS / Fake-IP range.
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            o[0] == 198 && (o[1] == 18 || o[1] == 19)
        }
        IpAddr::V6(v6) => {
            if let Some(mapped) = v6.to_ipv4_mapped() {
                let o = mapped.octets();
                return o[0] == 198 && (o[1] == 18 || o[1] == 19);
            }
            let o = v6.octets();
            // fc00::/7 中常见的 TUN 虚拟解析器地址（如 fdfe:dcba:9876::2）。 / ULA virtual DNS addresses used by TUN proxies.
            (o[0] == 0xfc || o[0] == 0xfd) && o[1] == 0xfe
        }
    }
}

fn domain_matches_suffix(norm: &str, suffix: &str) -> bool {
    norm == suffix
        || norm
            .strip_suffix(suffix)
            .is_some_and(|prefix| prefix.ends_with('.'))
}

pub(crate) fn classify_domain_region(domain: &str) -> &'static str {
    let norm = normalize_domain(domain);
    if norm.is_empty() || !norm.contains('.') {
        return "local";
    }
    const LOCAL_SUFFIXES: &[&str] = &[
        "local",
        "lan",
        "home.arpa",
        "arpa",
        "internal",
        "localhost",
        "localdomain",
        "intranet",
        "corp",
        "home",
    ];
    if LOCAL_SUFFIXES
        .iter()
        .any(|s| domain_matches_suffix(&norm, s))
    {
        return "local";
    }
    if domain_matches_suffix(&norm, "cn")
        || domain_matches_suffix(&norm, "xn--fiqs8s")
        || domain_matches_suffix(&norm, "xn--fiqz9s")
    {
        return "domestic";
    }
    // 高频中国大陆域名后缀，分流模式下直连解析不视作海外泄漏。 / Common mainland China domains expected to resolve directly under rule-based split routing.
    const DOMESTIC_SUFFIXES: &[&str] = &[
        "qq.com",
        "wechat.com",
        "weixin.com",
        "tencent.com",
        "myqcloud.com",
        "gtimg.com",
        "qpic.cn",
        "baidu.com",
        "bdstatic.com",
        "bcebos.com",
        "taobao.com",
        "tmall.com",
        "alibaba.com",
        "alicdn.com",
        "aliyun.com",
        "aliyuncs.com",
        "alipay.com",
        "alipayobjects.com",
        "amap.com",
        "dingtalk.com",
        "jd.com",
        "360buyimg.com",
        "bilibili.com",
        "bilivideo.com",
        "hdslb.com",
        "biliapi.net",
        "zhihu.com",
        "zhimg.com",
        "163.com",
        "126.com",
        "126.net",
        "netease.com",
        "ydstatic.com",
        "sina.com",
        "weibo.com",
        "weibocdn.com",
        "mi.com",
        "xiaomi.com",
        "miui.com",
        "huawei.com",
        "hicloud.com",
        "vmall.com",
        "bytedance.com",
        "byteimg.com",
        "bytetos.com",
        "douyin.com",
        "douyinpic.com",
        "toutiao.com",
        "snssdk.com",
        "pstatp.com",
        "meituan.com",
        "dianping.com",
        "pinduoduo.com",
        "yangkeduo.com",
        "kuaishou.com",
        "kwimgs.com",
        "douban.com",
        "csdn.net",
        "gitee.com",
        "huorong.cn",
        "kingsoft.com",
        "wps.com",
        "iqiyi.com",
        "youku.com",
        "sohu.com",
        "ctrip.com",
        "kugou.com",
        "xunlei.com",
        "360.com",
        "360safe.com",
        "lenovo.com",
        "oppo.com",
        "vivo.com",
        "xiaohongshu.com",
        "xhscdn.com",
    ];
    if DOMESTIC_SUFFIXES
        .iter()
        .any(|s| domain_matches_suffix(&norm, s))
    {
        return "domestic";
    }
    "overseas"
}

fn raw_physical_egress(r: &Observation) -> bool {
    // 物理网卡发往真实外部 DNS（排除回环、组播与 198.18.0.0/15 代理虚拟 DNS）的明文查询。 / Plaintext DNS leaving physical NIC to a real non-TUN resolver.
    r.source == "packet"
        && r.phase == "query"
        && r.direction.as_deref() == Some("tx")
        && r.interface.as_ref().and_then(|i| i.hardware) == Some(true)
        && r.domain.as_ref().is_some_and(|d| !d.is_empty())
        && parsed_resolver_ip(r)
            .is_some_and(|ip| is_external_resolver(ip) && !is_proxy_tunnel_ip(ip))
}

fn physical_query(r: &Observation) -> bool {
    // 仅海外域名从物理网卡明文直出才计为海外 DNS 泄漏证据。 / Only overseas domains leaving physical NIC in plaintext count as leak evidence.
    raw_physical_egress(r)
        && r.domain
            .as_deref()
            .is_some_and(|d| classify_domain_region(d) == "overseas")
}

fn domestic_physical_query(r: &Observation) -> bool {
    raw_physical_egress(r)
        && r.domain
            .as_deref()
            .is_some_and(|d| classify_domain_region(d) != "overseas")
}

fn loopback_query(r: &Observation) -> bool {
    r.source == "packet"
        && r.phase == "query"
        && r.direction.as_deref() == Some("tx")
        && r.domain.as_ref().is_some_and(|d| !d.is_empty())
        && parsed_resolver_ip(r).is_some_and(|ip| ip.is_loopback())
}

fn tunnel_query(r: &Observation) -> bool {
    r.source == "packet"
        && r.phase == "query"
        && r.direction.as_deref() == Some("tx")
        && r.domain.as_ref().is_some_and(|d| !d.is_empty())
        && parsed_resolver_ip(r).is_some_and(|ip| {
            is_proxy_tunnel_ip(ip)
                || (is_external_resolver(ip)
                    && r.interface.as_ref().and_then(|i| i.hardware) == Some(false))
        })
}

fn normalize_domain(domain: &str) -> String {
    domain.trim().trim_end_matches('.').to_ascii_lowercase()
}

fn route_priority(kind: &str) -> u8 {
    match kind {
        "physical" => 0,
        "domestic_direct" => 1,
        "encrypted" => 2,
        "tunnel" => 3,
        "loopback" => 4,
        "unknown" => 5,
        "system" => 6,
        _ => 7,
    }
}

type ProcessHint = (u64, Option<String>, Option<u32>);

fn summarize_report(
    report: &Report,
    policy: &Policy,
) -> (Vec<ResolverSummary>, Vec<QuerySummary>, u64) {
    let mut process_hints: HashMap<String, Vec<ProcessHint>> = HashMap::new();
    let mut answers_by_domain: HashMap<String, Vec<String>> = HashMap::new();
    let mut answers_by_id: HashMap<(String, u16), Vec<String>> = HashMap::new();
    let mut all_domains: BTreeSet<String> = BTreeSet::new();

    for o in &report.observations {
        let Some(raw_domain) = o.domain.as_deref().filter(|d| !d.trim().is_empty()) else {
            continue;
        };
        let norm = normalize_domain(raw_domain);
        if norm.is_empty() {
            continue;
        }
        all_domains.insert(norm.clone());
        if o.source == "windows-dns" && (o.process.is_some() || o.pid.is_some()) {
            process_hints.entry(norm.clone()).or_default().push((
                o.observed_at,
                o.process.clone(),
                o.pid,
            ));
        }
        if !o.answers.is_empty() {
            let entry = answers_by_domain.entry(norm.clone()).or_default();
            for ans in &o.answers {
                if !entry.contains(ans) && entry.len() < 8 {
                    entry.push(ans.clone());
                }
            }
            if let Some(id) = o.dns_id {
                let id_entry = answers_by_id.entry((norm, id)).or_default();
                for ans in &o.answers {
                    if !id_entry.contains(ans) && id_entry.len() < 8 {
                        id_entry.push(ans.clone());
                    }
                }
            }
        }
    }

    let find_process = |norm: &str, at: u64| -> (Option<String>, Option<u32>) {
        let Some(hints) = process_hints.get(norm) else {
            return (None, None);
        };
        // 在 ±3 秒窗口内匹配时间最接近的进程线索，优先带进程名的记录。 / Match closest ETW process within +/-3s window.
        hints
            .iter()
            .filter(|(t, _, _)| t.abs_diff(at) <= 3000)
            .min_by_key(|(t, proc_name, _)| (proc_name.is_none(), t.abs_diff(at)))
            .map(|(_, proc_name, pid)| (proc_name.clone(), *pid))
            .unwrap_or((None, None))
    };

    #[derive(Default)]
    struct ResolverAcc {
        queries: u64,
        domains: BTreeSet<String>,
    }
    let mut resolver_map: HashMap<(String, String, String), ResolverAcc> = HashMap::new();

    #[derive(Default)]
    struct QueryAcc {
        display_domain: String,
        query_types: BTreeSet<u16>,
        route_kind: String,
        resolver: Option<String>,
        interface_name: Option<String>,
        process: Option<String>,
        pid: Option<u32>,
        answers: Vec<String>,
        transport: String,
        region: String,
        count: u64,
        last_seen: u64,
        is_leak: bool,
    }
    let mut query_map: HashMap<(String, String, String), QueryAcc> = HashMap::new();
    let mut packet_domains: BTreeSet<String> = BTreeSet::new();

    for o in &report.observations {
        let is_packet_tx_query =
            o.source == "packet" && o.phase == "query" && o.direction.as_deref() == Some("tx");
        let is_encrypted_event =
            o.source == "windows-dns" && matches!(o.transport.as_str(), "DoH" | "DoT");
        let is_encrypted_pkt = o.source == "packet"
            && o.phase == "encrypted_candidate"
            && o.direction.as_deref() == Some("tx");

        if !is_packet_tx_query && !is_encrypted_event && !is_encrypted_pkt {
            continue;
        }
        let ip = parsed_resolver_ip(o);
        if ip.is_some_and(|addr| addr.is_multicast() || addr.is_unspecified()) {
            continue;
        }
        let raw_domain = o
            .domain
            .as_deref()
            .map(str::trim)
            .filter(|d| !d.is_empty())
            .unwrap_or(if is_encrypted_pkt {
                "DoT/DoQ 加密连接 (853)"
            } else {
                ""
            });
        let region = if is_encrypted_pkt {
            "encrypted"
        } else {
            classify_domain_region(raw_domain)
        };
        let is_proxy_ip = ip.is_some_and(is_proxy_tunnel_ip);
        let route_kind = if is_encrypted_event || is_encrypted_pkt {
            "encrypted"
        } else if ip.is_some_and(|addr| addr.is_loopback()) {
            "loopback"
        } else if is_proxy_ip {
            "tunnel"
        } else {
            match o.interface.as_ref().and_then(|i| i.hardware) {
                Some(true) => {
                    if region == "overseas" {
                        "physical"
                    } else {
                        "domestic_direct"
                    }
                }
                Some(false) => "tunnel",
                None => "unknown",
            }
        };
        let iface_name =
            if is_proxy_ip && o.interface.as_ref().and_then(|i| i.hardware) == Some(true) {
                "代理 TUN DNS".into()
            } else {
                o.interface
                    .as_ref()
                    .map(|i| i.name.clone())
                    .filter(|n| !n.is_empty())
                    .unwrap_or_else(|| {
                        if route_kind == "loopback" {
                            "Loopback".into()
                        } else if is_proxy_ip {
                            "代理 TUN DNS".into()
                        } else {
                            "未知接口".into()
                        }
                    })
            };
        let resolver_str = o
            .resolver
            .clone()
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "未知解析器".into());

        let r_entry = resolver_map
            .entry((route_kind.into(), resolver_str.clone(), iface_name.clone()))
            .or_default();
        r_entry.queries += 1;

        if raw_domain.is_empty() {
            continue;
        }
        let norm = normalize_domain(raw_domain);
        if !is_encrypted_pkt {
            r_entry
                .domains
                .insert(raw_domain.trim_end_matches('.').to_string());
            packet_domains.insert(norm.clone());
        }

        let q_key = (norm.clone(), route_kind.into(), resolver_str.clone());
        let q = query_map.entry(q_key).or_insert_with(|| QueryAcc {
            display_domain: raw_domain.trim_end_matches('.').to_string(),
            region: region.into(),
            route_kind: route_kind.into(),
            resolver: o.resolver.clone(),
            interface_name: Some(iface_name.clone()),
            transport: o.transport.clone(),
            ..Default::default()
        });
        q.count += 1;
        q.last_seen = q.last_seen.max(o.observed_at);
        if let Some(qt) = o.query_type {
            q.query_types.insert(qt);
        }
        if route_kind == "physical" && *policy == Policy::ProxyRequired {
            q.is_leak = true;
        }
        if q.process.is_none() {
            if o.process.is_some() || o.pid.is_some() {
                q.process = o.process.clone();
                q.pid = o.pid;
            } else {
                let (proc_name, pid) = find_process(&norm, o.observed_at);
                if proc_name.is_some() || pid.is_some() {
                    q.process = proc_name;
                    q.pid = pid;
                }
            }
        }
        let found_answers = o
            .dns_id
            .and_then(|id| answers_by_id.get(&(norm.clone(), id)))
            .or_else(|| answers_by_domain.get(&norm));
        if let Some(list) = found_answers {
            for ans in list {
                if !q.answers.contains(ans) && q.answers.len() < 6 {
                    q.answers.push(ans.clone());
                }
            }
        }
    }

    // 对于未出现在网卡报文中的系统 DNS 事件（例如命中本地缓存或经代理内部解析），补充到汇总列表。 / Add ETW-only queries (cache hits or local resolutions).
    for o in &report.observations {
        if o.source != "windows-dns" {
            continue;
        }
        if matches!(o.transport.as_str(), "DoH" | "DoT") {
            continue;
        }
        let Some(raw_domain) = o.domain.as_deref().map(str::trim).filter(|d| !d.is_empty()) else {
            continue;
        };
        let norm = normalize_domain(raw_domain);
        if packet_domains.contains(&norm) {
            continue;
        }
        let route_kind = if matches!(o.phase.as_str(), "cache_lookup" | "cache_result") {
            "cache"
        } else {
            "system"
        };
        let region = classify_domain_region(raw_domain);
        let resolver_key = o.resolver.clone().unwrap_or_default();
        let q_key = (norm.clone(), route_kind.into(), resolver_key);
        let q = query_map.entry(q_key).or_insert_with(|| QueryAcc {
            display_domain: raw_domain.trim_end_matches('.').to_string(),
            region: region.into(),
            route_kind: route_kind.into(),
            resolver: o.resolver.clone(),
            interface_name: o.interface.as_ref().map(|i| i.name.clone()),
            transport: o.transport.clone(),
            ..Default::default()
        });
        // 同一域名在单次解析内会产生多个阶段事件，仅在完成/结果/发送事件时累加次数（至少为 1）。 / Count completed/send stages to avoid inflating per-stage ETW counts.
        if matches!(
            o.phase.as_str(),
            "completed" | "send" | "cache_result" | "network_result"
        ) || q.count == 0
        {
            q.count += 1;
        }
        q.last_seen = q.last_seen.max(o.observed_at);
        if let Some(qt) = o.query_type {
            q.query_types.insert(qt);
        }
        if q.resolver.is_none() && o.resolver.is_some() {
            q.resolver = o.resolver.clone();
        }
        if q.interface_name.is_none() {
            q.interface_name = o.interface.as_ref().map(|i| i.name.clone());
        }
        if q.process.is_none() && (o.process.is_some() || o.pid.is_some()) {
            q.process = o.process.clone();
            q.pid = o.pid;
        }
        for ans in &o.answers {
            if !q.answers.contains(ans) && q.answers.len() < 6 {
                q.answers.push(ans.clone());
            }
        }
    }

    let mut resolvers: Vec<ResolverSummary> = resolver_map
        .into_iter()
        .map(
            |((route_kind, resolver, interface_name), acc)| ResolverSummary {
                resolver,
                interface_name,
                route_kind,
                queries: acc.queries,
                domains: acc.domains.into_iter().take(5).collect(),
            },
        )
        .collect();
    resolvers.sort_by(|a, b| {
        route_priority(&a.route_kind)
            .cmp(&route_priority(&b.route_kind))
            .then_with(|| b.queries.cmp(&a.queries))
            .then_with(|| a.resolver.cmp(&b.resolver))
    });

    let mut queries: Vec<QuerySummary> = query_map
        .into_values()
        .map(|q| QuerySummary {
            domain: q.display_domain,
            query_types: q.query_types.into_iter().collect(),
            region: q.region,
            route_kind: q.route_kind,
            resolver: q.resolver,
            interface_name: q.interface_name,
            process: q.process,
            pid: q.pid,
            answers: q.answers,
            transport: q.transport,
            count: q.count,
            last_seen: q.last_seen,
            is_leak: q.is_leak,
        })
        .collect();
    queries.sort_by(|a, b| {
        b.is_leak
            .cmp(&a.is_leak)
            .then_with(|| route_priority(&a.route_kind).cmp(&route_priority(&b.route_kind)))
            .then_with(|| b.last_seen.cmp(&a.last_seen))
            .then_with(|| a.domain.cmp(&b.domain))
    });
    queries.truncate(200);

    (resolvers, queries, all_domains.len() as u64)
}

fn is_encrypted_transport(transport: &str) -> bool {
    matches!(
        transport,
        "DoH" | "DoT" | "DoT (853)" | "DoQ (853)" | "TLS/853 candidate" | "QUIC/853 candidate"
    )
}

pub fn assess(report: &Report, policy: Policy) -> Assessment {
    let evidence: Vec<_> = report.leak_evidence.iter().map(|r| r.id).collect();
    let encrypted = report
        .observations
        .iter()
        .filter(|r| {
            is_encrypted_transport(&r.transport)
                && (r.source == "windows-dns" || r.direction.as_deref() == Some("tx"))
        })
        .count() as u64;
    let c = &report.coverage;
    let tunnel_total = c.tunnel_queries + c.loopback_queries;
    let (resolvers, queries, unique_domains) = summarize_report(report, &policy);
    let leak_domains: BTreeSet<String> = report
        .leak_evidence
        .iter()
        .filter_map(|r| r.domain.as_deref().map(normalize_domain))
        .filter(|d| !d.is_empty())
        .collect();

    // 仅采集源异常、队列溢出或大量丢包/丢事件才判定采集异常；偶发少量缓冲抖动不掩盖真实 DNS 结论。 / Only source failure, queue drops, or heavy loss mark capture incomplete.
    let critical_incomplete = report.packet_state != "active"
        || report.event_state != "active"
        || !report.errors.is_empty()
        || c.queue_drops > 0
        || c.lost_packets >= 50
        || c.lost_events >= 20;

    let minor_drop_note = if c.lost_packets > 0 || c.lost_events > 0 {
        let mut parts = Vec::new();
        if c.lost_packets > 0 {
            parts.push(format!("缓冲抖动 {} 条报文", c.lost_packets));
        }
        if c.lost_events > 0 {
            parts.push(format!("丢失 {} 条事件", c.lost_events));
        }
        format!("（期间{}）", parts.join("、"))
    } else {
        String::new()
    };

    let (status, title, detail): (&str, String, String) = if !evidence.is_empty()
        && policy == Policy::ProxyRequired
    {
        (
            "leak",
            "检测到海外 DNS 泄漏".into(),
            format!(
                "物理网卡直接发出 {} 条海外域名明文 DNS 查询（涉及 {} 个海外域名），绕过了代理隧道。",
                c.physical_queries,
                leak_domains.len().max(1)
            ),
        )
    } else if !evidence.is_empty() {
        (
            "observed",
            "观察到海外明文 DNS 物理直出".into(),
            format!(
                "物理网卡发出 {} 条海外域名明文 DNS 查询（涉及 {} 个域名）。",
                c.physical_queries,
                leak_domains.len().max(1)
            ),
        )
    } else if report.packet_state == "awaiting_authorization" {
        (
            "awaiting_authorization",
            "等待 Windows 授权".into(),
            "请在系统弹窗中确认授权，确认后自动开始采集。".into(),
        )
    } else if report.packet_state == "cancelled" {
        (
            "cancelled",
            "已取消检查".into(),
            "未取得采集权限，点击「开始本地检查」可重试。".into(),
        )
    } else if report.packet_state == "permission_required"
        || report.event_state == "permission_required"
    {
        (
            "unavailable",
            "缺少采集权限".into(),
            "Windows 拒绝了本地网卡抓包或 DNS 事件订阅。".into(),
        )
    } else if critical_incomplete {
        let mut reasons = Vec::new();
        if report.packet_state != "active" {
            reasons.push(format!("网卡抓包状态：{}", report.packet_state));
        }
        if report.event_state != "active" {
            reasons.push(format!("系统事件状态：{}", report.event_state));
        }
        if c.lost_packets > 0 {
            reasons.push(format!("丢失 {} 条报文", c.lost_packets));
        }
        if c.lost_events > 0 {
            reasons.push(format!("丢失 {} 条事件", c.lost_events));
        }
        if c.queue_drops > 0 {
            reasons.push(format!("队列溢出 {} 条", c.queue_drops));
        }
        if reasons.is_empty() && !report.errors.is_empty() {
            reasons.push(report.errors[0].clone());
        }
        ("incomplete", "采集存在异常".into(), reasons.join("；"))
    } else if c.dns_messages == 0 && encrypted == 0 && c.windows_events == 0 {
        (
            "no_sample",
            "暂无 DNS 查询样本".into(),
            format!(
                "可点击「测试解析」或正常打开网页触发 DNS 查询。{}",
                minor_drop_note
            ),
        )
    } else {
        let title = if policy == Policy::ProxyRequired {
            "未发现海外 DNS 泄漏"
        } else {
            "海外流量无物理明文直出"
        };
        let mut summary_parts = Vec::new();
        if tunnel_total > 0 {
            summary_parts.push(format!("{} 条隧道/代理查询", tunnel_total));
        }
        if c.domestic_direct_queries > 0 {
            summary_parts.push(format!("{} 条国内直连", c.domestic_direct_queries));
        }
        if encrypted > 0 {
            summary_parts.push(format!("{} 条加密 DNS", encrypted));
        }
        if summary_parts.is_empty() {
            summary_parts.push(format!("{} 条系统 DNS 事件", c.windows_events));
        }
        let detail = format!(
            "海外域名无物理网卡明文直出；本轮捕获 {}。{}",
            summary_parts.join("、"),
            minor_drop_note
        );
        ("not_observed", title.into(), detail)
    };

    Assessment {
        status: status.into(),
        title,
        detail,
        physical_queries: report.coverage.physical_queries,
        evidence_ids: evidence,
        encrypted_events: encrypted,
        tunnel_queries: tunnel_total,
        domestic_queries: report.coverage.domestic_direct_queries,
        unique_domains,
        resolvers,
        queries,
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
        r.coverage.lost_events = 25;
        assert_eq!(assess(&r, Policy::ProxyRequired).status, "incomplete");
        r.coverage.lost_events = 0;
        // 偶发极少量内核缓冲抖动与无关底噪帧不应触发不完整误报。 / Minor buffer jitter and non-DNS noise should not cause false incomplete.
        r.coverage.lost_packets = 3;
        r.coverage.retained_rows_dropped = 5;
        r.coverage.unsupported_packets = 12;
        r.coverage.oversized_warnings = 3;
        assert_eq!(assess(&r, Policy::ProxyRequired).status, "not_observed");
        r.coverage.lost_packets = 60;
        assert_eq!(assess(&r, Policy::ProxyRequired).status, "incomplete");
        r.coverage.lost_packets = 0;
        r.packet_state = "awaiting_authorization".into();
        assert_eq!(
            assess(&r, Policy::ProxyRequired).status,
            "awaiting_authorization"
        );
        r.packet_state = "cancelled".into();
        assert_eq!(assess(&r, Policy::ProxyRequired).status, "cancelled");
    }
    #[test]
    fn summarizes_resolvers_tunnel_queries_and_correlates_process_and_answers() {
        let mut r = Report::new(30);
        r.packet_state = "active".into();
        r.event_state = "active".into();
        r.coverage.windows_events = 1;
        r.coverage.dns_messages = 2;
        r.add(Observation {
            observed_at: 1000,
            source: "windows-dns".into(),
            phase: "started".into(),
            domain: Some("api.github.com".into()),
            query_type: Some(1),
            pid: Some(4321),
            process: Some("chrome.exe".into()),
            transport: "Windows DNS".into(),
            ..Default::default()
        });
        r.add(Observation {
            observed_at: 1050,
            source: "packet".into(),
            phase: "query".into(),
            domain: Some("api.github.com".into()),
            query_type: Some(1),
            dns_id: Some(99),
            resolver: Some("198.18.0.2".into()),
            transport: "UDP".into(),
            direction: Some("tx".into()),
            interface: Some(Interface {
                name: "Meta".into(),
                hardware: Some(false),
                index: Some(3),
                component_id: 1,
            }),
            ..Default::default()
        });
        r.add(Observation {
            observed_at: 1080,
            source: "packet".into(),
            phase: "response".into(),
            domain: Some("api.github.com".into()),
            query_type: Some(1),
            dns_id: Some(99),
            answers: vec!["198.18.0.15".into()],
            resolver: Some("198.18.0.2".into()),
            transport: "UDP".into(),
            direction: Some("rx".into()),
            interface: Some(Interface {
                name: "Meta".into(),
                hardware: Some(false),
                index: Some(3),
                component_id: 1,
            }),
            ..Default::default()
        });
        let a = assess(&r, Policy::ProxyRequired);
        assert_eq!(a.status, "not_observed");
        assert_eq!(a.title, "未发现海外 DNS 泄漏");
        assert_eq!(a.tunnel_queries, 1);
        assert_eq!(a.unique_domains, 1);
        assert_eq!(a.resolvers.len(), 1);
        assert_eq!(a.resolvers[0].resolver, "198.18.0.2");
        assert_eq!(a.resolvers[0].route_kind, "tunnel");
        assert_eq!(a.queries.len(), 1);
        assert_eq!(a.queries[0].region, "overseas");
        assert_eq!(a.queries[0].process.as_deref(), Some("chrome.exe"));
        assert_eq!(a.queries[0].answers, vec!["198.18.0.15"]);

        // 国内域名直连与发往 198.18.0.2（即使挂在 WLAN 上）的查询绝不应误报海外 DNS 泄漏。 / Domestic direct and 198.18.0.2 proxy DNS must not trigger overseas leak.
        let mut split = Report::new(30);
        split.packet_state = "active".into();
        split.event_state = "active".into();
        split.coverage.dns_messages = 2;
        split.add(Observation {
            observed_at: 2000,
            source: "packet".into(),
            phase: "query".into(),
            domain: Some("api.bugreport.huorong.cn".into()),
            query_type: Some(1),
            resolver: Some("198.18.0.2".into()),
            transport: "UDP".into(),
            direction: Some("tx".into()),
            interface: Some(Interface {
                name: "WLAN".into(),
                hardware: Some(true),
                index: Some(21),
                component_id: 2,
            }),
            ..Default::default()
        });
        split.add(Observation {
            observed_at: 2010,
            source: "packet".into(),
            phase: "query".into(),
            domain: Some("www.baidu.com".into()),
            query_type: Some(1),
            resolver: Some("223.5.5.5".into()),
            transport: "UDP".into(),
            direction: Some("tx".into()),
            interface: Some(Interface {
                name: "WLAN".into(),
                hardware: Some(true),
                index: Some(21),
                component_id: 2,
            }),
            ..Default::default()
        });
        let sa = assess(&split, Policy::ProxyRequired);
        assert_eq!(sa.status, "not_observed");
        assert_eq!(sa.physical_queries, 0);
        assert_eq!(sa.tunnel_queries, 1);
        assert_eq!(sa.domestic_queries, 1);
        assert!(sa.queries.iter().all(|q| !q.is_leak));

        // 853 加密 DNS 报文也应计入 encrypted_events，避免指标卡为 0 与芯片不一致。 / Count 853 encrypted packets in encrypted_events.
        let mut enc = Report::new(30);
        enc.packet_state = "active".into();
        enc.event_state = "active".into();
        enc.add(Observation {
            observed_at: 2000,
            source: "packet".into(),
            phase: "encrypted_candidate".into(),
            resolver: Some("223.5.5.5".into()),
            transport: "DoT (853)".into(),
            direction: Some("tx".into()),
            interface: Some(Interface {
                name: "WLAN".into(),
                hardware: Some(true),
                index: Some(21),
                component_id: 2,
            }),
            ..Default::default()
        });
        let ea = assess(&enc, Policy::ProxyRequired);
        assert_eq!(ea.status, "not_observed");
        assert_eq!(ea.encrypted_events, 1);
        assert_eq!(ea.resolvers.len(), 1);
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
