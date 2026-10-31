use super::event::{Connection, Event, EventData, SCHEMA_VERSION, Snapshot, Stamp};
use std::collections::BTreeMap;

struct Seen {
    connection: Connection,
    key: String,
    evidence: String,
}

struct Gap {
    from: u64,
    failures: u64,
    dropped: u64,
    evidence: String,
    controller_failed: bool,
}

pub struct StateMachine {
    session: String,
    sequence: u64,
    epoch: u64,
    previous: BTreeMap<String, Seen>,
    last: Option<(Stamp, u64, u64, String)>,
    gap: Option<Gap>,
    version: Option<String>,
}

impl StateMachine {
    pub fn new(session: String) -> Self {
        Self {
            session,
            sequence: 0,
            epoch: 1,
            previous: BTreeMap::new(),
            last: None,
            gap: None,
            version: None,
        }
    }

    fn event(
        &mut self,
        at: Stamp,
        key: Option<String>,
        evidence_ids: Vec<String>,
        data: EventData,
    ) -> Event {
        self.sequence += 1;
        let source_timestamp = match &data {
            EventData::ConnectionObserved { connection }
            | EventData::ConnectionUpdated { connection, .. } => connection.start.clone(),
            _ => None,
        };
        let mut quality = vec!["sampled_snapshot".into()];
        if let EventData::ConnectionObserved { connection }
        | EventData::ConnectionUpdated { connection, .. } = &data
        {
            if connection.metadata.process.is_none() {
                quality.push("process_unknown".into());
            }
            if connection.metadata.pid.is_none() {
                quality.push("pid_unknown".into());
            }
            if connection.start.is_none() {
                quality.push("source_start_unknown".into());
            }
            let m = &connection.metadata;
            if m.network.is_none()
                || m.source_ip.is_none()
                || m.source_port.is_none()
                || m.destination_port.is_none()
                || (m.destination_ip.is_none() && m.host.is_none())
            {
                quality.push("tuple_incomplete".into());
            }
        }
        Event {
            schema_version: SCHEMA_VERSION,
            event_id: format!("{}:{}", self.session, self.sequence),
            session_id: self.session.clone(),
            sequence: self.sequence,
            source_id: "mihomo".into(),
            source_epoch: self.epoch,
            observed_at_utc: at.utc_ms,
            monotonic_offset_ms: at.monotonic_ms,
            source_timestamp,
            connection_key: key,
            evidence_ids,
            quality,
            data,
        }
    }

    pub fn start(&mut self, at: Stamp, interval_ms: u64) -> Event {
        self.event(at, None, vec![], EventData::SessionStarted { interval_ms })
    }

    pub fn unavailable(&mut self, at: Stamp, reason: String) -> Vec<Event> {
        if let Some(gap) = &mut self.gap {
            gap.failures += 1;
            if gap.controller_failed {
                return vec![];
            }
            gap.controller_failed = true;
            return vec![self.event(
                at,
                None,
                vec![],
                EventData::ControllerUnavailable { reason },
            )];
        }
        let event = self.event(
            at,
            None,
            vec![],
            EventData::ControllerUnavailable { reason },
        );
        self.gap = Some(Gap {
            from: self
                .last
                .as_ref()
                .map_or(at.monotonic_ms, |last| last.0.monotonic_ms),
            failures: 1,
            dropped: 0,
            evidence: event.event_id.clone(),
            controller_failed: true,
        });
        vec![event]
    }

    pub fn dropped(&mut self, _at: Stamp, from: u64, count: u64) -> Vec<Event> {
        if let Some(gap) = &mut self.gap {
            gap.from = gap.from.min(from);
            gap.dropped += count;
            return vec![];
        }
        self.gap = Some(Gap {
            from: self
                .last
                .as_ref()
                .map_or(from, |last| last.0.monotonic_ms.min(from)),
            failures: 0,
            dropped: count,
            evidence: self
                .last
                .as_ref()
                .map(|last| last.3.clone())
                .unwrap_or_default(),
            controller_failed: false,
        });
        vec![]
    }

    fn close_gap(&mut self, at: Stamp) -> Vec<Event> {
        let Some(gap) = self.gap.take() else {
            return vec![];
        };
        vec![self.event(
            at,
            None,
            if gap.evidence.is_empty() {
                vec![]
            } else {
                vec![gap.evidence]
            },
            EventData::ObservationGap {
                from_monotonic_ms: gap.from,
                to_monotonic_ms: at.monotonic_ms,
                failures: gap.failures,
                dropped_samples: gap.dropped,
            },
        )]
    }

