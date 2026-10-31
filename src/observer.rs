use crate::{
    application::{parse_codex, parse_dsh},
    core::event::{Event, EventData, SCHEMA_VERSION, Stamp},
    sockets,
    store::Store,
};
use anyhow::{Result, bail};
use std::{
    collections::BTreeMap,
    fs::{self, File},
    io::{Read, Seek, SeekFrom},
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant, SystemTime},
};

const READ_BATCH: u64 = 4 * 1024 * 1024;
const MAX_LINE: usize = 1024 * 1024;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum LogKind {
    Dsh,
    Codex,
}

pub struct ObserveOptions {
    pub database: PathBuf,
    pub inputs: Vec<(LogKind, PathBuf)>,
    pub codex_sockets: bool,
    pub socket_inputs: Vec<PathBuf>,
    pub from_end: bool,
    pub interval: Duration,
    pub polls: Option<u64>,
    pub max_events: u64,
}

struct Tail {
    source: String,
    offset: u64,
    line: u64,
    epoch: u64,
    created: Option<SystemTime>,
    pending: Vec<u8>,
    discarding: bool,
}

struct Factory {
    session: String,
    sequence: u64,
    origin: Instant,
}

impl Factory {
    fn event(&mut self, source: &str, epoch: u64, data: EventData) -> Event {
        self.sequence += 1;
        let at = Stamp::now(self.origin);
        let timestamp = if let EventData::ApplicationObserved { observation } = &data {
            observation.source_time.clone()
        } else {
            None
        };
        Event {
            schema_version: SCHEMA_VERSION,
            event_id: format!("{}:{}", self.session, self.sequence),
            session_id: self.session.clone(),
            sequence: self.sequence,
            source_id: source.into(),
            source_epoch: epoch,
            observed_at_utc: at.utc_ms,
            monotonic_offset_ms: at.monotonic_ms,
            source_timestamp: timestamp,
            connection_key: None,
            evidence_ids: vec![],
            quality: vec!["read_only_observation".into()],
            data,
        }
    }

    fn gap(
        &mut self,
        source: &str,
        epoch: u64,
        reason: &str,
        file: Option<String>,
        line: Option<u64>,
    ) -> Event {
        self.event(
            source,
            epoch,
            EventData::SourceGap {
                reason: reason.into(),
                file,
                line,
            },
        )
    }
}

fn files(
    path: &Path,
    kind: LogKind,
    output: &mut Vec<PathBuf>,
    depth: usize,
) -> std::io::Result<()> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() {
        return Ok(());
    }
    if metadata.is_file() {
        output.push(path.to_owned());
        return Ok(());
    }
    if depth >= 6 {
        return Ok(());
    }
    for entry in fs::read_dir(path)? {
        let entry = entry?;
        let path = entry.path();
        let is_directory = entry.file_type()?.is_dir();
        let relevant = match kind {
            LogKind::Dsh => {
                path.extension().is_some_and(|v| v == "jsonl")
                    && path
                        .file_name()
                        .is_some_and(|v| v.to_string_lossy().starts_with("run-"))
            }
            LogKind::Codex => path.extension().is_some_and(|v| v == "log" || v == "jsonl"),
        };
        if is_directory || relevant {
            files(&path, kind, output, depth + 1)?;
        }
    }
    Ok(())
}

