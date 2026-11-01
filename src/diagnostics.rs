use crate::{
    application::ApplicationObservation,
    core::event::{Event, EventData},
    sockets::SocketObservation,
    store,
};
use anyhow::Result;
use serde::Serialize;
use std::{
    collections::{BTreeMap, BTreeSet},
    path::Path,
};

#[derive(Default, Serialize)]
pub struct Latency {
    pub samples: usize,
    pub median_ms: Option<f64>,
    pub p95_ms: Option<f64>,
    pub evidence_ids: Vec<String>,
}

impl Latency {
    fn from(mut values: Vec<f64>) -> Self {
        values.sort_by(f64::total_cmp);
        let n = values.len();
        if n == 0 {
            return Self::default();
        }
        Self {
            samples: n,
            median_ms: Some(if n.is_multiple_of(2) {
                (values[n / 2 - 1] + values[n / 2]) / 2.0
            } else {
                values[n / 2]
            }),
            p95_ms: Some(values[(n * 95).div_ceil(100) - 1]),
            evidence_ids: vec![],
        }
    }
}

#[derive(Serialize)]
pub struct DshGroup {
    pub provider: Option<String>,
    pub model: Option<String>,
    pub purpose: Option<String>,
    pub reasoning_effort: Option<String>,
    pub completed_attempts: usize,
    pub successful_tps_samples: usize,
    pub failed_or_incomplete_attempts: usize,
    pub weighted_decode_tps: Option<f64>,
    pub median_decode_tps: Option<f64>,
    pub ttft: Latency,
    pub evidence_ids: Vec<String>,
}

#[derive(Serialize)]
pub struct EvidenceObservation {
    pub event_id: String,
    pub observation: ApplicationObservation,
}

#[derive(Serialize)]
pub struct ProxyConnection {
    pub evidence_id: String,
    pub process: Option<String>,
    pub source_port: Option<u16>,
    pub destination_host: Option<String>,
    pub destination_ip: Option<String>,
    pub destination_port: Option<u16>,
    pub rule: Option<String>,
    pub chain_as_reported: Vec<String>,
}

#[derive(Serialize)]
pub struct DiagnosticReport {
    pub sessions: Vec<store::Session>,
    pub dsh: Vec<DshGroup>,
    pub dsh_recent: Vec<EvidenceObservation>,
    pub dsh_unfinished_calls: usize,
    pub codex_latency_by_scope: BTreeMap<String, Latency>,
    pub codex_recent: Vec<EvidenceObservation>,
    pub codex_recent_errors: Vec<EvidenceObservation>,
    pub codex_error_counts: BTreeMap<String, usize>,
    pub source_gaps: Vec<String>,
    pub source_gap_details: BTreeMap<String, String>,
    pub controller_failure_evidence: Vec<String>,
    pub sockets_evidence_id: Option<String>,
    pub sockets_observed_at_utc: Option<u64>,
    pub codex_loopback_sockets: Vec<SocketObservation>,
    pub codex_proxy_routes: Vec<LocalProxyRoute>,
    pub codex_other_sockets: Vec<SocketObservation>,
    pub proxy_egress_candidates: Vec<SocketObservation>,
    pub mihomo_codex_connections: Vec<ProxyConnection>,
    pub public_exit_ip: Option<String>,
    pub limitations: Vec<String>,
}

#[derive(Serialize)]
pub struct LocalProxyRoute {
    pub codex_pid: u32,
    pub codex_source_port: u16,
    pub proxy_pid: u32,
    pub proxy_process: String,
    pub proxy_port: u16,
    pub evidence_id: String,
}

#[derive(Default)]
struct Builder {
    dsh: BTreeMap<String, (String, ApplicationObservation)>,
    starts: BTreeSet<String>,
    codex: BTreeMap<String, (String, ApplicationObservation)>,
    gaps: Vec<String>,
    gap_details: BTreeMap<String, String>,
    controller_failures: Vec<String>,
    sockets: Option<(String, u64, Vec<SocketObservation>)>,
    proxy: BTreeMap<String, ProxyConnection>,
}

