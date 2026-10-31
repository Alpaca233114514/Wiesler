use serde_json::json;
use std::{io::Write, path::PathBuf, time::Duration};
use wiesler::{
    application::{parse_codex, parse_dsh},
    core::event::EventData,
    diagnostics,
    observer::{LogKind, ObserveOptions, observe},
    store,
};

fn call() -> serde_json::Value {
    json!({"schemaVersion":1,"kind":"call-end","callId":"call-1","sessionId":"session-1","status":"stop","provider":"test","model":"model","purpose":"conversation","usageAvailable":true,"inputTokens":100,"cacheReadTokens":20,"cacheWriteTokens":10,"outputTokens":100,"reasoningTokens":40,"decodeMs":2000,"elapsedMs":3000,"ttftMs":1000,"decodeTps":99999,"startedAt":1000,"endedAt":4000})
}
fn directory() -> PathBuf {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("target/application-tests")
        .join(format!(
            "{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
    std::fs::create_dir_all(&path).unwrap();
    path
}

#[test]
fn proxy_route_requires_matching_reverse_tcp_tuple() {
    use wiesler::{
        core::{connection::StateMachine, event::Stamp},
        sockets::SocketObservation,
    };
    let db = directory().join("routes.sqlite3");
    let mut writer = store::Store::open(&db, 100).unwrap();
    let mut state = StateMachine::new("route-test".into());
    let at = Stamp {
        utc_ms: 1000,
        monotonic_ms: 0,
    };
    writer.start(&state.start(at, 1000)).unwrap();
    let client = SocketObservation {
        process: "codex".into(),
        pid: 7,
        process_started_at: Some("2026-10-30T00:00:00Z".into()),
        local_ip: "127.0.0.1".into(),
        local_port: 50001,
        remote_ip: "127.0.0.1".into(),
        remote_port: 7897,
        state: "Established".into(),
        role: "codex".into(),
    };
    let proxy = SocketObservation {
        process: "verge-mihomo".into(),
        pid: 8,
        role: "proxy_process".into(),
        local_port: 7897,
        remote_port: 50001,
        ..client.clone()
    };
    let unrelated = SocketObservation {
        remote_port: 9000,
        local_port: 50002,
        ..client.clone()
    };
    let outer = SocketObservation {
        local_port: 60000,
        remote_ip: "203.0.113.1".into(),
        remote_port: 443,
        ..proxy.clone()
    };
    let mut event = state.finish(at, "test".into()).pop().unwrap();
    event.data = EventData::SocketsObserved {
        sockets: vec![client, proxy, unrelated, outer],
        sample_time_utc: Some(1000),
    };
    writer.append(&[event]).unwrap();
    let report = diagnostics::report(&db, &[]).unwrap();
    assert_eq!(report.codex_loopback_sockets.len(), 2);
    assert_eq!(report.codex_proxy_routes.len(), 1);
    assert_eq!(report.codex_proxy_routes[0].proxy_port, 7897);
    assert_eq!(report.proxy_egress_candidates.len(), 1);
    assert!(report.public_exit_ip.is_none());
}

#[tokio::test]
async fn truncated_log_creates_new_source_epoch() {
    let root = directory();
    let log = root.join("run-rotate.jsonl");
    let db = root.join("rotate.sqlite3");
    let mut before = call();
    before["callId"] = json!("before-rotation");
    std::fs::write(&log, format!("{}\n{}\n", before, before)).unwrap();
    let path = log.clone();
    let observed_db = db.clone();
    let writer = std::thread::spawn(move || {
        // Wait for durable evidence, not machine speed / 等待首条记录落库，不依赖机器速度。
        let deadline = std::time::Instant::now() + Duration::from_secs(10);
        loop {
            let mut observed = false;
            if let Ok(sessions) = store::sessions(&observed_db) {
                for session in sessions {
                    store::visit_events(&observed_db, &session.session_id, |event| {
                        if let EventData::ApplicationObserved { observation } = event.data {
                            observed |=
                                observation.request_id.as_deref() == Some("before-rotation");
                        }
                        Ok(())
                    })
                    .unwrap();
                }
            }
            if observed {
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "observer did not import the initial log"
            );
            std::thread::sleep(Duration::from_millis(10));
        }
        std::fs::write(path, format!("{}\n", call())).unwrap();
    });
    let session = observe(ObserveOptions {
        database: db.clone(),
        inputs: vec![(LogKind::Dsh, log)],
        codex_sockets: false,
        socket_inputs: vec![],
        from_end: false,
        interval: Duration::from_millis(100),
        polls: Some(20),
        max_events: 1000,
    })
    .await
    .unwrap();
    writer.join().unwrap();
    let mut epochs = vec![];
    store::visit_events(&db, &session, |event| {
        if matches!(event.data, EventData::SourceGap { ref reason, .. } if reason == "log_rotated_or_truncated") { epochs.push(event.source_epoch); }
        Ok(())
    }).unwrap();
    assert_eq!(epochs, vec![2]);
}

#[test]
fn dsh_metrics_use_authoritative_usage_without_double_counting_reasoning() {
    let row = parse_dsh(&call().to_string()).unwrap().unwrap();
    assert_eq!(row.decode_tps, Some(50.0));
    assert_eq!(row.context_tokens, Some(130));
    assert_eq!(row.output_tokens, Some(100));
    assert_eq!(row.reasoning_tokens, Some(40));
    assert_eq!(row.end_to_end_tps, Some(100.0 / 3.0));
}

#[test]
fn failed_missing_usage_empty_output_and_zero_duration_have_no_decode_speed() {
    for (key, value) in [
        ("status", json!("aborted")),
        ("usageAvailable", json!(false)),
        ("outputTokens", json!(null)),
        ("outputTokens", json!(0)),
        ("decodeMs", json!(0)),
        ("decodeMs", json!(-1)),
    ] {
        let mut row = call();
        row[key] = value;
        assert_eq!(
            parse_dsh(&row.to_string()).unwrap().unwrap().decode_tps,
            None
        );
    }
}

#[test]
fn dsh_import_keeps_counts_but_never_body_headers_or_errors() {
    let mut input = call();
    for key in [
        "prompt",
        "output",
        "reasoning",
        "headers",
        "errorMessage",
        "secret",
    ] {
        input[key] = json!("DO_NOT_PERSIST");
    }
    let row = parse_dsh(&input.to_string()).unwrap().unwrap();
    assert!(
        !serde_json::to_string(&row)
            .unwrap()
            .contains("DO_NOT_PERSIST")
    );
    input["schemaVersion"] = json!(2);
    assert!(parse_dsh(&input.to_string()).is_err());
}

#[test]
fn codex_desktop_network_and_rpc_are_not_model_ttft() {
    let network = parse_codex("2026-10-30T02:30:48.000Z info [orbit-messaging] Orbit messaging stage event=orbit.messaging.stage stage=host.network measurement=duration durationMs=730.6 requestId=req-1 outcome=success").unwrap().unwrap();
    assert_eq!(network.elapsed_ms, Some(730.6));
    assert_eq!(network.ttft_ms, None);
    assert_eq!(network.scope, "desktop_http_stage_not_model_ttft");
    let rpc = parse_codex("2026-10-30T02:30:48.000Z info [AppServerConnection] response_routed durationMs=20 errorCode=null method=thread/read requestId=req-2").unwrap().unwrap();
    assert_eq!(rpc.scope, "internal_rpc_not_model_latency");
    assert_eq!(rpc.decode_tps, None);
}

#[test]
fn codex_errors_strip_raw_message_and_reject_embedded_fake_fields() {
    let row = parse_codex("2026-10-30T02:30:48.000Z warning [electron-message-handler] sa_server_request_failed errorMessage=\"secret-value durationMs=999 httpStatus=200\" status=503 errorCode=HTTP_ERROR url=https://example.invalid/?token=secret-value").unwrap().unwrap();
    assert_eq!(row.http_status, Some(503));
    assert_eq!(row.elapsed_ms, None);
    assert!(
        !serde_json::to_string(&row)
            .unwrap()
            .contains("secret-value")
    );
    let ignored = json!({"type":"response_item","payload":{"type":"message","content":"Transport error secret-value"}});
    assert!(parse_codex(&ignored.to_string()).unwrap().is_none());
    let tokens = json!({"type":"event_msg","timestamp":"2026-10-30T02:30:48Z","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":100,"output_tokens":20,"reasoning_output_tokens":10}}}});
    let row = parse_codex(&tokens.to_string()).unwrap().unwrap();
    assert_eq!(row.output_tokens, Some(20));
    assert_eq!(row.decode_tps, None);
}