impl Tail {
    fn poll(&mut self, path: &Path, kind: LogKind, factory: &mut Factory) -> Result<Vec<Event>> {
        let mut file = File::open(path)?;
        let metadata = file.metadata()?;
        let created = metadata.created().ok();
        let name = path
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .into_owned();
        let mut events = vec![];
        if metadata.len() < self.offset || (self.created.is_some() && created != self.created) {
            self.epoch += 1;
            self.offset = 0;
            self.line = 0;
            self.pending.clear();
            self.discarding = false;
            events.push(factory.gap(
                &self.source,
                self.epoch,
                "log_rotated_or_truncated",
                Some(name.clone()),
                None,
            ));
        }
        self.created = created;
        file.seek(SeekFrom::Start(self.offset))?;
        let mut bytes = vec![];
        file.take(READ_BATCH).read_to_end(&mut bytes)?;
        self.offset += bytes.len() as u64;
        let base_offset = self.offset - bytes.len() as u64;
        for (offset, byte) in bytes.into_iter().enumerate() {
            if byte != b'\n' {
                if !self.discarding {
                    self.pending.push(byte);
                    if self.pending.len() > MAX_LINE {
                        self.pending.clear();
                        self.discarding = true;
                        events.push(factory.gap(
                            &self.source,
                            self.epoch,
                            "log_line_too_large",
                            Some(name.clone()),
                            Some(self.line + 1),
                        ));
                    }
                }
                continue;
            }
            self.line += 1;
            if self.discarding {
                self.discarding = false;
                continue;
            }
            let line = std::mem::take(&mut self.pending);
            if line.iter().all(u8::is_ascii_whitespace) {
                continue;
            }
            let parsed = std::str::from_utf8(&line)
                .map_err(|_| "invalid_log_utf8")
                .and_then(|line| match kind {
                    LogKind::Dsh => parse_dsh(line),
                    LogKind::Codex => parse_codex(line),
                });
            match parsed {
                Ok(Some(mut observation)) => {
                    observation.file = name.clone();
                    observation.line = self.line;
                    observation.byte_offset = base_offset + offset as u64 - line.len() as u64;
                    events.push(factory.event(
                        &self.source,
                        self.epoch,
                        EventData::ApplicationObserved {
                            observation: Box::new(observation),
                        },
                    ));
                }
                Ok(None) => {}
                Err(reason) => events.push(factory.gap(
                    &self.source,
                    self.epoch,
                    reason,
                    Some(name.clone()),
                    Some(self.line),
                )),
            }
        }
        Ok(events)
    }
}

pub async fn observe(options: ObserveOptions) -> Result<String> {
    if options.inputs.is_empty() && !options.codex_sockets && options.socket_inputs.is_empty() {
        bail!("至少选择一个 DSH/Codex 日志来源或 --codex-sockets");
    }
    if options.interval.is_zero() || options.polls == Some(0) || options.max_events == 0 {
        bail!("采样间隔、轮数和事件上限必须大于零");
    }
    let stop = Arc::new(AtomicBool::new(false));
    let stopped = stop.clone();
    let mut worker = tokio::task::spawn_blocking(move || run(options, stopped));
    tokio::select! {
        result = &mut worker => result?,
        signal = tokio::signal::ctrl_c() => {
            stop.store(true, Ordering::Relaxed);
            let result = worker.await?;
            signal?;
            result
        }
    }
}

