use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, HashSet, VecDeque},
    io::Read,
    path::PathBuf,
    process::{Command, Stdio},
    sync::Mutex,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const MAX_CMD_STDOUT_BYTES: usize = 4 * 1024 * 1024;
const MAX_CMD_STDERR_BYTES: usize = 256 * 1024;

const WINDOW_COINCIDENCE_MS: u64 = 30_000;
const WINDOW_PINGPONG_MS: u64 = 60_000;
const SCISSORS_RSRP_MIN_DBM: i32 = -95;
const SCISSORS_SINR_MAX_DB: i32 = 0;
const SCISSORS_RSRQ_MAX_DB: i32 = -15;
const SYNC_FLOOR_RSRP_DBM: i32 = -140;
const SYNC_FLOOR_RSRQ_DB: i32 = -30;
const PINGPONG_MIN_TRANSITIONS: usize = 4;
const STALE_FREEZE_THRESHOLD: u32 = 3;
const MAX_SAMPLES_PER_SLOT: usize = 120;
const MAX_EVENTS: usize = 60;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct WifiHealth {
    pub available: bool,
    pub ssid: Option<String>,
    pub rssi_dbm: Option<i32>,
    pub signal_percent: Option<u32>,
    pub channel: Option<u32>,
    pub rx_mbps: Option<f64>,
    pub tx_mbps: Option<f64>,
    pub bottleneck: bool,
    pub summary: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProtocolFault {
    pub observed_at_ms: u64,
    pub kind: String,
    pub detail: String,
    pub signature: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CellularSlotSample {
    pub observed_at_ms: u64,
    pub phone_id: u32,
    pub operator: String,
    pub rat: String,
    pub arfcn: Option<i32>,
    pub carrier_aggregation: bool,
    pub data_state: Option<i32>,
    pub rsrp_dbm: Option<i32>,
    pub rsrq_db: Option<i32>,
    pub sinr_db: Option<i32>,
    pub cell_ts_ns: Option<String>,
    pub in_service: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RfEvent {
    pub observed_at_ms: u64,
    pub slot: Option<u32>,
    pub code: String,
    pub title: String,
    pub detail: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RfSlotSummary {
    pub phone_id: u32,
    pub operator: String,
    pub rat: String,
    pub band: String,
    pub arfcn: Option<i32>,
    pub carrier_aggregation: bool,
    pub in_service: bool,
    pub rsrp_dbm: Option<i32>,
    pub rsrq_db: Option<i32>,
    pub sinr_db: Option<i32>,
    pub fresh_frames: u32,
    pub cached_duplicates: u32,
    pub cache_frozen: bool,
    pub scissors_30s: u32,
    pub sync_loss_30s: u32,
    pub pingpong_60s: u32,
    pub rsrp_sinr_corr: Option<f64>,
    pub active_flags: Vec<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct RfLinkContext {
    pub icmp_sample_count: u32,
    pub icmp_loss_percent: Option<f64>,
    pub icmp_avg_rtt_ms: Option<f64>,
    pub controller_connected: bool,
    pub controller_healthy: bool,
    pub last_controller_error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RfAttribution {
    pub cause: String,
    pub confidence: String,
    pub title: String,
    pub detail: String,
    pub suggestion: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RfSnapshotReport {
    pub observed_at_ms: u64,
    pub wifi: WifiHealth,
    pub adb_connected: bool,
    pub adb_device: Option<String>,
    pub adb_note: String,
    pub fresh_frames: u32,
    pub cached_duplicates: u32,
    pub frozen_slots: u32,
    pub score: u32,
    pub severity: String,
    pub severity_label: String,
    pub assessment_title: String,
    pub assessment_detail: String,
    pub dual_sim_coincidence: bool,
    pub scissors_alerts: u32,
    pub sync_loss_alerts: u32,
    pub pingpong_alerts: u32,
    pub protocol_faults_count: u32,
    pub slots: Vec<RfSlotSummary>,
    pub recent_events: Vec<RfEvent>,
    pub attribution: RfAttribution,
}

#[derive(Debug, Default)]
struct SlotTracker {
    operator: String,
    latest: Option<CellularSlotSample>,
    fresh_frames: u32,
    cached_duplicates: u32,
    consecutive_stale: u32,
    last_cell_key: Option<(String, String, String)>,
    consecutive_scissors: u32,
    last_channel_key: Option<String>,
    samples: VecDeque<CellularSlotSample>,
    transitions: VecDeque<u64>,
    last_scissors_alert_ms: Option<u64>,
    last_sync_loss_alert_ms: Option<u64>,
    last_pingpong_alert_ms: Option<u64>,
    freeze_emitted: bool,
}

#[derive(Debug, Default)]
pub struct RfMonitorState {
    slots: BTreeMap<u32, SlotTracker>,
    events: VecDeque<RfEvent>,
    seen_protocol_faults: HashSet<String>,
    protocol_faults: VecDeque<ProtocolFault>,
    total_fresh_frames: u32,
    total_cached_duplicates: u32,
    last_dual_sim_event_ms: Option<u64>,
}

impl RfMonitorState {
    pub fn reset(&mut self) {
        self.slots.clear();
        self.events.clear();
        self.seen_protocol_faults.clear();
        self.protocol_faults.clear();
        self.total_fresh_frames = 0;
        self.total_cached_duplicates = 0;
        self.last_dual_sim_event_ms = None;
    }

    pub fn ingest_slot(&mut self, mut sample: CellularSlotSample) -> bool {
        sample.rsrp_dbm = clean_metric(sample.rsrp_dbm, -160, -30);
        sample.rsrq_db = clean_metric(sample.rsrq_db, -50, 0);
        sample.sinr_db = clean_metric(sample.sinr_db, -30, 50);
        sample.arfcn = clean_arfcn(sample.arfcn);
        let now_ms = sample.observed_at_ms;
        let phone_id = sample.phone_id;

        let slot = self.slots.entry(phone_id).or_default();
        if !sample.operator.is_empty() {
            slot.operator = sample.operator.clone();
        } else if slot.operator.is_empty() {
            slot.operator = format!("卡 {}", phone_id + 1);
        }
        let operator = slot.operator.clone();

        // 硬件 mTimeStamp + 指标指纹严格去重，防止息屏 dumpsys 缓存被当成新样本。
        // Deduplicate by hardware mTimeStamp + signal fingerprint to catch screen-off cache freeze.
        if let Some(ts) = sample.cell_ts_ns.as_ref().filter(|s| !s.is_empty()) {
            let fp = format!(
                "{:?}/{:?}/{:?}/{:?}",
                sample.rsrp_dbm, sample.rsrq_db, sample.sinr_db, sample.arfcn
            );
            let key = (sample.rat.clone(), ts.clone(), fp);
            if slot.last_cell_key.as_ref() == Some(&key) {
                slot.cached_duplicates += 1;
                slot.consecutive_stale += 1;
                self.total_cached_duplicates += 1;
                let emit_freeze =
                    slot.consecutive_stale >= STALE_FREEZE_THRESHOLD && !slot.freeze_emitted;
                let stale_cnt = slot.consecutive_stale;
                if emit_freeze {
                    slot.freeze_emitted = true;
                    self.push_event(RfEvent {
                        observed_at_ms: now_ms,
                        slot: Some(phone_id),
                        code: "CACHE_FREEZE".into(),
                        title: format!("{operator} 基带缓存未刷新"),
                        detail: format!(
                            "连续 {stale_cnt} 次 dumpsys 返回相同硬件时间戳，已去重；亮屏或在手机端开启 Wiesler 可触发主动刷新。"
                        ),
                    });
                }
                return false;
            }
            slot.last_cell_key = Some(key);
            slot.consecutive_stale = 0;
            slot.freeze_emitted = false;
        }

        let scissors = is_scissors(&sample);
        let sync_loss = is_sync_loss(&sample);
        let band = arfcn_to_band(sample.arfcn, &sample.rat);

        let slot = self.slots.entry(phone_id).or_default();
        slot.fresh_frames += 1;
        self.total_fresh_frames += 1;

        let ch_key = if sample.rat == "NO_SIGNAL" || !sample.in_service {
            Some("NO_SIGNAL".to_string())
        } else {
            Some(match sample.arfcn {
                Some(ch) => format!("{}:{ch}", sample.rat),
                None => sample.rat.clone(),
            })
        };
        if let Some(key) = ch_key {
            if let Some(prev) = slot.last_channel_key.as_ref()
                && prev != &key
            {
                slot.transitions.push_back(now_ms);
            }
            slot.last_channel_key = Some(key);
        }
        while slot
            .transitions
            .front()
            .is_some_and(|ts| now_ms.saturating_sub(*ts) > WINDOW_PINGPONG_MS)
        {
            slot.transitions.pop_front();
        }
        let transition_count = slot.transitions.len();

        slot.samples.push_back(sample.clone());
        while slot.samples.len() > MAX_SAMPLES_PER_SLOT {
            slot.samples.pop_front();
        }
        slot.latest = Some(sample.clone());

        let mut emit_scissors = None;
        if scissors {
            slot.consecutive_scissors += 1;
            if slot.consecutive_scissors >= 2 {
                let should = slot
                    .last_scissors_alert_ms
                    .is_none_or(|t| now_ms.saturating_sub(t) >= 8_000);
                slot.last_scissors_alert_ms = Some(now_ms);
                if should {
                    emit_scissors = Some(slot.consecutive_scissors);
                }
            }
        } else {
            slot.consecutive_scissors = 0;
        }

        let mut emit_sync_loss = false;
        if sync_loss {
            let should = slot
                .last_sync_loss_alert_ms
                .is_none_or(|t| now_ms.saturating_sub(t) >= 8_000);
            slot.last_sync_loss_alert_ms = Some(now_ms);
            emit_sync_loss = should;
        }

        let mut emit_pingpong = false;
        if transition_count >= PINGPONG_MIN_TRANSITIONS {
            let should = slot
                .last_pingpong_alert_ms
                .is_none_or(|t| now_ms.saturating_sub(t) >= 15_000);
            slot.last_pingpong_alert_ms = Some(now_ms);
            emit_pingpong = should;
        }

        if let Some(cnt) = emit_scissors {
            self.push_event(RfEvent {
                observed_at_ms: now_ms,
                slot: Some(phone_id),
                code: "RF_INTERFERENCE".into(),
                title: format!("{operator} 强场强剪刀差干扰"),
                detail: format!(
                    "{band} · RSRP {} dBm / RSRQ {} dB / SINR {} dB（连续 {cnt} 帧）",
                    fmt_opt(sample.rsrp_dbm),
                    fmt_opt(sample.rsrq_db),
                    fmt_opt(sample.sinr_db)
                ),
            });
        }
        if emit_sync_loss {
            self.push_event(RfEvent {
                observed_at_ms: now_ms,
                slot: Some(phone_id),
                code: "RF_SYNC_LOSS".into(),
                title: format!("{operator} 接收机失步 / 失锁"),
                detail: format!(
                    "{band} · RSRP {} dBm / RSRQ {} dB / 状态 {}",
                    fmt_opt(sample.rsrp_dbm),
                    fmt_opt(sample.rsrq_db),
                    if sample.in_service {
                        "3GPP底板"
                    } else {
                        "NO_SIGNAL"
                    }
                ),
            });
        }
        if emit_pingpong {
            self.push_event(RfEvent {
                observed_at_ms: now_ms,
                slot: Some(phone_id),
                code: "CELL_PINGPONG".into(),
                title: format!("{operator} 高频乒乓切换"),
                detail: format!("最近 60 秒内 ARFCN/RAT 变更 {transition_count} 次（当前 {band}）"),
            });
        }

        let concurrent = self
            .slots
            .values()
            .filter(|s| {
                s.last_scissors_alert_ms
                    .is_some_and(|t| now_ms.saturating_sub(t) <= WINDOW_COINCIDENCE_MS)
                    || s.last_sync_loss_alert_ms
                        .is_some_and(|t| now_ms.saturating_sub(t) <= WINDOW_COINCIDENCE_MS)
            })
            .count();
        if concurrent >= 2
            && self
                .last_dual_sim_event_ms
                .is_none_or(|t| now_ms.saturating_sub(t) >= 15_000)
        {
            self.last_dual_sim_event_ms = Some(now_ms);
            self.push_event(RfEvent {
                observed_at_ms: now_ms,
                slot: None,
                code: "DUAL_SIM_POLLUTION".into(),
                title: "环境级宽带通信污染告警".into(),
                detail: "30 秒滑窗内双卡同步触发干扰/失步，已排除单一运营商基站维护。".into(),
            });
        }

        true
    }

    pub fn ingest_protocol_fault(&mut self, fault: ProtocolFault) {
        if !self.seen_protocol_faults.insert(fault.signature.clone()) {
            return;
        }
        let event = RfEvent {
            observed_at_ms: fault.observed_at_ms,
            slot: None,
            code: fault.kind.clone(),
            title: format!("基带协议栈故障 · {}", fault.kind),
            detail: fault.detail.clone(),
        };
        self.protocol_faults.push_back(fault);
        while self.protocol_faults.len() > 40 {
            self.protocol_faults.pop_front();
        }
        self.push_event(event);
    }

    pub fn build_report(
        &mut self,
        now_ms: u64,
        wifi: WifiHealth,
        adb_connected: bool,
        adb_device: Option<String>,
        adb_note: String,
        link: &RfLinkContext,
    ) -> RfSnapshotReport {
        let mut slot_summaries = Vec::new();
        for (&phone_id, slot) in &mut self.slots {
            while slot
                .transitions
                .front()
                .is_some_and(|ts| now_ms.saturating_sub(*ts) > WINDOW_PINGPONG_MS)
            {
                slot.transitions.pop_front();
            }
            let recent30: Vec<&CellularSlotSample> = slot
                .samples
                .iter()
                .filter(|s| now_ms.saturating_sub(s.observed_at_ms) <= WINDOW_COINCIDENCE_MS)
                .collect();
            let recent60: Vec<&CellularSlotSample> = slot
                .samples
                .iter()
                .filter(|s| now_ms.saturating_sub(s.observed_at_ms) <= WINDOW_PINGPONG_MS)
                .collect();
            let scissors_30s = recent30.iter().filter(|s| is_scissors(s)).count() as u32;
            let sync_loss_30s = recent30.iter().filter(|s| is_sync_loss(s)).count() as u32;
            let pingpong_60s = slot.transitions.len() as u32;
            let frozen = slot.consecutive_stale >= STALE_FREEZE_THRESHOLD;

            let mut flags = Vec::new();
            if slot
                .last_scissors_alert_ms
                .is_some_and(|t| now_ms.saturating_sub(t) <= WINDOW_COINCIDENCE_MS)
            {
                flags.push("RF_INTERFERENCE".to_string());
            }
            if sync_loss_30s > 0
                || slot
                    .last_sync_loss_alert_ms
                    .is_some_and(|t| now_ms.saturating_sub(t) <= WINDOW_COINCIDENCE_MS)
            {
                flags.push("RF_SYNC_LOSS".to_string());
            }
            if pingpong_60s as usize >= PINGPONG_MIN_TRANSITIONS {
                flags.push("CELL_PINGPONG".to_string());
            }
            if frozen {
                flags.push("CACHE_FREEZE".to_string());
            }

            let pairs: Vec<(f64, f64)> = recent60
                .iter()
                .filter_map(|s| Some((s.rsrp_dbm? as f64, s.sinr_db? as f64)))
                .collect();
            let corr = pearson(&pairs);

            let latest = slot.latest.as_ref();
            let rat = latest
                .map(|s| s.rat.clone())
                .unwrap_or_else(|| "UNKNOWN".into());
            let arfcn = latest.and_then(|s| s.arfcn);
            let band = arfcn_to_band(arfcn, &rat);

            slot_summaries.push(RfSlotSummary {
                phone_id,
                operator: slot.operator.clone(),
                rat,
                band,
                arfcn,
                carrier_aggregation: latest.is_some_and(|s| s.carrier_aggregation),
                in_service: latest.is_none_or(|s| s.in_service),
                rsrp_dbm: latest.and_then(|s| s.rsrp_dbm),
                rsrq_db: latest.and_then(|s| s.rsrq_db),
                sinr_db: latest.and_then(|s| s.sinr_db),
                fresh_frames: slot.fresh_frames,
                cached_duplicates: slot.cached_duplicates,
                cache_frozen: frozen,
                scissors_30s,
                sync_loss_30s,
                pingpong_60s,
                rsrp_sinr_corr: corr,
                active_flags: flags,
            });
        }

        let interference_slots = slot_summaries
            .iter()
            .filter(|s| s.active_flags.iter().any(|f| f == "RF_INTERFERENCE"))
            .count();
        let sync_loss_slots = slot_summaries
            .iter()
            .filter(|s| s.active_flags.iter().any(|f| f == "RF_SYNC_LOSS"))
            .count();
        let pingpong_slots = slot_summaries
            .iter()
            .filter(|s| s.active_flags.iter().any(|f| f == "CELL_PINGPONG"))
            .count();
        let severe_slots = slot_summaries
            .iter()
            .filter(|s| {
                s.active_flags
                    .iter()
                    .any(|f| f == "RF_INTERFERENCE" || f == "RF_SYNC_LOSS")
            })
            .count();
        let dual_sim_coincidence = severe_slots >= 2;
        let recent_proto_faults = self
            .protocol_faults
            .iter()
            .filter(|f| now_ms.saturating_sub(f.observed_at_ms) <= WINDOW_PINGPONG_MS)
            .count() as u32;

        let mut score: u32 = 0;
        if interference_slots > 0 {
            score += 35 + ((interference_slots - 1) as u32) * 15;
        }
        if sync_loss_slots > 0 {
            score += 45 + ((sync_loss_slots - 1) as u32) * 15;
        }
        if pingpong_slots > 0 {
            score += 20;
        }
        if recent_proto_faults > 0 {
            score += 15;
        }
        if dual_sim_coincidence {
            score = (score + 25).max(85);
        }
        score = score.min(100);

        let (severity, severity_label, assessment_title, assessment_detail) = if slot_summaries
            .is_empty()
        {
            (
                "idle".to_string(),
                if wifi.available {
                    "仅本机 Wi-Fi"
                } else {
                    "待采集"
                }
                .to_string(),
                if adb_connected {
                    "ADB 已连接，等待基带解析".to_string()
                } else {
                    "未连接 ADB 手机 · 仅监测本机 Wi-Fi".to_string()
                },
                adb_note.clone(),
            )
        } else if dual_sim_coincidence {
            (
                "severe".to_string(),
                "环境级宽带污染".to_string(),
                "检测到双卡并发宽带通信污染".to_string(),
                format!(
                    "30 秒内 {severe_slots} 张卡同步触发剪刀差/失步（污染分 {score}/100），排除单一运营商维护。"
                ),
            )
        } else if severe_slots > 0 || score >= 45 {
            (
                "interference".to_string(),
                "单卡干扰或失步".to_string(),
                "检测到单卡射频干扰或底板失步".to_string(),
                format!("射频风险分 {score}/100，近期剪刀差/失步事件活跃，请注意空口断流风险。"),
            )
        } else if pingpong_slots > 0 || recent_proto_faults > 0 || score >= 15 {
            (
                "watch".to_string(),
                "轻度波动".to_string(),
                "存在频点切换或协议栈偶发事件".to_string(),
                format!("射频风险分 {score}/100，暂未构成跨卡宽带通信污染。"),
            )
        } else {
            (
                "clear".to_string(),
                "蜂窝平稳".to_string(),
                "手机双卡空口射频平稳".to_string(),
                slot_summaries
                    .iter()
                    .map(|s| {
                        format!(
                            "{} {} ({}/{} dB)",
                            s.operator,
                            s.band,
                            fmt_opt(s.rsrp_dbm),
                            fmt_opt(s.sinr_db)
                        )
                    })
                    .collect::<Vec<_>>()
                    .join(" · "),
            )
        };

        let attribution = build_desktop_attribution(
            &wifi,
            adb_connected,
            !slot_summaries.is_empty(),
            &severity,
            score,
            dual_sim_coincidence,
            recent_proto_faults,
            link,
        );

        RfSnapshotReport {
            observed_at_ms: now_ms,
            wifi,
            adb_connected,
            adb_device,
            adb_note,
            fresh_frames: self.total_fresh_frames,
            cached_duplicates: self.total_cached_duplicates,
            frozen_slots: slot_summaries.iter().filter(|s| s.cache_frozen).count() as u32,
            score,
            severity,
            severity_label,
            assessment_title,
            assessment_detail,
            dual_sim_coincidence,
            scissors_alerts: slot_summaries.iter().map(|s| s.scissors_30s).sum(),
            sync_loss_alerts: slot_summaries.iter().map(|s| s.sync_loss_30s).sum(),
            pingpong_alerts: slot_summaries.iter().map(|s| s.pingpong_60s).sum(),
            protocol_faults_count: recent_proto_faults,
            slots: slot_summaries,
            recent_events: self.events.iter().cloned().collect(),
            attribution,
        }
    }

    fn push_event(&mut self, event: RfEvent) {
        self.events.push_front(event);
        while self.events.len() > MAX_EVENTS {
            self.events.pop_back();
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn build_desktop_attribution(
    wifi: &WifiHealth,
    adb_connected: bool,
    has_cellular: bool,
    rf_severity: &str,
    rf_score: u32,
    dual_sim: bool,
    proto_faults: u32,
    link: &RfLinkContext,
) -> RfAttribution {
    let rf_bad = rf_severity == "severe" || rf_severity == "interference";
    let wifi_bad = wifi.available && wifi.bottleneck;
    let icmp_bad = link.icmp_loss_percent.is_some_and(|p| p >= 10.0)
        || link.icmp_avg_rtt_ms.is_some_and(|r| r >= 250.0);
    let icmp_good = link.icmp_sample_count >= 3
        && link.icmp_loss_percent.unwrap_or(100.0) < 5.0
        && link.icmp_avg_rtt_ms.unwrap_or(999.0) < 200.0;
    let proxy_bad = link.controller_connected && !link.controller_healthy;

    if wifi_bad && rf_bad {
        return RfAttribution {
            cause: "COMPOUND_FAULT".into(),
            confidence: "高".into(),
            title: "本机 Wi-Fi 与手机蜂窝空口双重劣化".into(),
            detail: format!(
                "笔记本 Wi-Fi 信号偏弱（{}），同时手机蜂窝触发射频告警（{rf_score}/100）。",
                wifi.summary
            ),
            suggestion: "先用 USB 共享网络排除 Wi-Fi 热点段损耗，再排查蜂窝空口干扰源。".into(),
        };
    }
    if wifi_bad {
        return RfAttribution {
            cause: "WIFI_HOTSPOT_BOTTLENECK".into(),
            confidence: "高".into(),
            title: "优先归因为本机 Wi-Fi 热点段瓶颈".into(),
            detail: format!(
                "本机无线链路信号过弱或掉速（{}），易先于蜂窝空口引发高抖动与丢包。",
                wifi.summary
            ),
            suggestion: "将笔记本靠近手机热点、切换 5 GHz 热点或改用 USB 网络共享后复测。".into(),
        };
    }
    if rf_bad {
        let wifi_note = if wifi.available {
            format!("本机 Wi-Fi 正常（{}）", wifi.summary)
        } else {
            "本机非 Wi-Fi / 有线接入".to_string()
        };
        let proto_note = if proto_faults > 0 {
            format!("，并捕获 {proto_faults} 条基带协议栈故障")
        } else {
            String::new()
        };
        return RfAttribution {
            cause: "CELLULAR_RF_POLLUTION".into(),
            confidence: if dual_sim || icmp_bad || proto_faults > 0 {
                "高"
            } else {
                "中"
            }
            .into(),
            title: "归因为手机蜂窝空口射频污染".into(),
            detail: format!(
                "{wifi_note}，但 ADB 旁路监测到手机蜂窝评分 {rf_score}/100{proto_note}，根因位于手机蜂窝空口而非本机 Wi-Fi 或代理。"
            ),
            suggestion: "无需反复切换代理节点；建议远离强射频干扰区或切换至宽带 Wi-Fi。".into(),
        };
    }
    if proxy_bad && (icmp_good || !icmp_bad) {
        let err = link
            .last_controller_error
            .as_deref()
            .map(|e| format!("（{e}）"))
            .unwrap_or_default();
        return RfAttribution {
            cause: "PROXY_OR_EGRESS".into(),
            confidence: if has_cellular || icmp_good {
                "高"
            } else {
                "中"
            }
            .into(),
            title: "本地无线与蜂窝平稳 · 归因为代理/出口故障".into(),
            detail: format!(
                "本机无线段正常{}，但代理控制器或节点状态异常{err}。",
                if has_cellular {
                    format!("且手机蜂窝平稳（{rf_score}/100）")
                } else {
                    String::new()
                }
            ),
            suggestion: "检查 Mihomo 节点连通性、订阅线路或控制器配置。".into(),
        };
    }
    if icmp_bad {
        return RfAttribution {
            cause: "UPSTREAM_LINK".into(),
            confidence: if has_cellular { "高" } else { "中" }.into(),
            title: "无线段平稳 · 上游骨干或运营商回程抖动".into(),
            detail: format!(
                "本机 Wi-Fi 正常{}，但 ICMP 丢包 {:.1}%（均延迟 {} ms）。",
                if has_cellular {
                    format!("、手机蜂窝平稳（{rf_score}/100）")
                } else {
                    "（未接入 ADB 手机基带）".to_string()
                },
                link.icmp_loss_percent.unwrap_or(0.0),
                link.icmp_avg_rtt_ms
                    .map(|v| format!("{v:.0}"))
                    .unwrap_or_else(|| "—".into())
            ),
            suggestion: if adb_connected {
                "对比探测 223.5.5.5 与 1.1.1.1 区分国内骨干与跨境路由拥塞。".into()
            } else {
                "插入手机 USB 并开启 ADB 调试即可同时核查手机蜂窝基带是否受干扰。".into()
            },
        };
    }
    if wifi.available && (has_cellular || icmp_good || link.controller_healthy) {
        return RfAttribution {
            cause: "HEALTHY".into(),
            confidence: if has_cellular && icmp_good {
                "高"
            } else {
                "中"
            }
            .into(),
            title: "本机 Wi-Fi 与观测链路均平稳".into(),
            detail: format!(
                "本机 Wi-Fi：{}；{}。",
                wifi.summary,
                if has_cellular {
                    format!("手机蜂窝空口平稳（评分 {rf_score}/100）")
                } else {
                    "未连接 ADB 手机，当前仅覆盖本机无线与链路段".to_string()
                }
            ),
            suggestion: "当前链路健康；如需完整双卡基带对照，可通过 USB/无线连接 ADB 手机。".into(),
        };
    }

    RfAttribution {
        cause: "INSUFFICIENT_EVIDENCE".into(),
        confidence: "待采样".into(),
        title: "等待三段对照采样".into(),
        detail: "点击「开始射频监测」采集本机 Wi-Fi 与 ADB 手机基带，并搭配 ICMP 丢包探测形成三段闭环归因。".into(),
        suggestion: "建议同时启动 ICMP 探测（如 223.5.5.5）并保持手机 ADB 连接。".into(),
    }
}

pub fn now_epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX))
        .unwrap_or(0)
}

pub fn clean_metric(raw: Option<i32>, min_val: i32, max_val: i32) -> Option<i32> {
    let v = raw?;
    if v.abs() >= 2_147_483_600 || v == -1000 || v < min_val || v > max_val {
        return None;
    }
    Some(v)
}

pub fn clean_arfcn(raw: Option<i32>) -> Option<i32> {
    let v = raw?;
    if v <= 0 || v >= 2_147_483_600 {
        None
    } else {
        Some(v)
    }
}

pub fn arfcn_to_band(arfcn: Option<i32>, rat: &str) -> String {
    let Some(ch) = clean_arfcn(arfcn) else {
        return if rat == "NO_SIGNAL" {
            "失锁".into()
        } else {
            rat.to_string()
        };
    };
    if rat.contains("NR") {
        match ch {
            151_600..=160_600 => "n28 (700MHz)".into(),
            361_000..=376_000 => "n3 (1.8GHz)".into(),
            422_000..=434_000 => "n1 (2.1GHz)".into(),
            499_200..=537_999 => "n41 (2.6GHz)".into(),
            620_000..=653_333 => "n78 (3.5GHz)".into(),
            693_334..=733_333 => "n79 (4.9GHz)".into(),
            _ => format!("NR#{ch}"),
        }
    } else if rat.contains("LTE") {
        match ch {
            0..=599 => "B1 (2.1GHz)".into(),
            1_200..=1_949 => "B3 (1.8GHz)".into(),
            2_400..=2_649 => "B5 (850MHz)".into(),
            3_450..=3_799 => "B8 (900MHz)".into(),
            37_750..=38_249 => "B38 (2.6GHz)".into(),
            38_250..=38_649 => "B39 (1.9GHz)".into(),
            38_650..=39_649 => "B40 (2.3GHz)".into(),
            39_650..=41_589 => "B41 (2.6GHz)".into(),
            _ => format!("LTE#{ch}"),
        }
    } else {
        format!("{rat}#{ch}")
    }
}

fn is_scissors(sample: &CellularSlotSample) -> bool {
    let Some(rsrp) = sample.rsrp_dbm else {
        return false;
    };
    if rsrp < SCISSORS_RSRP_MIN_DBM {
        return false;
    }
    let bad_sinr = sample.sinr_db.is_some_and(|v| v <= SCISSORS_SINR_MAX_DB);
    let bad_rsrq = sample.rsrq_db.is_some_and(|v| v <= SCISSORS_RSRQ_MAX_DB);
    bad_sinr || bad_rsrq
}

fn is_sync_loss(sample: &CellularSlotSample) -> bool {
    if !sample.in_service || sample.rat == "NO_SIGNAL" {
        return true;
    }
    if sample.rsrp_dbm.is_some_and(|v| v <= SYNC_FLOOR_RSRP_DBM) {
        return true;
    }
    sample.rsrq_db.is_some_and(|v| v <= SYNC_FLOOR_RSRQ_DB)
}

fn pearson(pairs: &[(f64, f64)]) -> Option<f64> {
    if pairs.len() < 4 {
        return None;
    }
    let n = pairs.len() as f64;
    let mx = pairs.iter().map(|(x, _)| *x).sum::<f64>() / n;
    let my = pairs.iter().map(|(_, y)| *y).sum::<f64>() / n;
    let (mut num, mut dx2, mut dy2) = (0.0, 0.0, 0.0);
    for (x, y) in pairs {
        let dx = x - mx;
        let dy = y - my;
        num += dx * dy;
        dx2 += dx * dx;
        dy2 += dy * dy;
    }
    if dx2 <= 1e-9 || dy2 <= 1e-9 {
        return None;
    }
    Some((num / (dx2 * dy2).sqrt()).clamp(-1.0, 1.0))
}

fn fmt_opt(v: Option<i32>) -> String {
    v.map(|n| n.to_string()).unwrap_or_else(|| "—".into())
}

// =========================================================================
// 解析器：Windows netsh Wi-Fi / Android dumpsys telephony.registry / radio log
// =========================================================================

pub fn parse_netsh_wlan(output: &str) -> WifiHealth {
    let mut ssid: Option<String> = None;
    let mut rssi: Option<i32> = None;
    let mut signal: Option<u32> = None;
    let mut channel: Option<u32> = None;
    let mut rx_mbps: Option<f64> = None;
    let mut tx_mbps: Option<f64> = None;
    let mut connected = false;

    for raw in output.lines() {
        let Some((k, v)) = raw.split_once(':') else {
            continue;
        };
        let key = k.trim().to_ascii_lowercase();
        let val = v.trim();
        if (key == "state" || k.trim() == "状态")
            && (val.eq_ignore_ascii_case("connected") || val.contains("已连接"))
        {
            connected = true;
        } else if (key == "ssid" || k.trim() == "SSID") && !val.is_empty() && ssid.is_none() {
            ssid = Some(val.to_string());
        } else if key == "rssi" {
            rssi = extract_first_i32(val);
        } else if key == "signal" || k.trim() == "信号" {
            signal = extract_first_i32(val).and_then(|n| u32::try_from(n).ok());
        } else if key == "channel" || k.trim() == "信道" {
            channel = extract_first_i32(val).and_then(|n| u32::try_from(n).ok());
        } else if key.contains("receive rate") || k.trim().contains("接收速率") {
            rx_mbps = extract_first_f64(val);
        } else if key.contains("transmit rate") || k.trim().contains("传输速率") {
            tx_mbps = extract_first_f64(val);
        }
    }

    if !connected && ssid.is_none() && rssi.is_none() && signal.is_none() {
        return WifiHealth {
            available: false,
            ssid: None,
            rssi_dbm: None,
            signal_percent: None,
            channel: None,
            rx_mbps: None,
            tx_mbps: None,
            bottleneck: false,
            summary: "未检测到已连接的 Wi-Fi 网卡（可能使用有线或 USB 共享网络）".into(),
        };
    }

    // 若 Windows 仅返回信号百分比未输出 RSSI，用标准线性映射估算 dBm。
    let effective_rssi = rssi.or_else(|| signal.map(|pct| (pct as i32 / 2) - 100));
    let bottleneck = effective_rssi.is_some_and(|dbm| dbm <= -78)
        || signal.is_some_and(|pct| pct <= 35)
        || rx_mbps.is_some_and(|r| r < 24.0);

    let summary = format!(
        "{} · RSSI {} dBm · 信号 {}% · Ch{}{}",
        ssid.as_deref().unwrap_or("Wi-Fi"),
        fmt_opt(effective_rssi),
        signal.map(|s| s.to_string()).unwrap_or_else(|| "—".into()),
        channel.map(|c| c.to_string()).unwrap_or_else(|| "—".into()),
        rx_mbps
            .map(|r| format!(" · {r:.0} Mbps"))
            .unwrap_or_default()
    );

    WifiHealth {
        available: true,
        ssid,
        rssi_dbm: effective_rssi,
        signal_percent: signal,
        channel,
        rx_mbps,
        tx_mbps,
        bottleneck,
        summary,
    }
}

pub fn parse_telephony_registry(output: &str, now_ms: u64) -> Vec<CellularSlotSample> {
    let mut slots: BTreeMap<u32, CellularSlotSample> = BTreeMap::new();
    let mut cur_pid: Option<u32> = None;

    for raw in output.lines() {
        let trimmed = raw.trim();
        if let Some(rest) = trimmed.strip_prefix("Phone Id=") {
            if let Some(pid) = extract_first_i32(rest).and_then(|n| u32::try_from(n).ok()) {
                cur_pid = Some(pid);
                slots.entry(pid).or_insert_with(|| CellularSlotSample {
                    observed_at_ms: now_ms,
                    phone_id: pid,
                    operator: format!("卡 {}", pid + 1),
                    rat: "UNKNOWN".into(),
                    arfcn: None,
                    carrier_aggregation: false,
                    data_state: None,
                    rsrp_dbm: None,
                    rsrq_db: None,
                    sinr_db: None,
                    cell_ts_ns: None,
                    in_service: true,
                });
            }
            continue;
        }
        let Some(pid) = cur_pid else {
            continue;
        };
        let Some(entry) = slots.get_mut(&pid) else {
            continue;
        };

        if trimmed.starts_with("mServiceState=") {
            if let Some(op) = extract_between(trimmed, "mOperatorAlphaLong=", ",") {
                let op_clean = op.trim();
                if !op_clean.is_empty() && op_clean != "null" {
                    entry.operator = op_clean.to_string();
                }
            }
            if trimmed.contains("mDataRegState=1(OUT_OF_SERVICE)")
                && trimmed.contains("mVoiceRegState=1(OUT_OF_SERVICE)")
            {
                entry.in_service = false;
                entry.rat = "NO_SIGNAL".into();
            } else if let Some(rat_raw) = extract_after_paren(trimmed, "getRilDataRadioTechnology=")
            {
                if rat_raw.contains("NR") {
                    entry.rat = "NR".into();
                } else if rat_raw.contains("LTE") {
                    entry.rat = "LTE".into();
                } else if !rat_raw.eq_ignore_ascii_case("Unknown") {
                    entry.rat = rat_raw;
                }
            }
            if let Some(ch) = extract_key_i32(trimmed, "mChannelNumber=") {
                entry.arfcn = clean_arfcn(Some(ch));
            }
            if trimmed.contains("isUsingCarrierAggregation=true") {
                entry.carrier_aggregation = true;
            }
        } else if trimmed.starts_with("mDataConnectionState=") {
            entry.data_state = extract_key_i32(trimmed, "mDataConnectionState=");
        } else if trimmed.starts_with("mSignalStrength=") {
            if let Some(sig) = parse_signal_tuple(trimmed) {
                if entry.rat == "UNKNOWN" {
                    entry.rat = sig.rat;
                }
                if entry.rsrp_dbm.is_none() {
                    entry.rsrp_dbm = sig.rsrp;
                    entry.rsrq_db = sig.rsrq;
                    entry.sinr_db = sig.sinr;
                }
            }
        } else if trimmed.starts_with("mCellInfo=") {
            if let Some(ts) = extract_between(trimmed, "mTimeStamp=", "ns") {
                let ts_clean = ts.trim();
                if !ts_clean.is_empty() {
                    entry.cell_ts_ns = Some(ts_clean.to_string());
                }
            }
            if let Some(sig) = parse_signal_tuple(trimmed) {
                entry.rat = sig.rat;
                entry.rsrp_dbm = sig.rsrp;
                entry.rsrq_db = sig.rsrq;
                entry.sinr_db = sig.sinr;
            }
        }
    }

    slots
        .into_values()
        .filter(|s| {
            s.rsrp_dbm.is_some()
                || s.rsrq_db.is_some()
                || s.sinr_db.is_some()
                || !s.in_service
                || s.arfcn.is_some()
        })
        .collect()
}

pub fn parse_radio_protocol_faults(log_output: &str, now_ms: u64) -> Vec<ProtocolFault> {
    let mut faults = Vec::new();
    for line in log_output.lines() {
        let trimmed = line.trim();
        if trimmed.contains("UNSOL_REGISTRATION_FAILED") {
            let cause = extract_key_i32(trimmed, "causeCode=");
            let plmn = extract_between(trimmed, "chosenPlmn=", ",").unwrap_or("unknown");
            faults.push(ProtocolFault {
                observed_at_ms: now_ms,
                kind: "UNSOL_REGISTRATION_FAILED".into(),
                detail: format!("基站注册拒绝（PLMN {plmn}，EMM Cause #{}）", fmt_opt(cause)),
                signature: trimmed.chars().take(120).collect(),
            });
        } else if trimmed.contains("DataStallRecoveryStats") && trimmed.contains("Duration:") {
            let dur = extract_key_i32(trimmed, "Duration:");
            let pid = extract_key_i32(trimmed, "Phone id:");
            let slot_label = pid
                .and_then(|n| n.checked_add(1))
                .map(|n| n.to_string())
                .unwrap_or_else(|| "—".into());
            faults.push(ProtocolFault {
                observed_at_ms: now_ms,
                kind: "DATA_STALL".into(),
                detail: format!(
                    "卡 {slot_label} 发生 DataStall 数据停滞（持续 {} ms）",
                    fmt_opt(dur)
                ),
                signature: trimmed.chars().take(120).collect(),
            });
        } else if trimmed.contains("onDataNetworkDisconnected:")
            && (trimmed.contains("LOST_CONNECTION") || trimmed.contains("ERROR_UNSPECIFIED"))
        {
            let cause = extract_between(trimmed, "cause=", ",").unwrap_or("LOST_CONNECTION");
            faults.push(ProtocolFault {
                observed_at_ms: now_ms,
                kind: "DATA_DISCONNECTED".into(),
                detail: format!("蜂窝数据/IMS 异常断链（{cause}）"),
                signature: trimmed.chars().take(120).collect(),
            });
        }
    }
    faults
}

struct ParsedSignal {
    rat: String,
    rsrp: Option<i32>,
    rsrq: Option<i32>,
    sinr: Option<i32>,
}

fn parse_signal_tuple(text: &str) -> Option<ParsedSignal> {
    if let Some(idx) = text.find("CellSignalStrengthNr:") {
        let sub = &text[idx..];
        let rsrp = clean_metric(extract_key_i32(sub, "ssRsrp"), -160, -30);
        let rsrq = clean_metric(extract_key_i32(sub, "ssRsrq"), -50, 0);
        let sinr = clean_metric(extract_key_i32(sub, "ssSinr"), -30, 50);
        if rsrp.is_some() || rsrq.is_some() || sinr.is_some() {
            return Some(ParsedSignal {
                rat: "NR".into(),
                rsrp,
                rsrq,
                sinr,
            });
        }
    }
    if let Some(idx) = text.find("CellSignalStrengthLte:") {
        let sub = &text[idx..];
        let rsrp = clean_metric(extract_key_i32(sub, "rsrp="), -160, -30);
        let rsrq = clean_metric(extract_key_i32(sub, "rsrq="), -50, 0);
        let sinr = clean_metric(extract_key_i32(sub, "rssnr="), -30, 50);
        if rsrp.is_some() || rsrq.is_some() || sinr.is_some() {
            return Some(ParsedSignal {
                rat: "LTE".into(),
                rsrp,
                rsrq,
                sinr,
            });
        }
    }
    None
}

fn extract_key_i32(text: &str, key: &str) -> Option<i32> {
    let idx = text.find(key)?;
    let rest = &text[idx + key.len()..];
    let rest = rest.trim_start_matches([' ', '=']);
    extract_first_i32(rest)
}

fn extract_first_i32(text: &str) -> Option<i32> {
    let bytes = text.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let b = bytes[i];
        if b.is_ascii_digit() || (b == b'-' && i + 1 < bytes.len() && bytes[i + 1].is_ascii_digit())
        {
            let start = i;
            i += 1;
            while i < bytes.len() && bytes[i].is_ascii_digit() {
                i += 1;
            }
            return text[start..i].parse::<i32>().ok();
        }
        i += 1;
    }
    None
}

fn extract_first_f64(text: &str) -> Option<f64> {
    let bytes = text.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i].is_ascii_digit() {
            let start = i;
            while i < bytes.len() && (bytes[i].is_ascii_digit() || bytes[i] == b'.') {
                i += 1;
            }
            return text[start..i].parse::<f64>().ok();
        }
        i += 1;
    }
    None
}

fn extract_between<'a>(text: &'a str, start_pat: &str, end_pat: &str) -> Option<&'a str> {
    let start = text.find(start_pat)? + start_pat.len();
    let rest = &text[start..];
    let end = rest.find(end_pat).unwrap_or(rest.len());
    Some(&rest[..end])
}

fn extract_after_paren(text: &str, key: &str) -> Option<String> {
    let start = text.find(key)? + key.len();
    let rest = &text[start..];
    let open = rest.find('(')?;
    let inner = &rest[open + 1..];
    let close = inner.find(')')?;
    Some(inner[..close].trim().to_string())
}

// =========================================================================
// 外部命令执行（带严格超时保护，防挂起）
// =========================================================================

fn read_pipe_bounded<R: Read>(mut reader: R, cap: usize) -> Vec<u8> {
    let mut out = Vec::new();
    let mut buf = [0u8; 8192];
    loop {
        match reader.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                // 超过上限后继续排空管道但不追加，防止子进程写满 OS 管道缓冲而死锁。
                // Keep draining past the cap so the child never deadlocks on a full OS pipe buffer.
                if out.len() < cap {
                    let take = n.min(cap - out.len());
                    out.extend_from_slice(&buf[..take]);
                }
            }
        }
    }
    out
}

