use crate::core::event::{Connection as ObservedConnection, Event, EventData, SCHEMA_VERSION};
use anyhow::{Context, Result, bail};
use rusqlite::{Connection, OpenFlags, TransactionBehavior, params};
use serde::Serialize;
use std::{collections::BTreeMap, io::Write, path::Path, time::Duration};

const APPLICATION_ID: i32 = 0x5749534c;

pub struct Store {
    db: Connection,
    max_events: u64,
}

#[derive(Debug, Serialize)]
pub struct Session {
    pub session_id: String,
    pub started_at_utc: i64,
    pub ended_at_utc: Option<i64>,
    pub status: String,
    pub event_count: i64,
}

fn validate(db: &Connection) -> Result<()> {
    let version: u32 = db.pragma_query_value(None, "user_version", |row| row.get(0))?;
    let app: i32 = db.pragma_query_value(None, "application_id", |row| row.get(0))?;
    if version != SCHEMA_VERSION || app != APPLICATION_ID {
        bail!("不是受支持的 Wiesler 数据库；保留原库，不自动迁移或重建");
    }
    Ok(())
}

fn read_only(path: &Path) -> Result<Connection> {
    let db = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    db.busy_timeout(Duration::from_secs(3))?;
    validate(&db)?;
    Ok(db)
}

impl Store {
    pub fn open(path: &Path, max_events: u64) -> Result<Self> {
        let mut db = Connection::open(path).context("打开 SQLite 失败")?;
        db.busy_timeout(Duration::from_secs(3))?;
        let version: u32 = db.pragma_query_value(None, "user_version", |row| row.get(0))?;
        let app: i32 = db.pragma_query_value(None, "application_id", |row| row.get(0))?;
        let tables: i64 = db.query_row(
            "SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'",
            [],
            |row| row.get(0),
        )?;
        if version == 0 && app == 0 && tables == 0 {
            let tx = db.transaction()?;
            tx.execute_batch("CREATE TABLE sessions (
                session_id TEXT PRIMARY KEY, started_at_utc INTEGER NOT NULL,
                ended_at_utc INTEGER, status TEXT NOT NULL, app_version TEXT NOT NULL);
                CREATE TABLE events (
                event_id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(session_id),
                sequence INTEGER NOT NULL, observed_at_utc INTEGER NOT NULL, source_id TEXT NOT NULL,
                connection_key TEXT, json TEXT NOT NULL, UNIQUE(session_id, sequence));
                CREATE INDEX events_time ON events(observed_at_utc);
                CREATE INDEX events_connection ON events(connection_key);
                CREATE INDEX events_source ON events(source_id, observed_at_utc);")?;
            tx.pragma_update(None, "user_version", SCHEMA_VERSION)?;
            tx.pragma_update(None, "application_id", APPLICATION_ID)?;
            tx.commit()?;
        }
        validate(&db)?;
        db.pragma_update(None, "journal_mode", "WAL")?;
        db.pragma_update(None, "synchronous", "FULL")?;
        db.pragma_update(None, "foreign_keys", true)?;
        let count = db.query_row("SELECT count(*) FROM events", [], |row| {
            row.get::<_, i64>(0)
        })? as u64;
        if count >= max_events {
            bail!("已达到事件保留上限；原有数据保留，请选择新数据库或显式提高上限");
        }
        Ok(Self { db, max_events })
    }

    pub fn start(&mut self, event: &Event) -> Result<()> {
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        check_limit(&tx, self.max_events, 1)?;
        tx.execute(
            "INSERT INTO sessions VALUES (?1, ?2, NULL, 'recording_or_interrupted', ?3)",
            params![
                event.session_id,
                i64::try_from(event.observed_at_utc)?,
                env!("CARGO_PKG_VERSION")
            ],
        )?;
        insert(&tx, event)?;
        tx.commit()?;
        Ok(())
    }

    pub fn append(&mut self, events: &[Event]) -> Result<()> {
        if events.is_empty() {
            return Ok(());
        }
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        check_limit(&tx, self.max_events, events.len())?;
        for event in events {
            insert(&tx, event)?;
        }
        tx.commit()?;
        Ok(())
    }