fn run(options: ObserveOptions, stop: Arc<AtomicBool>) -> Result<String> {
    let mut store = Store::open(&options.database, options.max_events)?;
    let session = format!(
        "s-{}",
        rusqlite::Connection::open_in_memory()?.query_row(
            "SELECT lower(hex(randomblob(16)))",
            [],
            |row| row.get::<_, String>(0)
        )?
    );
    let mut factory = Factory {
        session: session.clone(),
        sequence: 0,
        origin: Instant::now(),
    };
    store.start(&factory.event(
        "observer",
        1,
        EventData::SessionStarted {
            interval_ms: options.interval.as_millis() as u64,
        },
    ))?;
    eprintln!("应用观测会话 {session}；Ctrl+C 停止并保存。");
    let result = (|| -> Result<()> {
        let mut tails: BTreeMap<PathBuf, Tail> = BTreeMap::new();
        let mut root_failed = vec![false; options.inputs.len()];
        let mut source_errors = false;
        let mut polls = 0_u64;
        loop {
            let mut batch = vec![];
            for (index, (kind, input)) in options.inputs.iter().enumerate() {
                let mut discovered = vec![];
                let result = files(input, *kind, &mut discovered, 0);
                if result.is_err() || discovered.is_empty() {
                    source_errors = true;
                    if !root_failed[index] {
                        batch.push(factory.gap(
                            &format!("input-{index}"),
                            1,
                            if result.is_err() {
                                "log_source_unavailable"
                            } else {
                                "log_source_empty"
                            },
                            None,
                            None,
                        ));
                        root_failed[index] = true;
                    }
                    continue;
                }
                if root_failed[index] {
                    batch.push(factory.gap(
                        &format!("input-{index}"),
                        1,
                        "log_source_recovered",
                        None,
                        None,
                    ));
                    root_failed[index] = false;
                }
                discovered.sort();
                discovered.dedup();
                for path in discovered {
                    let next = tails.len() + 1;
                    let tail = tails.entry(path.clone()).or_insert_with(|| Tail {
                        source: format!(
                            "{}-file-{next}",
                            if *kind == LogKind::Dsh {
                                "dsh"
                            } else {
                                "codex"
                            }
                        ),
                        offset: if options.from_end && polls == 0 {
                            fs::metadata(&path).map(|m| m.len()).unwrap_or(0)
                        } else {
                            0
                        },
                        line: 0,
                        epoch: 1,
                        created: fs::metadata(&path).ok().and_then(|m| m.created().ok()),
                        pending: vec![],
                        discarding: false,
                    });
                    match tail.poll(&path, *kind, &mut factory) {
                        Ok(events) => batch.extend(events),
                        Err(_) => batch.push(factory.gap(
                            &tail.source,
                            tail.epoch,
                            "log_read_failed",
                            path.file_name().map(|s| s.to_string_lossy().into_owned()),
                            None,
                        )),
                    }
                }
            }
            if options.codex_sockets {
                match sockets::collect() {
                    Ok(snapshot) => batch.push(factory.event(
                        "windows-tcp",
                        1,
                        EventData::SocketsObserved {
                            sockets: snapshot.sockets,
                            sample_time_utc: Some(snapshot.observed_at_utc),
                        },
                    )),
                    Err(error) => {
                        eprintln!("TCP 采集不可用：{error}");
                        batch.push(factory.gap(
                            "windows-tcp",
                            1,
                            "socket_probe_unavailable",
                            None,
                            None,
                        ));
                    }
                }
            }
            for path in &options.socket_inputs {
                match sockets::read_snapshot(path) {
                    Ok(snapshot) => batch.push(factory.event(
                        "imported-windows-tcp",
                        1,
                        EventData::SocketsObserved {
                            sockets: snapshot.sockets,
                            sample_time_utc: Some(snapshot.observed_at_utc),
                        },
                    )),
                    Err(_) => batch.push(factory.gap(
                        "imported-windows-tcp",
                        1,
                        "socket_snapshot_unavailable",
                        None,
                        None,
                    )),
                }
            }
            source_errors |= batch
                .iter()
                .any(|e| matches!(e.data, EventData::SourceGap { .. }));
            store.append(&batch)?;
            polls += 1;
            if stop.load(Ordering::Relaxed) || options.polls.is_some_and(|limit| polls >= limit) {
                break;
            }
            let deadline = Instant::now() + options.interval;
            while !stop.load(Ordering::Relaxed) && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(50).min(options.interval));
            }
        }
        let mut final_gaps = vec![];
        for (path, tail) in &tails {
            let reason = if fs::metadata(path).is_ok_and(|m| m.len() > tail.offset) {
                Some("unread_log_bytes_at_stop")
            } else if !tail.pending.is_empty() || tail.discarding {
                Some("incomplete_log_line_at_stop")
            } else {
                None
            };
            if let Some(reason) = reason {
                final_gaps.push(factory.gap(
                    &tail.source,
                    tail.epoch,
                    reason,
                    path.file_name().map(|v| v.to_string_lossy().into_owned()),
                    Some(tail.line + 1),
                ));
            }
        }
        source_errors |= !final_gaps.is_empty();
        store.append(&final_gaps)?;
        let reason = if source_errors {
            "completed_with_gaps"
        } else if stop.load(Ordering::Relaxed) {
            "stopped"
        } else {
            "completed"
        };
        let end = factory.event(
            "observer",
            1,
            EventData::ObservationEnded {
                reason: reason.into(),
            },
        );
        store.finish(
            std::slice::from_ref(&end),
            &session,
            end.observed_at_utc,
            reason,
        )?;
        Ok(())
    })();
    if result.is_err() {
        store.mark_incomplete(&session);
    }
    result?;
    Ok(session)
}