fn run_command_with_timeout(
    program: &str,
    args: &[&str],
    timeout: Duration,
) -> Result<String, String> {
    let mut cmd = Command::new(program);
    cmd.args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }

    let mut child = cmd
        .spawn()
        .map_err(|e| format!("无法启动 {program}: {e}"))?;
    // 立即并发排空 stdout/stderr，避免 Windows 4 KB 匿名管道写满导致 dumpsys/logcat 死锁超时。
    // Drain stdout/stderr concurrently so large outputs (>4 KB on Windows) never deadlock try_wait.
    let stdout_handle = child
        .stdout
        .take()
        .map(|pipe| std::thread::spawn(move || read_pipe_bounded(pipe, MAX_CMD_STDOUT_BYTES)));
    let stderr_handle = child
        .stderr
        .take()
        .map(|pipe| std::thread::spawn(move || read_pipe_bounded(pipe, MAX_CMD_STDERR_BYTES)));

    let start = Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                let stdout = stdout_handle
                    .and_then(|h| h.join().ok())
                    .unwrap_or_default();
                let stderr = stderr_handle
                    .and_then(|h| h.join().ok())
                    .unwrap_or_default();
                if !status.success() {
                    let err_text = String::from_utf8_lossy(&stderr);
                    let first_line = err_text.lines().next().unwrap_or("").trim();
                    let code = status
                        .code()
                        .map(|c| c.to_string())
                        .unwrap_or_else(|| "signal".into());
                    return if first_line.is_empty() {
                        Err(format!("{program} 退出异常（code {code}）"))
                    } else {
                        Err(format!("{program} 退出异常（code {code}）：{first_line}"))
                    };
                }
                return Ok(String::from_utf8_lossy(&stdout).into_owned());
            }
            Ok(None) => {
                if start.elapsed() >= timeout {
                    let _ = child.kill();
                    let _ = child.wait();
                    if let Some(h) = stdout_handle {
                        let _ = h.join();
                    }
                    if let Some(h) = stderr_handle {
                        let _ = h.join();
                    }
                    return Err(format!("{program} 执行超时（>{}s）", timeout.as_secs()));
                }
                std::thread::sleep(Duration::from_millis(25));
            }
            Err(e) => {
                let _ = child.kill();
                let _ = child.wait();
                if let Some(h) = stdout_handle {
                    let _ = h.join();
                }
                if let Some(h) = stderr_handle {
                    let _ = h.join();
                }
                return Err(format!("等待 {program} 失败: {e}"));
            }
        }
    }
}

