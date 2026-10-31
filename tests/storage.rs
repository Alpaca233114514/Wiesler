use std::{
    collections::HashSet,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};
use wiesler::{
    core::{
        connection::StateMachine,
        event::{Event, EventData, Stamp},
    },
    probes::mihomo::parse_snapshot,
    store::{self, Replay, Store},
};

static NEXT: AtomicU64 = AtomicU64::new(0);

#[test]
fn retention_limit_includes_other_writer_sessions() {
    let path = database("two-writers");
    let mut first = Store::open(&path, 2).unwrap();
    let mut second = Store::open(&path, 2).unwrap();
    let mut one = StateMachine::new("one".into());
    let mut two = StateMachine::new("two".into());
    first.start(&one.start(stamp(0), 1000)).unwrap();
    second.start(&two.start(stamp(0), 1000)).unwrap();
    assert!(
        first
            .append(&one.finish(stamp(1), "completed".into()))
            .is_err()
    );
    assert_eq!(
        store::sessions(&path)
            .unwrap()
            .iter()
            .map(|s| s.event_count)
            .sum::<i64>(),
        2
    );
}
fn database(name: &str) -> PathBuf {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("target/test-data");
    std::fs::create_dir_all(&dir).unwrap();
    dir.join(format!(
        "{name}-{}-{}-{}.sqlite3",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ))
}
fn stamp(ms: u64) -> Stamp {
    Stamp {
        utc_ms: 10000 + ms,
        monotonic_ms: ms,
    }
}
fn sample(state: &mut StateMachine, ms: u64) -> Vec<Event> {
    state.snapshot(
        stamp(ms),
        parse_snapshot(include_bytes!("fixtures/snapshot.json")).unwrap(),
        "synthetic-v1".into(),
    )
}

#[test]
fn export_replay_and_reopen_preserve_sessions_and_evidence() {
    let path = database("roundtrip");
    let mut store = Store::open(&path, 1000).unwrap();
    for id in ["one", "two"] {
        let mut state = StateMachine::new(id.into());
        store.start(&state.start(stamp(0), 1000)).unwrap();
        store.append(&sample(&mut state, 1)).unwrap();
        store.append(&sample(&mut state, 1001)).unwrap();
        store
            .finish(
                &state.finish(stamp(2000), "completed".into()),
                id,
                12000,
                "completed",
            )
            .unwrap();
    }
    drop(store);
    let sessions = store::sessions(&path).unwrap();
    assert_eq!(sessions.len(), 2);
    assert!(sessions.iter().all(|s| s.status == "completed"));
    let mut output = Vec::new();
    store::export(&path, "one", &mut output).unwrap();
    let mut known = HashSet::new();
    let mut from_export = Replay::default();
    for line in String::from_utf8(output).unwrap().lines() {
        let event: Event = serde_json::from_str(line).unwrap();
        assert_eq!(event.session_id, "one");
        assert!(event.evidence_ids.iter().all(|id| known.contains(id)));
        known.insert(event.event_id.clone());
        from_export.apply(event).unwrap();
    }
    let replay = store::replay(&path, "one").unwrap();
    assert_eq!(
        serde_json::to_value(replay).unwrap(),
        serde_json::to_value(from_export).unwrap()
    );
    let replay = store::replay(&path, "two").unwrap();
    assert_eq!(replay.connections.len(), 1);
    assert_eq!(
        replay.connections.values().next().unwrap().status,
        "observation_ended"
    );
    assert!(store::replay(&path, "missing").is_err());
}

#[test]
fn duplicate_batch_rolls_back_every_event_and_preserves_prior_evidence() {
    let path = database("transaction");
    let mut store = Store::open(&path, 1000).unwrap();
    let mut state = StateMachine::new("one".into());
    store.start(&state.start(stamp(0), 1000)).unwrap();
    let events = sample(&mut state, 1);
    let mut invalid = events.clone();
    invalid.push(events[0].clone());
    assert!(store.append(&invalid).is_err());
    assert_eq!(store::sessions(&path).unwrap()[0].event_count, 1);
    store.append(&events).unwrap();
    assert_eq!(
        store::sessions(&path).unwrap()[0].event_count,
        1 + events.len() as i64
    );
}

#[test]
fn interrupted_session_stays_readable_and_new_session_is_independent() {
    let path = database("interrupted");
    {
        let mut store = Store::open(&path, 1000).unwrap();
        let mut state = StateMachine::new("old".into());
        store.start(&state.start(stamp(0), 1000)).unwrap();
        store.append(&sample(&mut state, 1)).unwrap();
    }
    let mut store = Store::open(&path, 1000).unwrap();
    let mut state = StateMachine::new("new".into());
    store.start(&state.start(stamp(0), 1000)).unwrap();
    store.append(&sample(&mut state, 1)).unwrap();
    let old = store::replay(&path, "old").unwrap();
    let new = store::replay(&path, "new").unwrap();
    assert_ne!(old.connections.keys().next(), new.connections.keys().next());
    assert!(
        store::sessions(&path)
            .unwrap()
            .iter()
            .all(|s| s.ended_at_utc.is_none())
    );
}

#[test]
fn unknown_schema_and_unrelated_database_are_not_rebuilt() {
    for future in [false, true] {
        let path = database("schema");
        let db = rusqlite::Connection::open(&path).unwrap();
        db.execute_batch(
            "CREATE TABLE valuable (content TEXT); INSERT INTO valuable VALUES ('keep me');",
        )
        .unwrap();
        if future {
            db.pragma_update(None, "user_version", 99).unwrap();
        }
        drop(db);
        assert!(Store::open(&path, 1000).is_err());
        let db = rusqlite::Connection::open(&path).unwrap();
        assert_eq!(
            db.query_row("SELECT content FROM valuable", [], |row| row
                .get::<_, String>(0))
                .unwrap(),
            "keep me"
        );
        let count: i32 = db
            .query_row(
                "SELECT count(*) FROM sqlite_master WHERE name='sessions'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(count, 0);
    }
}

#[test]
fn retention_limit_stops_without_deletion_or_partial_snapshot() {
    let path = database("limit");
    let mut store = Store::open(&path, 2).unwrap();
    let mut state = StateMachine::new("one".into());
    store.start(&state.start(stamp(0), 1000)).unwrap();
    assert!(store.append(&sample(&mut state, 1)).is_err());
    store.mark_incomplete("one");
    let sessions = store::sessions(&path).unwrap();
    assert_eq!(sessions[0].event_count, 1);
    assert_eq!(sessions[0].status, "incomplete");
}

#[test]
fn recovered_projection_keeps_old_connection_as_unknown_end() {
    let mut state = StateMachine::new("one".into());
    let mut replay = Replay::default();
    for event in sample(&mut state, 0) {
        replay.apply(event).unwrap();
    }
    for event in state.unavailable(stamp(1), "timeout".into()) {
        replay.apply(event).unwrap();
    }
    assert_eq!(replay.connections.values().next().unwrap().status, "stale");
    for event in sample(&mut state, 2) {
        replay.apply(event).unwrap();
    }
    assert_eq!(replay.connections.len(), 2);
    assert!(
        replay
            .connections
            .values()
            .any(|v| v.status == "observation_lost")
    );
    assert!(replay.connections.values().any(|v| v.status == "observed"));
    assert!(matches!(
        replay.latest_snapshot,
        Some(EventData::SnapshotObserved {
            upload_delta: None,
            ..
        })
    ));
}