impl Builder {
    fn event(&mut self, event: Event) {
        match event.data {
            EventData::ApplicationObserved { observation } => {
                let observation = *observation;
                if observation.application == "dsh" {
                    let key = format!(
                        "{}:{}",
                        observation.file,
                        observation.request_id.as_deref().unwrap_or_default()
                    );
                    if observation.category == "call-start" {
                        self.starts.insert(key);
                    } else if observation.category == "call-end" {
                        self.dsh.insert(key, (event.event_id, observation));
                    } else if observation.category == "data-loss" {
                        self.gaps.push(event.event_id);
                    }
                } else if observation.application == "codex" {
                    let key = format!(
                        "{}:{}:{:?}:{}:{:?}",
                        observation.file,
                        observation.byte_offset,
                        observation.source_time,
                        observation.category,
                        observation.request_id
                    );
                    self.codex.insert(key, (event.event_id, observation));
                }
            }
            EventData::SocketsObserved {
                sockets,
                sample_time_utc,
            } => {
                let sampled = sample_time_utc.unwrap_or(event.observed_at_utc);
                if self
                    .sockets
                    .as_ref()
                    .is_none_or(|(_, time, _)| sampled >= *time)
                {
                    self.sockets = Some((event.event_id, sampled, sockets));
                }
            }
            EventData::SourceGap { reason, .. } => {
                self.gap_details.insert(
                    event.event_id.clone(),
                    format!("{}: {}", event.source_id, reason),
                );
                self.gaps.push(event.event_id)
            }
            EventData::ObservationGap { .. } => {
                self.gaps.push(event.event_id);
            }
            EventData::ControllerUnavailable { .. } => {
                self.controller_failures.push(event.event_id)
            }
            EventData::ConnectionObserved { connection }
            | EventData::ConnectionUpdated { connection, .. }
                if connection.metadata.process.as_ref().is_some_and(|p| {
                    p.to_ascii_lowercase().trim_end_matches(".exe") == "codex"
                }) =>
            {
                self.proxy.insert(
                    event
                        .connection_key
                        .unwrap_or_else(|| event.event_id.clone()),
                    ProxyConnection {
                        evidence_id: event.event_id,
                        process: connection.metadata.process,
                        source_port: connection.metadata.source_port,
                        destination_host: connection.metadata.host,
                        destination_ip: connection.metadata.destination_ip,
                        destination_port: connection.metadata.destination_port,
                        rule: connection.rule,
                        chain_as_reported: connection.chains,
                    },
                );
            }
            _ => {}
        }
    }
}

fn external_address(ip: &str) -> bool {
    ip.parse::<std::net::IpAddr>()
        .is_ok_and(|ip| !ip.is_loopback() && !ip.is_unspecified())
}

