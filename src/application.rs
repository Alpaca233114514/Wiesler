use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ApplicationObservation {
    pub application: String,
    pub category: String,
    pub scope: String,
    pub file: String,
    pub line: u64,
    pub byte_offset: u64,
    pub source_time: Option<String>,
    pub app_session_id: Option<String>,
    pub request_id: Option<String>,
    pub provider: Option<String>,
    pub model: Option<String>,
    pub purpose: Option<String>,
    pub reasoning_effort: Option<String>,
    pub status: Option<String>,
    pub failure_code: Option<String>,
    pub http_status: Option<u16>,
    pub output_tokens: Option<u64>,
    pub reasoning_tokens: Option<u64>,
    pub context_tokens: Option<u64>,
    pub ttft_ms: Option<f64>,
    pub decode_ms: Option<f64>,
    pub elapsed_ms: Option<f64>,
    pub decode_tps: Option<f64>,
    pub end_to_end_tps: Option<f64>,
    pub gap_max_ms: Option<f64>,
    pub consumer_wait_ms: Option<f64>,
    pub active_at_start: Option<u64>,
    pub max_concurrent: Option<u64>,
    pub dropped_records: Option<u64>,
}

fn text(value: &Value, key: &str) -> Option<String> {
    value
        .get(key)?
        .as_str()
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
}
fn number(value: &Value, key: &str) -> Option<f64> {
    value
        .get(key)?
        .as_f64()
        .filter(|n| n.is_finite() && *n >= 0.0)
}
fn count(value: &Value, key: &str) -> Option<u64> {
    value.get(key)?.as_u64()
}

/// 移植 DSHObserver 的 usage/速度口径；来源见 THIRD_PARTY_NOTICES。 / Ported metric semantics; see THIRD_PARTY_NOTICES.
pub fn parse_dsh(line: &str) -> Result<Option<ApplicationObservation>, &'static str> {
    let row: Value = serde_json::from_str(line).map_err(|_| "invalid_dsh_json")?;
    if row.get("schemaVersion").and_then(Value::as_u64) != Some(1) {
        return Err("unsupported_dsh_schema");
    }
    let kind = row
        .get("kind")
        .and_then(Value::as_str)
        .ok_or("missing_dsh_kind")?;
    if ![
        "call-start",
        "call-end",
        "retry",
        "retry-started",
        "data-loss",
        "recorder-start",
        "recorder-stop",
    ]
    .contains(&kind)
    {
        return Ok(None);
    }
    if ["call-start", "call-end"].contains(&kind) && text(&row, "callId").is_none() {
        return Err("missing_dsh_call_id");
    }
    let mut observation = ApplicationObservation {
        application: "dsh".into(),
        category: kind.into(),
        scope: "model_provider_attempt".into(),
        source_time: count(&row, "endedAt")
            .or_else(|| count(&row, "startedAt"))
            .or_else(|| count(&row, "time"))
            .map(|v| v.to_string()),
        app_session_id: text(&row, "sessionId"),
        request_id: text(&row, "callId"),
        provider: text(&row, "provider"),
        model: text(&row, "model"),
        purpose: text(&row, "purpose"),
        reasoning_effort: text(&row, "reasoningEffort"),
        status: text(&row, "status"),
        failure_code: text(&row, "failureCode"),
        http_status: count(&row, "httpStatus")
            .and_then(|v| u16::try_from(v).ok())
            .filter(|v| (100..=599).contains(v)),
        output_tokens: count(&row, "outputTokens"),
        reasoning_tokens: count(&row, "reasoningTokens"),
        context_tokens: count(&row, "inputTokens")
            .and_then(|input| input.checked_add(count(&row, "cacheReadTokens").unwrap_or(0)))
            .and_then(|total| total.checked_add(count(&row, "cacheWriteTokens").unwrap_or(0))),
        ttft_ms: number(&row, "ttftMs"),
        decode_ms: number(&row, "decodeMs"),
        elapsed_ms: number(&row, "elapsedMs"),
        gap_max_ms: number(&row, "gapMaxMs"),
        consumer_wait_ms: number(&row, "consumerWaitMs"),
        active_at_start: count(&row, "activeAtStart"),
        max_concurrent: count(&row, "maxConcurrent"),
        dropped_records: count(&row, "dropped"),
        ..Default::default()
    };
    let success = matches!(
        observation.status.as_deref(),
        Some("stop" | "tool-calls" | "max-tokens")
    );
    if kind == "call-end"
        && success
        && row.get("usageAvailable").and_then(Value::as_bool) == Some(true)
        && let Some(tokens) = observation.output_tokens.filter(|n| *n > 0)
    {
        observation.decode_tps = observation
            .decode_ms
            .filter(|ms| *ms > 0.0)
            .map(|ms| tokens as f64 * 1000.0 / ms)
            .filter(|v| v.is_finite());
        observation.end_to_end_tps = observation
            .elapsed_ms
            .filter(|ms| *ms > 0.0)
            .map(|ms| tokens as f64 * 1000.0 / ms)
            .filter(|v| v.is_finite());
    }
    Ok(Some(observation))
}