fn find_adb_binary() -> Option<String> {
    static ADB_CACHE: Mutex<Option<String>> = Mutex::new(None);
    if let Ok(guard) = ADB_CACHE.lock()
        && let Some(cached) = guard.as_ref()
        && (cached == "adb" || PathBuf::from(cached).is_file())
    {
        return Some(cached.clone());
    }
    let resolved = if run_command_with_timeout("adb", &["version"], Duration::from_secs(2)).is_ok()
    {
        Some("adb".to_string())
    } else {
        let mut candidates: Vec<PathBuf> = Vec::new();
        if let Some(local) = std::env::var_os("LOCALAPPDATA") {
            let base = PathBuf::from(&local);
            candidates.push(
                base.join("Android")
                    .join("Sdk")
                    .join("platform-tools")
                    .join("adb.exe"),
            );
            let winget_pkgs = base.join("Microsoft").join("WinGet").join("Packages");
            if let Ok(entries) = std::fs::read_dir(winget_pkgs) {
                for entry in entries.flatten() {
                    let p = entry.path();
                    if let Ok(sub) = std::fs::read_dir(&p) {
                        for s in sub.flatten() {
                            let adb = s.path().join("adb.exe");
                            if adb.is_file() {
                                candidates.push(adb);
                            }
                        }
                    }
                }
            }
        }
        candidates
            .into_iter()
            .find(|p| p.is_file())
            .map(|p| p.to_string_lossy().into_owned())
    };
    if let Some(ref found) = resolved
        && let Ok(mut guard) = ADB_CACHE.lock()
    {
        *guard = Some(found.clone());
    }
    resolved
}

