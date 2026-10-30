use serde::{Deserialize, Serialize};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

pub const SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct Stamp {
    pub utc_ms: u64,
    pub monotonic_ms: u64,
}

impl Stamp {
    pub fn now(origin: Instant) -> Self {
        Self {
            utc_ms: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
            monotonic_ms: origin.elapsed().as_millis() as u64,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Metadata {
    pub network: Option<String>,
    pub inbound_type: Option<String>,
    pub source_ip: Option<String>,
    pub source_port: Option<u16>,
    pub destination_ip: Option<String>,
    pub destination_port: Option<u16>,
    pub host: Option<String>,
    pub process: Option<String>,
    pub pid: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Connection {
    pub id: String,
    pub upload: u64,
    pub download: u64,
    pub start: Option<String>,
    pub metadata: Metadata,
    pub chains: Vec<String>,
    pub rule: Option<String>,
}

#[derive(Debug, Clone)]
pub struct Snapshot {
    pub connections: Vec<Connection>,
    pub upload_total: u64,
    pub download_total: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", content = "payload")]
pub enum EventData {
    ApplicationObserved {
        observation: Box<crate::application::ApplicationObservation>,
    },
    SocketsObserved {
        sockets: Vec<crate::sockets::SocketObservation>,
        #[serde(default)]
        sample_time_utc: Option<u64>,
    },
    SourceGap {
        reason: String,
        file: Option<String>,
        line: Option<u64>,
    },
    SessionStarted {
        interval_ms: u64,
    },
    ControllerVersion {
        version: String,
    },
    SnapshotObserved {
        connection_count: usize,
        upload_total: u64,
        download_total: u64,
        upload_delta: Option<u64>,
        download_delta: Option<u64>,
        interval_ms: Option<u64>,
    },
    ConnectionObserved {
        connection: Connection,
    },
    ConnectionUpdated {
        connection: Connection,
        upload_delta: u64,
        download_delta: u64,
        interval_ms: u64,
    },
    ConnectionDisappeared {
        last_seen_monotonic_ms: u64,
    },
    ControllerUnavailable {
        reason: String,
    },
    ControllerRecovered,
    ObservationGap {
        from_monotonic_ms: u64,
        to_monotonic_ms: u64,
        failures: u64,
        dropped_samples: u64,
    },
    CounterReset {
        reason: String,
    },
    ObservationEnded {
        reason: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Event {
    pub schema_version: u32,
    pub event_id: String,
    pub session_id: String,
    pub sequence: u64,
    pub source_id: String,
    pub source_epoch: u64,
    /// UTC Unix 毫秒；单调时间用于间隔。 / UTC Unix milliseconds; intervals use monotonic time.
    pub observed_at_utc: u64,
    pub monotonic_offset_ms: u64,
    pub source_timestamp: Option<String>,
    pub connection_key: Option<String>,
    pub evidence_ids: Vec<String>,
    pub quality: Vec<String>,
    #[serde(flatten)]
    pub data: EventData,
}