// 仅解析顶层 key=value，避免错误正文里的伪字段污染指标。 / Parse top-level fields only, not fields embedded in error text.
fn fields(line: &str) -> std::collections::BTreeMap<&str, &str> {
    let mut fields = std::collections::BTreeMap::new();
    let bytes = line.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        let start = i;
        while i < bytes.len() && !bytes[i].is_ascii_whitespace() && bytes[i] != b'=' {
            i += 1;
        }
        if i == bytes.len() || bytes[i] != b'=' {
            continue;
        }
        let key = &line[start..i];
        i += 1;
        let value_start = i;
        if i < bytes.len() && bytes[i] == b'"' {
            i += 1;
            while i < bytes.len() {
                if bytes[i] == b'\\' {
                    i = (i + 2).min(bytes.len());
                } else if bytes[i] == b'"' {
                    i += 1;
                    break;
                } else {
                    i += 1;
                }
            }
        } else {
            while i < bytes.len() && !bytes[i].is_ascii_whitespace() {
                i += 1;
            }
        }
        fields.insert(key, &line[value_start..i]);
    }
    fields
}

fn identifier(value: Option<&&str>) -> Option<String> {
    let value = value?.trim_matches('"');
    if ["null", "undefined", ""].contains(&value)
        || value.len() > 200
        || !value
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "._:/-".contains(c))
    {
        return None;
    }
    Some(value.to_owned())
}

pub fn parse_codex(line: &str) -> Result<Option<ApplicationObservation>, &'static str> {
    if line.trim_start().starts_with('{') {
        return parse_codex_json(line);
    }
    let Some((time, rest)) = line.split_once(' ') else {
        return Ok(None);
    };
    if time.len() < 20 || !time.contains('T') || !time.ends_with('Z') {
        return Ok(None);
    }
    let fields = fields(rest);
    let mut row = ApplicationObservation {
        application: "codex".into(),
        source_time: Some(time.into()),
        request_id: identifier(fields.get("requestId"))
            .or_else(|| identifier(fields.get("httpRpcRequestId"))),
        app_session_id: identifier(fields.get("conversationId"))
            .or_else(|| identifier(fields.get("threadId"))),
        ..Default::default()
    };
    if fields.get("event") == Some(&"orbit.messaging.stage") {
        let stage = identifier(fields.get("stage")).ok_or("missing_codex_stage")?;
        if !["host.network", "host.request", "host.response_headers"].contains(&stage.as_str()) {
            return Ok(None);
        }
        row.category = stage;
        row.scope = "desktop_http_stage_not_model_ttft".into();
        row.elapsed_ms = fields
            .get("durationMs")
            .and_then(|v| v.parse::<f64>().ok())
            .filter(|v| v.is_finite() && *v >= 0.0);
        row.http_status = fields
            .get("httpStatus")
            .and_then(|v| v.parse().ok())
            .filter(|v| (100..=599).contains(v));
        row.status = identifier(fields.get("outcome"));
    } else if rest.starts_with("info [AppServerConnection] response_routed ") {
        row.category = "app_server_rpc".into();
        row.scope = "internal_rpc_not_model_latency".into();
        row.elapsed_ms = fields
            .get("durationMs")
            .and_then(|v| v.parse::<f64>().ok())
            .filter(|v| v.is_finite() && *v >= 0.0);
        row.failure_code = identifier(fields.get("errorCode")).filter(|v| v != "0");
    } else if rest.starts_with("warning [electron-message-handler] sa_server_request_failed ") {
        row.category = "http_error".into();
        row.scope = "desktop_http_request".into();
        row.failure_code = identifier(fields.get("errorCode"));
        row.http_status = fields
            .get("status")
            .and_then(|v| v.parse().ok())
            .filter(|v| (100..=599).contains(v));
        row.status = Some("failed".into());
    } else if rest
        .starts_with("warning [electron-message-handler] chatgpt_pubsub_transport_closed ")
    {
        row.category = "pubsub_transport_closed".into();
        row.scope = "desktop_pubsub_not_model_stream".into();
    } else if (rest.starts_with("error ")
        || rest.starts_with("warning ")
        || rest.starts_with("WARN ")
        || rest.starts_with("ERROR "))
        && (rest.contains("stream disconnected")
            || rest.contains("Transport error")
            || rest.contains("error sending request"))
    {
        row.category = "transport_error".into();
        row.scope = "application_log_unattributed_request".into();
        row.failure_code = Some(
            if rest.contains("stream disconnected") {
                "stream_disconnected"
            } else {
                "transport_error"
            }
            .into(),
        );
        row.status = Some("failed".into());
    } else {
        return Ok(None);
    }
    Ok(Some(row))
}

fn parse_codex_json(line: &str) -> Result<Option<ApplicationObservation>, &'static str> {
    let value: Value = serde_json::from_str(line).map_err(|_| "invalid_codex_json")?;
    // 只读事件包；绝不扫描 response_item 的正文。 / Read event envelopes only; never scan response_item content.
    if value.get("type").and_then(Value::as_str) != Some("event_msg") {
        return Ok(None);
    }
    let Some(payload) = value.get("payload") else {
        return Ok(None);
    };
    let kind = payload
        .get("type")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let mut row = ApplicationObservation {
        application: "codex".into(),
        category: kind.into(),
        scope: "application_turn_not_provider_attempt".into(),
        source_time: text(&value, "timestamp"),
        ..Default::default()
    };
    match kind {
        "token_count" => {
            let Some(usage) = payload.pointer("/info/last_token_usage") else {
                return Ok(None);
            };
            row.output_tokens = count(usage, "output_tokens");
            row.reasoning_tokens = count(usage, "reasoning_output_tokens");
            row.context_tokens = count(usage, "input_tokens");
        }
        "error" => {
            row.status = Some("failed".into());
            row.failure_code = text(payload, "code").filter(|c| {
                c.len() <= 100
                    && c.chars()
                        .all(|c| c.is_ascii_alphanumeric() || "_-".contains(c))
            });
        }
        "task_started" | "task_complete" | "turn_aborted" => {}
        _ => return Ok(None),
    }
    Ok(Some(row))
}