pub fn collect_local_wifi() -> WifiHealth {
    #[cfg(target_os = "windows")]
    {
        match run_command_with_timeout(
            "netsh",
            &["wlan", "show", "interfaces"],
            Duration::from_secs(3),
        ) {
            Ok(out) => parse_netsh_wlan(&out),
            Err(err) => WifiHealth {
                available: false,
                ssid: None,
                rssi_dbm: None,
                signal_percent: None,
                channel: None,
                rx_mbps: None,
                tx_mbps: None,
                bottleneck: false,
                summary: format!("读取本机 Wi-Fi 状态失败：{err}"),
            },
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        WifiHealth {
            available: false,
            ssid: None,
            rssi_dbm: None,
            signal_percent: None,
            channel: None,
            rx_mbps: None,
            tx_mbps: None,
            bottleneck: false,
            summary: "本机 Wi-Fi RSSI 采集当前支持 Windows 桌面端".into(),
        }
    }
}

pub fn probe_once(state: &mut RfMonitorState, link: &RfLinkContext) -> RfSnapshotReport {
    let now_ms = now_epoch_ms();
    let wifi = collect_local_wifi();

    let Some(adb_bin) = find_adb_binary() else {
        return state.build_report(
            now_ms,
            wifi,
            false,
            None,
            "未检测到 adb 工具；当前仅监测本机 Wi-Fi，安装平台工具或开启 USB 调试后可旁路读取手机双卡基带。".into(),
            link,
        );
    };

    let devices_out = match run_command_with_timeout(&adb_bin, &["devices"], Duration::from_secs(3))
    {
        Ok(o) => o,
        Err(err) => {
            return state.build_report(
                now_ms,
                wifi,
                false,
                None,
                format!("ADB 设备枚举失败：{err}"),
                link,
            );
        }
    };

    let device = devices_out
        .lines()
        .skip(1)
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            let serial = parts.next()?;
            let status = parts.next()?;
            if status == "device" {
                Some(serial.to_string())
            } else {
                None
            }
        })
        .next();

    let Some(serial) = device else {
        return state.build_report(
            now_ms,
            wifi,
            false,
            None,
            "未发现已授权的 ADB 手机设备；仅监测本机 Wi-Fi 无线段。".into(),
            link,
        );
    };

    let mut adb_note = format!("已通过 ADB（{serial}）旁路读取手机双卡基带");
    match run_command_with_timeout(
        &adb_bin,
        &["-s", &serial, "shell", "dumpsys", "telephony.registry"],
        Duration::from_secs(5),
    ) {
        Ok(reg_out) => {
            let parsed = parse_telephony_registry(&reg_out, now_ms);
            if parsed.is_empty() {
                adb_note = format!(
                    "ADB（{serial}）已连接，但未在 telephony.registry 中解析到蜂窝信号字段"
                );
            } else {
                for slot_sample in parsed {
                    state.ingest_slot(slot_sample);
                }
            }
        }
        Err(err) => {
            adb_note = format!("ADB（{serial}）读取 telephony.registry 失败：{err}");
        }
    }

    // 轻量拉取最近 250 行 radio 日志以捕获 DataStall 与 UNSOL_REGISTRATION_FAILED。
    if let Ok(radio_out) = run_command_with_timeout(
        &adb_bin,
        &["-s", &serial, "logcat", "-b", "radio", "-d", "-t", "250"],
        Duration::from_secs(4),
    ) {
        for fault in parse_radio_protocol_faults(&radio_out, now_ms) {
            state.ingest_protocol_fault(fault);
        }
    }

    state.build_report(now_ms, wifi, true, Some(serial), adb_note, link)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_windows_netsh_wifi_and_detects_bottleneck() {
        let healthy_out = "\
    Name                   : WLAN
    State                  : connected
    SSID                   : REDMI K80
    Channel                : 1
    Receive rate (Mbps)    : 144.4
    Transmit rate (Mbps)   : 144.4
    Signal                 : 100%
    Rssi                   : -10
";
        let ok = parse_netsh_wlan(healthy_out);
        assert!(ok.available);
        assert!(!ok.bottleneck);
        assert_eq!(ok.ssid.as_deref(), Some("REDMI K80"));
        assert_eq!(ok.rssi_dbm, Some(-10));
        assert_eq!(ok.channel, Some(1));

        let weak_out = "\
    状态                   : 已连接
    SSID                   : FAR_HOTSPOT
    信道                   : 11
    接收速率 (Mbps)        : 12
    信号                   : 28%
";
        let weak = parse_netsh_wlan(weak_out);
        assert!(weak.available);
        assert!(weak.bottleneck);
        assert_eq!(weak.signal_percent, Some(28));
    }

    #[test]
    fn parses_dual_sim_registry_and_detects_wideband_pollution() {
        let dump = "\
Phone Id=0
  mServiceState={mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE), mChannelNumber=504990, mOperatorAlphaLong=中国移动, getRilDataRadioTechnology=20(NR_SA), isUsingCarrierAggregation=false}
  mDataConnectionState=0
  mCellInfo=[CellInfoNr:{mRegistered=YES mTimeStamp=100000ns CellSignalStrengthNr:{ssRsrp = -85 ssRsrq = -24 ssSinr = -12}}]
