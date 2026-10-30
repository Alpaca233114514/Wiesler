use std::{collections::HashSet, time::Duration};
use wiesler::{
    config::ControllerConfig,
    core::{
        connection::StateMachine,
        event::{Event, EventData, Snapshot, Stamp},
    },
    probes::mihomo::parse_snapshot,
};

const FIXTURE: &[u8] = include_bytes!("fixtures/snapshot.json");
fn stamp(ms: u64) -> Stamp {
    Stamp {
        utc_ms: 10000 + ms,
        monotonic_ms: ms,
    }
}
fn snapshot() -> Snapshot {
    parse_snapshot(FIXTURE).unwrap()
}
fn empty() -> Snapshot {
    Snapshot {
        connections: vec![],
        upload_total: 100,
        download_total: 200,
    }
}
fn observed(events: &[Event]) -> &Event {
    events
        .iter()
        .find(|e| matches!(e.data, EventData::ConnectionObserved { .. }))
        .unwrap()
}

#[test]
fn controller_configuration_enforces_loopback_and_hides_secret() {
    for url in ["http://127.0.0.1:9090", "http://[::1]:9090"] {
        assert!(ControllerConfig::new(url, Some("secret"), Duration::from_secs(1)).is_ok());
    }
    for url in [
        "http://localhost:9090",
        "http://192.168.1.1",
        "https://127.0.0.1",
        "http://u:secret@127.0.0.1",
        "http://127.0.0.1?secret=value",
        "http://127.0.0.1/#secret",
        "http://127.0.0.1/api",
    ] {
        let error = ControllerConfig::new(url, None, Duration::from_secs(1))
            .err()
            .unwrap()
            .to_string();
        assert!(!error.contains("secret"));
    }
    assert!(
        ControllerConfig::new(
            "http://127.0.0.1",
            Some("bad\r\nsecret"),
            Duration::from_secs(1)
        )
        .is_err()
    );
}

#[test]
fn parses_mihomo_ports_optional_metadata_and_filters_private_fields() {
    let s = snapshot();
    let c = &s.connections[0];
    assert_eq!(c.metadata.destination_port, Some(443));
    assert_eq!(c.metadata.pid, None); // UID 不是 PID。 / UID is not PID.
    assert_eq!(c.chains, ["TEST-NODE", "TEST-GROUP"]);
    let encoded = serde_json::to_string(c).unwrap();
    assert!(!encoded.contains("private"));
    let minimal = br#"{"connections":[{"id":"a","upload":0,"download":0,"metadata":{}}],"uploadTotal":0,"downloadTotal":0}"#;
    assert!(
        parse_snapshot(minimal).unwrap().connections[0]
            .metadata
            .host
            .is_none()
    );
}