#[tokio::test]
async fn import_follow_and_diagnose_are_deduplicated_and_traceable() {
    let root = directory();
    let log = root.join("run-synthetic.jsonl");
    let db = root.join("observe.sqlite3");
    let mut start = call();
    start["kind"] = json!("call-start");
    std::fs::write(&log, format!("{}\n", start)).unwrap();
    let path = log.clone();
    let writer = std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(80));
        let value = call().to_string();
        let middle = value.len() / 2;
        let mut file = std::fs::OpenOptions::new()
            .append(true)
            .open(&path)
            .unwrap();
        file.write_all(&value.as_bytes()[..middle]).unwrap();
        file.flush().unwrap();
        std::thread::sleep(Duration::from_millis(80));
        writeln!(file, "{}", &value[middle..]).unwrap();
    });
    let options = || ObserveOptions {
        database: db.clone(),
        inputs: vec![(LogKind::Dsh, log.clone())],
        codex_sockets: false,
        socket_inputs: vec![],
        from_end: false,
        interval: Duration::from_millis(100),
        polls: Some(5),
        max_events: 1000,
    };
    let first = observe(options()).await.unwrap();
    writer.join().unwrap();
    let mut imported = vec![];
    store::visit_events(&db, &first, |e| {
        imported.push(e);
        Ok(())
    })
    .unwrap();
    assert_eq!(
        imported
            .iter()
            .filter(|e| matches!(e.data, EventData::ApplicationObserved { .. }))
            .count(),
        2
    );
    assert!(
        !imported
            .iter()
            .any(|e| matches!(e.data, EventData::SourceGap { .. }))
    );
    let report = diagnostics::report(&db, std::slice::from_ref(&first)).unwrap();
    assert_eq!(report.dsh[0].weighted_decode_tps, Some(50.0));
    assert_eq!(report.dsh_unfinished_calls, 0);
    let mut once = options();
    once.polls = Some(1);
    observe(once).await.unwrap();
    let combined = diagnostics::report(&db, &[]).unwrap();
    assert_eq!(combined.dsh[0].completed_attempts, 1);
    assert!(combined.markdown().contains("50.00"));
    assert_eq!(combined.public_exit_ip, None);
}

#[tokio::test]
async fn partial_lines_and_missing_sources_are_visible_as_gaps() {
    let root = directory();
    let log = root.join("run-partial.jsonl");
    let db = root.join("partial.sqlite3");
    std::fs::write(&log, "{\"schemaVersion\":1").unwrap();
    let session = observe(ObserveOptions {
        database: db.clone(),
        inputs: vec![
            (LogKind::Dsh, log),
            (LogKind::Codex, root.join("missing.log")),
        ],
        codex_sockets: false,
        socket_inputs: vec![],
        from_end: false,
        interval: Duration::from_millis(10),
        polls: Some(1),
        max_events: 1000,
    })
    .await
    .unwrap();
    let mut reasons = vec![];
    store::visit_events(&db, &session, |e| {
        if let EventData::SourceGap { reason, .. } = e.data {
            reasons.push(reason);
        }
        Ok(())
    })
    .unwrap();
    assert!(reasons.contains(&"incomplete_log_line_at_stop".into()));
    assert!(reasons.contains(&"log_source_unavailable".into()));
    assert_eq!(
        store::sessions(&db).unwrap()[0].status,
        "completed_with_gaps"
    );
}