pub fn report(path: &Path, selected_sessions: &[String]) -> Result<DiagnosticReport> {
    let sessions = store::sessions(path)?
        .into_iter()
        .filter(|s| selected_sessions.is_empty() || selected_sessions.contains(&s.session_id))
        .collect::<Vec<_>>();
    if !selected_sessions.is_empty()
        && selected_sessions
            .iter()
            .any(|id| !sessions.iter().any(|s| &s.session_id == id))
    {
        anyhow::bail!("未找到指定会话");
    }
    let mut builder = Builder::default();
    for session in &sessions {
        store::visit_events(path, &session.session_id, |event| {
            builder.event(event);
            Ok(())
        })?;
    }
    let unfinished = builder
        .starts
        .iter()
        .filter(|id| !builder.dsh.contains_key(*id))
        .count();
    let mut groups: BTreeMap<_, Vec<_>> = BTreeMap::new();
    for (id, row) in builder.dsh.values() {
        groups
            .entry((
                row.provider.clone(),
                row.model.clone(),
                row.purpose.clone(),
                row.reasoning_effort.clone(),
            ))
            .or_default()
            .push((id, row));
    }
    let dsh = groups
        .into_iter()
        .map(|((provider, model, purpose, reasoning_effort), rows)| {
            let valid: Vec<_> = rows
                .iter()
                .filter(|(_, r)| r.decode_tps.is_some())
                .collect();
            let tokens: f64 = valid
                .iter()
                .map(|(_, r)| r.output_tokens.unwrap_or(0) as f64)
                .sum();
            let ms: f64 = valid.iter().map(|(_, r)| r.decode_ms.unwrap_or(0.0)).sum();
            let speeds = Latency::from(valid.iter().filter_map(|(_, r)| r.decode_tps).collect());
            DshGroup {
                provider,
                model,
                purpose,
                reasoning_effort,
                completed_attempts: rows.len(),
                successful_tps_samples: valid.len(),
                failed_or_incomplete_attempts: rows
                    .iter()
                    .filter(|(_, r)| {
                        !matches!(
                            r.status.as_deref(),
                            Some("stop" | "tool-calls" | "max-tokens")
                        )
                    })
                    .count(),
                weighted_decode_tps: if ms > 0.0 {
                    Some(tokens * 1000.0 / ms)
                } else {
                    None
                },
                median_decode_tps: speeds.median_ms,
                ttft: Latency::from(rows.iter().filter_map(|(_, r)| r.ttft_ms).collect()),
                evidence_ids: rows.iter().map(|(id, _)| (*id).clone()).collect(),
            }
        })
        .collect();
    let mut latency: BTreeMap<String, Vec<(String, f64)>> = BTreeMap::new();
    let mut dsh_recent: Vec<_> = builder
        .dsh
        .values()
        .map(|(id, row)| EvidenceObservation {
            event_id: id.clone(),
            observation: row.clone(),
        })
        .collect();
    dsh_recent.sort_by_key(|item| {
        std::cmp::Reverse(
            item.observation
                .source_time
                .as_deref()
                .and_then(|v| v.parse::<u64>().ok()),
        )
    });
    dsh_recent.truncate(100);
    let mut recent = vec![];
    let mut recent_errors = vec![];
    let mut error_counts = BTreeMap::new();
    for (id, row) in builder.codex.into_values() {
        if let Some(ms) = row.elapsed_ms {
            latency
                .entry(format!("{} / {}", row.scope, row.category))
                .or_default()
                .push((id.clone(), ms));
        }
        if row.status.as_deref() == Some("failed")
            || row.http_status.is_some_and(|code| code >= 400)
            || row.failure_code.is_some()
            || row.category.contains("closed")
        {
            *error_counts.entry(row.category.clone()).or_default() += 1;
            recent_errors.push(EvidenceObservation {
                event_id: id.clone(),
                observation: row.clone(),
            });
        }
        recent.push(EvidenceObservation {
            event_id: id,
            observation: row,
        });
    }
    recent.sort_by(|a, b| b.observation.source_time.cmp(&a.observation.source_time));
    recent.truncate(100);
    recent_errors.sort_by(|a, b| b.observation.source_time.cmp(&a.observation.source_time));
    recent_errors.truncate(100);
    let mut codex_loopback_sockets = vec![];
    let mut codex_proxy_routes = vec![];
    let mut codex_other_sockets = vec![];
    let mut proxy_egress_candidates = vec![];
    let (sockets_evidence_id, sockets_observed_at_utc) =
        if let Some((id, time, sockets)) = builder.sockets {
            for socket in &sockets {
                if socket.state != "Established" {
                    continue;
                }
                if socket.role == "codex" {
                    if socket
                        .remote_ip
                        .parse::<std::net::IpAddr>()
                        .is_ok_and(|ip| ip.is_loopback())
                    {
                        codex_loopback_sockets.push(socket.clone());
                        for proxy in sockets.iter().filter(|proxy| {
                            proxy.role == "proxy_process"
                                && proxy.state == "Established"
                                && proxy.local_ip == socket.remote_ip
                                && proxy.local_port == socket.remote_port
                                && proxy.remote_ip == socket.local_ip
                                && proxy.remote_port == socket.local_port
                        }) {
                            codex_proxy_routes.push(LocalProxyRoute {
                                codex_pid: socket.pid,
                                codex_source_port: socket.local_port,
                                proxy_pid: proxy.pid,
                                proxy_process: proxy.process.clone(),
                                proxy_port: proxy.local_port,
                                evidence_id: id.clone(),
                            });
                        }
                    } else {
                        codex_other_sockets.push(socket.clone());
                    }
                } else if external_address(&socket.remote_ip) {
                    proxy_egress_candidates.push(socket.clone());
                }
            }
            (Some(id), Some(time))
        } else {
            (None, None)
        };
    Ok(DiagnosticReport { sessions, dsh, dsh_recent, dsh_unfinished_calls: unfinished,
        codex_latency_by_scope: latency.into_iter().map(|(key, values)| {
            let (ids, numbers): (Vec<_>, Vec<_>) = values.into_iter().unzip();
            let mut latency = Latency::from(numbers); latency.evidence_ids = ids; (key, latency)
        }).collect(), codex_recent: recent,
        codex_recent_errors: recent_errors, codex_error_counts: error_counts,
        source_gaps: builder.gaps, source_gap_details: builder.gap_details, controller_failure_evidence: builder.controller_failures,
        sockets_evidence_id, sockets_observed_at_utc, codex_loopback_sockets, codex_proxy_routes, codex_other_sockets, proxy_egress_candidates,
        mihomo_codex_connections: builder.proxy.into_values().collect(), public_exit_ip: None,
        limitations: vec![
            "DSH TPS 来自成功 attempt 的 usage / decode 时长；reasoningTokens 已含在 outputTokens 中；chunk 不等于 token。".into(),
            "Codex 桌面 HTTP、内部 RPC 与模型请求是不同口径；现有桌面日志未提供模型 TTFT 时保持未知。".into(),
            "Codex 回环套接字是本机端口证据；是否为代理要与代理监听/连接证据核对。代理进程外连仅为出口候选，不证明某条模型请求使用该连接。".into(),
            "Mihomo 链按来源原样显示；未查询公网出口 IP。历史日志、当前 TCP 采样和不同采集会话不按时间接近强行关联。".into(),
        ] })
}