    pub fn snapshot(&mut self, at: Stamp, snapshot: Snapshot, version: String) -> Vec<Event> {
        let mut events = vec![];
        if self.gap.is_some() {
            let controller_failed = self.gap.as_ref().is_some_and(|gap| gap.controller_failed);
            events.extend(self.close_gap(at));
            self.epoch += 1;
            self.previous.clear();
            self.last = None;
            if controller_failed {
                let refs = events.iter().map(|e| e.event_id.clone()).collect();
                events.push(self.event(at, None, refs, EventData::ControllerRecovered));
            }
        }
        let version_changed = self.version.as_ref().is_some_and(|old| *old != version);
        let totals_reset = self.last.as_ref().is_some_and(|(_, up, down, _)| {
            snapshot.upload_total < *up || snapshot.download_total < *down
        });
        if version_changed || totals_reset {
            let refs = self
                .last
                .as_ref()
                .map(|v| vec![v.3.clone()])
                .unwrap_or_default();
            events.push(
                self.event(
                    at,
                    None,
                    refs,
                    EventData::CounterReset {
                        reason: if version_changed {
                            "controller_version_changed"
                        } else {
                            "controller_totals_decreased"
                        }
                        .into(),
                    },
                ),
            );
            self.epoch += 1;
            self.previous.clear();
            self.last = None;
        }
        if self.version.as_ref() != Some(&version) {
            events.push(self.event(
                at,
                None,
                vec![],
                EventData::ControllerVersion {
                    version: version.clone(),
                },
            ));
            self.version = Some(version);
        }
        let interval = self
            .last
            .as_ref()
            .map(|last| at.monotonic_ms.saturating_sub(last.0.monotonic_ms));
        let sample = self.event(
            at,
            None,
            self.last
                .as_ref()
                .map(|last| vec![last.3.clone()])
                .unwrap_or_default(),
            EventData::SnapshotObserved {
                connection_count: snapshot.connections.len(),
                upload_total: snapshot.upload_total,
                download_total: snapshot.download_total,
                upload_delta: self
                    .last
                    .as_ref()
                    .map(|last| snapshot.upload_total - last.1),
                download_delta: self
                    .last
                    .as_ref()
                    .map(|last| snapshot.download_total - last.2),
                interval_ms: interval,
            },
        );
        let sample_id = sample.event_id.clone();
        events.push(sample);
        let mut next = BTreeMap::new();
        let sorted: BTreeMap<_, _> = snapshot
            .connections
            .into_iter()
            .map(|c| (c.id.clone(), c))
            .collect();
        for (id, connection) in sorted {
            let previous = self.previous.remove(&id);
            let reset = previous.as_ref().is_some_and(|old| {
                old.connection.start != connection.start
                    || old.connection.upload > connection.upload
                    || old.connection.download > connection.download
                    || old.connection.metadata.network != connection.metadata.network
                    || old.connection.metadata.source_ip != connection.metadata.source_ip
                    || old.connection.metadata.source_port != connection.metadata.source_port
                    || old.connection.metadata.destination_ip != connection.metadata.destination_ip
                    || old.connection.metadata.destination_port
                        != connection.metadata.destination_port
            });
            if reset {
                let old = previous.as_ref().expect("reset has previous connection");
                events.push(self.event(
                    at,
                    Some(old.key.clone()),
                    vec![old.evidence.clone(), sample_id.clone()],
                    EventData::CounterReset {
                        reason: "connection_counter_or_identity_changed".into(),
                    },
                ));
            }
            let (key, data, refs) = match previous.as_ref().filter(|_| !reset) {
                Some(old) => (
                    old.key.clone(),
                    EventData::ConnectionUpdated {
                        upload_delta: connection.upload - old.connection.upload,
                        download_delta: connection.download - old.connection.download,
                        interval_ms: interval.unwrap_or(0),
                        connection: connection.clone(),
                    },
                    vec![old.evidence.clone(), sample_id.clone()],
                ),
                None => (
                    format!(
                        "{}/mihomo/{}/{}/{}",
                        self.session,
                        self.epoch,
                        id,
                        self.sequence + 1
                    ),
                    EventData::ConnectionObserved {
                        connection: connection.clone(),
                    },
                    vec![sample_id.clone()],
                ),
            };
            let event = self.event(at, Some(key.clone()), refs, data);
            next.insert(
                id,
                Seen {
                    connection,
                    key,
                    evidence: event.event_id.clone(),
                },
            );
            events.push(event);
        }
        let vanished = std::mem::take(&mut self.previous);
        for (_, old) in vanished {
            events.push(
                self.event(
                    at,
                    Some(old.key),
                    vec![old.evidence, sample_id.clone()],
                    EventData::ConnectionDisappeared {
                        last_seen_monotonic_ms: self
                            .last
                            .as_ref()
                            .map_or(at.monotonic_ms, |v| v.0.monotonic_ms),
                    },
                ),
            );
        }
        self.previous = next;
        self.last = Some((
            at,
            snapshot.upload_total,
            snapshot.download_total,
            sample_id,
        ));
        events
    }

    pub fn finish(&mut self, at: Stamp, reason: String) -> Vec<Event> {
        let mut events = self.close_gap(at);
        events.push(self.event(at, None, vec![], EventData::ObservationEnded { reason }));
        events
    }
}