    pub fn finish(&mut self, events: &[Event], session: &str, at: u64, status: &str) -> Result<()> {
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        check_limit(&tx, self.max_events, events.len())?;
        for event in events {
            insert(&tx, event)?;
        }
        tx.execute(
            "UPDATE sessions SET ended_at_utc=?1, status=?2 WHERE session_id=?3",
            params![i64::try_from(at)?, status, session],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn mark_incomplete(&self, session: &str) {
        // 磁盘故障可能也阻止此更新；未结束状态仍保留。 / Disk failure may prevent this update; unfinished status remains.
        let _ = self.db.execute(
            "UPDATE sessions SET status='incomplete' WHERE session_id=?1",
            [session],
        );
    }
}

fn check_limit(db: &Connection, limit: u64, added: usize) -> Result<()> {
    let count = db.query_row("SELECT count(*) FROM events", [], |row| {
        row.get::<_, i64>(0)
    })? as u64;
    if count.saturating_add(added as u64) > limit {
        bail!("已达到事件保留上限，停止采集；没有删除既有事件");
    }
    Ok(())
}

fn insert(db: &Connection, event: &Event) -> Result<()> {
    db.execute(
        "INSERT INTO events VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![
            event.event_id,
            event.session_id,
            i64::try_from(event.sequence)?,
            i64::try_from(event.observed_at_utc)?,
            event.source_id,
            event.connection_key,
            serde_json::to_string(event)?
        ],
    )?;
    Ok(())
}

pub fn sessions(path: &Path) -> Result<Vec<Session>> {
    let db = read_only(path)?;
    let mut stmt = db.prepare("SELECT s.session_id, s.started_at_utc, s.ended_at_utc, s.status, count(e.event_id)
        FROM sessions s LEFT JOIN events e ON s.session_id=e.session_id GROUP BY s.session_id ORDER BY s.started_at_utc, s.session_id")?;
    let sessions = stmt
        .query_map([], |row| {
            Ok(Session {
                session_id: row.get(0)?,
                started_at_utc: row.get(1)?,
                ended_at_utc: row.get(2)?,
                status: row.get(3)?,
                event_count: row.get(4)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(sessions)
}

pub fn visit_events(
    path: &Path,
    session: &str,
    mut visit: impl FnMut(Event) -> Result<()>,
) -> Result<()> {
    let mut db = read_only(path)?;
    let tx = db.transaction()?;
    let exists: bool = tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM sessions WHERE session_id=?1)",
        [session],
        |row| row.get(0),
    )?;
    if !exists {
        bail!("未找到指定会话");
    }
    {
        let mut stmt =
            tx.prepare("SELECT json FROM events WHERE session_id=?1 ORDER BY sequence")?;
        let mut rows = stmt.query([session])?;
        while let Some(row) = rows.next()? {
            let json: String = row.get(0)?;
            let event: Event = serde_json::from_str(&json)?;
            if event.schema_version != SCHEMA_VERSION {
                bail!("事件格式版本不受支持");
            }
            visit(event)?;
        }
    }
    tx.commit()?;
    Ok(())
}

pub fn export(path: &Path, session: &str, output: &mut impl Write) -> Result<()> {
    visit_events(path, session, |event| {
        serde_json::to_writer(&mut *output, &event)?;
        output.write_all(b"\n")?;
        Ok(())
    })?;
    output.flush()?;
    Ok(())
}

#[derive(Serialize)]
pub struct ConnectionView {
    pub connection: ObservedConnection,
    pub status: String,
    pub last_event_id: String,
}

#[derive(Default, Serialize)]
pub struct Replay {
    pub session_id: String,
    pub events: u64,
    pub controller_version: Option<String>,
    pub health: String,
    pub latest_snapshot_event_id: Option<String>,
    pub latest_snapshot: Option<EventData>,
    pub connections: BTreeMap<String, ConnectionView>,
    pub gap_event_ids: Vec<String>,
}

impl Replay {
    pub fn apply(&mut self, event: Event) -> Result<()> {
        if event.schema_version != SCHEMA_VERSION {
            bail!("事件格式版本不受支持");
        }
        if self.session_id.is_empty() {
            self.session_id = event.session_id.clone();
        }
        if self.session_id != event.session_id {
            bail!("回放禁止混合不同会话");
        }
        self.events += 1;
        match event.data {
            EventData::ApplicationObserved { .. }
            | EventData::SocketsObserved { .. }
            | EventData::SourceGap { .. } => {}
            EventData::SessionStarted { .. } => self.health = "unknown".into(),
            EventData::ControllerVersion { version } => self.controller_version = Some(version),
            EventData::ConnectionObserved { connection }
            | EventData::ConnectionUpdated { connection, .. } => {
                let key = event
                    .connection_key
                    .context("连接事件缺少 connection_key")?;
                self.connections.insert(
                    key,
                    ConnectionView {
                        connection,
                        status: "observed".into(),
                        last_event_id: event.event_id,
                    },
                );
            }
            EventData::ConnectionDisappeared { .. } => {
                if let Some(view) = event
                    .connection_key
                    .and_then(|key| self.connections.get_mut(&key))
                {
                    view.status = "disappeared_reason_unknown".into();
                    view.last_event_id = event.event_id;
                }
            }
            EventData::ControllerUnavailable { .. } => {
                self.health = "unavailable".into();
                self.expire_observed("stale");
            }
            EventData::ObservationGap { .. } => {
                self.gap_event_ids.push(event.event_id);
                self.expire_observed("observation_lost");
            }
            EventData::CounterReset { .. } => {
                if let Some(key) = event.connection_key {
                    if let Some(view) = self.connections.get_mut(&key) {
                        view.status = "identity_or_counter_reset".into();
                    }
                } else {
                    self.expire_observed("counter_epoch_ended");
                }
            }
            data @ EventData::SnapshotObserved { .. } => {
                self.health = "available".into();
                self.latest_snapshot_event_id = Some(event.event_id);
                self.latest_snapshot = Some(data);
            }
            EventData::ControllerRecovered => self.health = "available".into(),
            EventData::ObservationEnded { .. } => {
                self.expire_observed("observation_ended");
            }
        }
        Ok(())
    }

    fn expire_observed(&mut self, status: &str) {
        for view in self
            .connections
            .values_mut()
            .filter(|v| v.status == "observed" || v.status == "stale")
        {
            view.status = status.into();
        }
    }
}

pub fn replay(path: &Path, session: &str) -> Result<Replay> {
    let mut replay = Replay::default();
    visit_events(path, session, |event| replay.apply(event))?;
    Ok(replay)
}