#[test]
fn accepts_explicit_null_and_empty_connections_but_rejects_missing_and_malformed() {
    for list in ["null", "[]"] {
        assert!(
            parse_snapshot(
                format!(r#"{{"connections":{list},"uploadTotal":0,"downloadTotal":0}}"#).as_bytes()
            )
            .unwrap()
            .connections
            .is_empty()
        );
    }
    for data in [
        r#"{"uploadTotal":0,"downloadTotal":0}"#,
        r#"{"connections":{},"uploadTotal":0,"downloadTotal":0}"#,
        r#"{"connections":[],"uploadTotal":-1,"downloadTotal":0}"#,
        "{",
    ] {
        assert!(parse_snapshot(data.as_bytes()).is_err());
    }
    let base: serde_json::Value = serde_json::from_slice(FIXTURE).unwrap();
    for (key, value) in [
        ("id", serde_json::json!(42)),
        ("upload", serde_json::json!("10")),
        ("id", serde_json::json!("")),
    ] {
        let mut bad = base.clone();
        bad["connections"][0][key] = value;
        assert!(parse_snapshot(&serde_json::to_vec(&bad).unwrap()).is_err());
    }
    let mut duplicate = base;
    let c = duplicate["connections"][0].clone();
    duplicate["connections"].as_array_mut().unwrap().push(c);
    assert!(parse_snapshot(&serde_json::to_vec(&duplicate).unwrap()).is_err());
}

#[test]
fn continuous_snapshots_compute_bytes_and_disappearance_with_evidence() {
    let mut state = StateMachine::new("test".into());
    let first = state.snapshot(stamp(0), snapshot(), "v1".into());
    let first_key = observed(&first).connection_key.clone();
    let mut next = snapshot();
    next.connections[0].download += 30;
    next.download_total += 30;
    let mut later = stamp(1500);
    later.utc_ms = 9000;
    let second = state.snapshot(later, next, "v1".into());
    assert!(second.iter().any(|e| matches!(
        e.data,
        EventData::ConnectionUpdated {
            download_delta: 30,
            interval_ms: 1500,
            ..
        }
    ) && e.connection_key == first_key));
    let mut last = empty();
    last.download_total += 30;
    let third = state.snapshot(stamp(2500), last, "v1".into());
    assert!(third.iter().any(|e| matches!(
        e.data,
        EventData::ConnectionDisappeared {
            last_seen_monotonic_ms: 1500
        }
    )));
    let all: Vec<_> = first.into_iter().chain(second).chain(third).collect();
    let mut known = HashSet::new();
    for event in all {
        assert!(event.evidence_ids.iter().all(|id| known.contains(id)));
        known.insert(event.event_id);
    }
}

#[test]
fn outage_recovery_rebuilds_epoch_without_inventing_closes_or_deltas() {
    let mut state = StateMachine::new("test".into());
    let first = state.snapshot(stamp(0), snapshot(), "v1".into());
    assert_eq!(state.unavailable(stamp(1000), "timeout".into()).len(), 1);
    assert!(
        state
            .unavailable(stamp(2000), "invalid_snapshot".into())
            .is_empty()
    );
    let recovery = state.snapshot(stamp(3000), snapshot(), "v1".into());
    assert_ne!(
        observed(&first).connection_key,
        observed(&recovery).connection_key
    );
    assert_eq!(observed(&recovery).source_epoch, 2);
    assert!(recovery.iter().any(|e| matches!(
        e.data,
        EventData::ObservationGap {
            failures: 2,
            from_monotonic_ms: 0,
            to_monotonic_ms: 3000,
            ..
        }
    )));
    assert!(!recovery.iter().any(|e| matches!(
        e.data,
        EventData::ConnectionDisappeared { .. } | EventData::ConnectionUpdated { .. }
    )));
    assert!(recovery.iter().any(|e| matches!(
        e.data,
        EventData::SnapshotObserved {
            upload_delta: None,
            ..
        }
    )));
}

#[test]
fn queue_loss_rebuilds_baseline_without_reporting_controller_failure() {
    let mut state = StateMachine::new("test".into());
    state.snapshot(stamp(0), snapshot(), "v1".into());
    state.dropped(stamp(3000), 1000, 2);
    let recovered = state.snapshot(stamp(3000), empty(), "v1".into());
    assert_eq!(
        recovered
            .iter()
            .filter(|e| matches!(
                e.data,
                EventData::ObservationGap {
                    dropped_samples: 2,
                    ..
                }
            ))
            .count(),
        1
    );
    assert!(!recovered.iter().any(|e| matches!(
        e.data,
        EventData::ControllerUnavailable { .. }
            | EventData::ControllerRecovered
            | EventData::ConnectionDisappeared { .. }
    )));
}

#[test]
fn counter_and_identity_changes_break_connection_lifetime() {
    for change in 0..3 {
        let mut state = StateMachine::new("test".into());
        let first = state.snapshot(stamp(0), snapshot(), "v1".into());
        let mut next = snapshot();
        match change {
            0 => next.connections[0].download = 1,
            1 => next.connections[0].start = Some("new-start".into()),
            _ => next.connections[0].metadata.source_port = Some(2222),
        }
        let events = state.snapshot(stamp(1000), next, "v1".into());
        assert!(
            events
                .iter()
                .any(|e| matches!(e.data, EventData::CounterReset { .. }))
        );
        assert_ne!(
            observed(&first).connection_key,
            observed(&events).connection_key
        );
        assert!(
            !events
                .iter()
                .any(|e| matches!(e.data, EventData::ConnectionUpdated { .. }))
        );
    }
}

#[test]
fn global_reset_and_version_change_start_new_epoch() {
    for change in 0..2 {
        let mut state = StateMachine::new("test".into());
        state.snapshot(stamp(0), snapshot(), "v1".into());
        let mut next = snapshot();
        if change == 0 {
            next.download_total = 1;
        }
        let events = state.snapshot(
            stamp(1000),
            next,
            if change == 0 { "v1" } else { "v2" }.into(),
        );
        assert_eq!(observed(&events).source_epoch, 2);
        assert!(events.iter().any(|e| matches!(
            e.data,
            EventData::SnapshotObserved {
                download_delta: None,
                ..
            }
        )));
    }
}

#[test]
fn session_restart_and_id_reappearance_never_reuse_keys() {
    let mut state = StateMachine::new("a".into());
    let first = state.snapshot(stamp(0), snapshot(), "v1".into());
    state.snapshot(stamp(1), empty(), "v1".into());
    let second = state.snapshot(stamp(2), snapshot(), "v1".into());
    let third = StateMachine::new("b".into()).snapshot(stamp(0), snapshot(), "v1".into());
    assert_ne!(
        observed(&first).connection_key,
        observed(&second).connection_key
    );
    assert_ne!(
        observed(&first).connection_key,
        observed(&third).connection_key
    );
    let ended = state.finish(stamp(3), "stopped".into());
    assert!(
        !ended
            .iter()
            .any(|e| matches!(e.data, EventData::ConnectionDisappeared { .. }))
    );
}