impl DiagnosticReport {
    pub fn markdown(&self) -> String {
        let mut out = String::from("# Wiesler 诊断报告\n\n");
        out.push_str(&format!(
            "会话 {} 个；采集缺口 {} 条；控制器异常 {} 条。\n\n",
            self.sessions.len(),
            self.source_gaps.len(),
            self.controller_failure_evidence.len()
        ));
        out.push_str("## DSH token 速度\n\n| Provider / Model / Purpose | 完成 attempt | 有效 TPS 样本 | 加权 TPS | 中位 TPS | TTFT 中位 ms |\n| --- | ---: | ---: | ---: | ---: | ---: |\n");
        for group in &self.dsh {
            out.push_str(&format!(
                "| {} / {} / {} | {} | {} | {} | {} | {} |\n",
                safe(group.provider.as_deref()),
                safe(group.model.as_deref()),
                safe(group.purpose.as_deref()),
                group.completed_attempts,
                group.successful_tps_samples,
                number(group.weighted_decode_tps),
                number(group.median_decode_tps),
                number(group.ttft.median_ms)
            ));
        }
        out.push_str(&format!("\n仅有开始记录的请求：{}。\n\n## Codex 响应耗时\n\n| 测量范围 | 样本 | 中位 ms | P95 ms |\n| --- | ---: | ---: | ---: |\n", self.dsh_unfinished_calls));
        for (scope, value) in &self.codex_latency_by_scope {
            out.push_str(&format!(
                "| {} | {} | {} | {} |\n",
                safe(Some(scope)),
                value.samples,
                number(value.median_ms),
                number(value.p95_ms)
            ));
        }
        for (name, rows) in [("DSH", &self.dsh_recent), ("Codex", &self.codex_recent)] {
            out.push_str(&format!("\n## {name} 最近日志状态（最多 100 条）\n\n"));
            if rows.is_empty() {
                out.push_str("暂无日志记录。\n");
                continue;
            }
            out.push_str("| 来源时间 | 事件 / 范围 | HTTP 状态码 | 结果 | 错误码 | 来源 | 证据 |\n| --- | --- | --- | --- | --- | --- | --- |\n");
            for item in rows {
                let row = &item.observation;
                let result = if row.failure_code.is_some()
                    || matches!(row.status.as_deref(), Some("failed" | "error" | "aborted"))
                    || row.http_status.is_some_and(|v| v >= 400)
                {
                    "失败"
                } else {
                    match row.http_status {
                        Some(200..=299) => "成功",
                        Some(300..=399) => "重定向",
                        Some(100..=199) => "信息响应",
                        _ => "未知",
                    }
                };
                out.push_str(&format!(
                    "| {} | {} / {} | {} | {} | {} | {}:{} | {} |\n",
                    safe(row.source_time.as_deref()),
                    safe(Some(&row.category)),
                    safe(Some(&row.scope)),
                    row.http_status
                        .map(|v| format!("HTTP {v}"))
                        .unwrap_or_else(|| "未记录".into()),
                    result,
                    safe(row.failure_code.as_deref()),
                    safe(Some(&row.file)),
                    row.line,
                    safe(Some(&item.event_id))
                ));
            }
        }
        out.push_str("\n## Codex 端口与出口\n\n");
        out.push_str(&format!(
            "TCP 采样时间（UTC Unix ms）：{}；证据：{}。\n\n",
            self.sockets_observed_at_utc
                .map(|v| v.to_string())
                .unwrap_or_else(|| "未知".into()),
            self.sockets_evidence_id.as_deref().unwrap_or("无")
        ));
        for route in &self.codex_proxy_routes {
            out.push_str(&format!(
                "- 双向 TCP 四元组匹配：Codex PID {} 源端口 {} → {} PID {} 代理端口 {}。\n",
                route.codex_pid,
                route.codex_source_port,
                safe(Some(&route.proxy_process)),
                route.proxy_pid,
                route.proxy_port
            ));
        }
        for socket in &self.codex_loopback_sockets {
            out.push_str(&format!(
                "- Codex PID {}：{}:{} → {}:{}（回环连接）。\n",
                socket.pid,
                socket.local_ip,
                socket.local_port,
                socket.remote_ip,
                socket.remote_port
            ));
        }
        let mut egress_groups = BTreeMap::new();
        for socket in &self.proxy_egress_candidates {
            *egress_groups
                .entry((
                    &socket.process,
                    socket.pid,
                    &socket.remote_ip,
                    socket.remote_port,
                ))
                .or_insert(0) += 1;
        }
        for ((process, pid, ip, port), count) in egress_groups {
            out.push_str(&format!(
                "- 代理进程 {} PID {}：{}:{}（外连候选，{} 条 TCP 连接）。\n",
                safe(Some(process)),
                pid,
                ip,
                port,
                count
            ));
        }
        if !self.codex_other_sockets.is_empty() {
            out.push_str(&format!("\n另有 {} 条 Codex 非回环 TCP 连接，详见 JSON 的 codex_other_sockets；未强行关联至上述代理。\n", self.codex_other_sockets.len()));
        }
        if self.mihomo_codex_connections.is_empty() {
            out.push_str("\n未取得 Mihomo 对 Codex 的连接归属和代理链证据。\n");
        }
        for connection in &self.mihomo_codex_connections {
            out.push_str(&format!(
                "\n- Mihomo：{}，链 {}；证据 {}。\n",
                safe(
                    connection
                        .destination_host
                        .as_deref()
                        .or(connection.destination_ip.as_deref())
                ),
                safe(Some(&connection.chain_as_reported.join(" → "))),
                connection.evidence_id
            ));
        }
        out.push_str("\n实际公网出口 IP：未知。\n\n## Codex 最近错误与传输事件\n\n");
        for item in &self.codex_recent_errors {
            out.push_str(&format!(
                "- {}：{} / {}，HTTP {}，错误码 {}；证据 {}。\n",
                safe(item.observation.source_time.as_deref()),
                safe(Some(&item.observation.category)),
                safe(Some(&item.observation.scope)),
                item.observation
                    .http_status
                    .map(|s| s.to_string())
                    .unwrap_or_else(|| "未知".into()),
                safe(item.observation.failure_code.as_deref()),
                item.event_id
            ));
        }
        out.push_str("\n## 口径与缺失证据\n\n");
        for (id, reason) in &self.source_gap_details {
            out.push_str(&format!(
                "- 采集缺口 {}；证据 {}。\n",
                safe(Some(reason)),
                id
            ));
        }
        for limitation in &self.limitations {
            out.push_str(&format!("- {limitation}\n"));
        }
        out
    }
}

fn number(value: Option<f64>) -> String {
    value
        .map(|v| format!("{v:.2}"))
        .unwrap_or_else(|| "未知".into())
}
fn safe(value: Option<&str>) -> String {
    value
        .unwrap_or("未知")
        .replace('|', "\\|")
        .replace(['\r', '\n', '`', '<', '>'], " ")
}