Phone Id=1
  mServiceState={mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE), mChannelNumber=1650, mOperatorAlphaLong=中国电信, getRilDataRadioTechnology=14(LTE), isUsingCarrierAggregation=true}
  mDataConnectionState=2
  mCellInfo=[CellInfoLte:{mRegistered=YES mTimeStamp=200000ns CellSignalStrengthLte:{rssi=-51 rsrp=-72 rsrq=-20 rssnr=-10}}]
";
        let mut state = RfMonitorState::default();
        let t0 = 1_764_211_600_000;
        for s in parse_telephony_registry(dump, t0) {
            assert!(state.ingest_slot(s));
        }
        // 第二帧推进硬件时间戳，双卡同时满足连续 2 帧剪刀差 -> 升级为环境级宽带污染。
        let dump2 = dump
            .replace("100000ns", "102000ns")
            .replace("200000ns", "202000ns");
        for s in parse_telephony_registry(&dump2, t0 + 3_000) {
            assert!(state.ingest_slot(s));
        }

        let wifi = WifiHealth {
            available: true,
            ssid: Some("REDMI K80".into()),
            rssi_dbm: Some(-10),
            signal_percent: Some(100),
            channel: Some(1),
            rx_mbps: Some(144.0),
            tx_mbps: Some(144.0),
            bottleneck: false,
            summary: "REDMI K80 · RSSI -10 dBm".into(),
        };
        let link = RfLinkContext {
            icmp_sample_count: 10,
            icmp_loss_percent: Some(30.0),
            icmp_avg_rtt_ms: Some(180.0),
            controller_connected: true,
            controller_healthy: true,
            last_controller_error: None,
        };
        let report = state.build_report(
            t0 + 4_000,
            wifi,
            true,
            Some("12b71ec8".into()),
            "ADB ok".into(),
            &link,
        );
        assert_eq!(report.severity, "severe");
        assert!(report.dual_sim_coincidence);
        assert!(report.score >= 85);
        assert_eq!(report.attribution.cause, "CELLULAR_RF_POLLUTION");
    }

    #[test]
    fn deduplicates_frozen_dumpsys_cache_and_parses_protocol_faults() {
        let dump = "\
Phone Id=0
  mServiceState={mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE), mChannelNumber=504990, mOperatorAlphaLong=中国移动, getRilDataRadioTechnology=20(NR_SA)}
  mCellInfo=[CellInfoNr:{mRegistered=YES mTimeStamp=555555ns CellSignalStrengthNr:{ssRsrp = -91 ssRsrq = -11 ssSinr = 14}}]
";
        let mut state = RfMonitorState::default();
        let t0 = 1_764_211_700_000;
        assert!(state.ingest_slot(parse_telephony_registry(dump, t0).pop().unwrap()));
        for step in 1..=3 {
            assert!(
                !state.ingest_slot(
                    parse_telephony_registry(dump, t0 + step * 2_000)
                        .pop()
                        .unwrap()
                )
            );
        }
        let radio_log = "\
11-27 10:05:01.123  1000  1000 D RILJ: UNSOL_REGISTRATION_FAILED {CellIdentity=CellIdentityNr:{mMcc=460 mMnc=11}} chosenPlmn=46011, domain=2, causeCode=9
11-27 10:23:44.000  1000  1000 D DSRS: Upload stats: Action:1, Recovered:true, Duration:5303, Reason:1 DataStallRecoveryStats {Phone id:1, Signal strength:2, Band:3, RAT:14}
";
        let faults = parse_radio_protocol_faults(radio_log, t0 + 7_000);
        assert_eq!(faults.len(), 2);
        for f in faults {
            state.ingest_protocol_fault(f);
        }

        let wifi = WifiHealth {
            available: true,
            ssid: Some("REDMI K80".into()),
            rssi_dbm: Some(-10),
            signal_percent: Some(100),
            channel: Some(1),
            rx_mbps: Some(144.0),
            tx_mbps: Some(144.0),
            bottleneck: false,
            summary: "REDMI K80 · RSSI -10 dBm".into(),
        };
        let report = state.build_report(
            t0 + 8_000,
            wifi,
            true,
            Some("12b71ec8".into()),
            "ADB ok".into(),
            &RfLinkContext::default(),
        );
        assert_eq!(report.fresh_frames, 1);
        assert_eq!(report.cached_duplicates, 3);
        assert_eq!(report.frozen_slots, 1);
        assert_eq!(report.protocol_faults_count, 2);
        assert!(
            report
                .recent_events
                .iter()
                .any(|e| e.code == "DATA_STALL" && e.detail.contains("卡 2"))
        );
    }

    #[test]
    fn run_command_with_timeout_drains_large_stdout_and_checks_exit_status() {
        #[cfg(target_os = "windows")]
        let (prog, ok_args, fail_args) = (
            "powershell",
            vec!["-NoProfile", "-Command", "Write-Output ('x' * 65536)"],
            vec!["-NoProfile", "-Command", "exit 7"],
        );
        #[cfg(not(target_os = "windows"))]
        let (prog, ok_args, fail_args) = (
            "sh",
            vec!["-c", "head -c 65536 /dev/zero | tr '\\0' 'x'"],
            vec!["-c", "exit 7"],
        );

        let out = run_command_with_timeout(prog, &ok_args, Duration::from_secs(5))
            .expect("large stdout should drain without pipe deadlock");
        assert!(out.trim().len() >= 65536);

        let err = run_command_with_timeout(prog, &fail_args, Duration::from_secs(5))
            .expect_err("non-zero exit should return Err");
        assert!(err.contains("7"));
    }
}
