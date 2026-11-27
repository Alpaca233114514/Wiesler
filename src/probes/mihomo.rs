use crate::{
    config::ControllerConfig,
    core::event::{Connection, Metadata, Snapshot},
};
use reqwest::{Client, StatusCode, header::AUTHORIZATION};
use serde::{
    Deserialize, Deserializer,
    de::{self, MapAccess, SeqAccess, Visitor},
};
use std::{collections::BTreeSet, fmt, sync::OnceLock};

const MAX_BODY: usize = 16 * 1024 * 1024;

fn shared_http_client() -> Result<Client, reqwest::Error> {
    static CLIENT: OnceLock<Client> = OnceLock::new();
    if let Some(client) = CLIENT.get() {
        return Ok(client.clone());
    }
    let built = Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    Ok(CLIENT.get_or_init(|| built).clone())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProbeError {
    Authentication(u16),
    Timeout,
    Network,
    Http(u16),
    InvalidSnapshot,
    Oversized,
}

impl std::fmt::Display for ProbeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Authentication(status) => write!(f, "authentication_failed (HTTP {status})"),
            Self::Timeout => f.write_str("timeout"),
            Self::Network => f.write_str("network_error"),
            Self::Http(status) => write!(f, "http_{status}"),
            Self::InvalidSnapshot => f.write_str("invalid_snapshot"),
            Self::Oversized => f.write_str("response_too_large"),
        }
    }
}

impl std::error::Error for ProbeError {}

#[derive(Debug)]
pub struct ProbeFailure {
    pub error: ProbeError,
    pub http_status: Option<u16>,
}

fn network_error(error: reqwest::Error) -> ProbeError {
    if error.is_timeout() {
        ProbeError::Timeout
    } else {
        ProbeError::Network
    }
}

pub struct Mihomo {
    config: ControllerConfig,
    client: Client,
}

impl Mihomo {
    pub fn new(config: ControllerConfig) -> anyhow::Result<Self> {
        let client = shared_http_client()?;
        Ok(Self { config, client })
    }

    async fn get(&self, path: &str) -> Result<Vec<u8>, ProbeError> {
        self.send(
            self.client
                .get(self.config.url.join(path).expect("API path")),
        )
        .await
        .map(|(_, body)| body)
    }

    async fn send(&self, request: reqwest::RequestBuilder) -> Result<(u16, Vec<u8>), ProbeError> {
        self.send_with_status(request)
            .await
            .map_err(|failure| failure.error)
    }

    async fn send_with_status(
        &self,
        mut request: reqwest::RequestBuilder,
    ) -> Result<(u16, Vec<u8>), ProbeFailure> {
        request = request.timeout(self.config.timeout);
        if let Some(header) = &self.config.authorization {
            request = request.header(AUTHORIZATION, header.clone());
        }
        let mut response = request.send().await.map_err(|error| ProbeFailure {
            error: network_error(error),
            http_status: None,
        })?;
        let status = response.status();
        let failure = |error| ProbeFailure {
            error,
            http_status: Some(status.as_u16()),
        };
        match status {
            StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN => {
                return Err(failure(ProbeError::Authentication(status.as_u16())));
            }
            code if !code.is_success() => return Err(failure(ProbeError::Http(code.as_u16()))),
            _ => {}
        }
        let mut body = Vec::new();
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|error| failure(network_error(error)))?
        {
            if body.len().saturating_add(chunk.len()) > MAX_BODY {
                return Err(failure(ProbeError::Oversized));
            }
            body.extend_from_slice(&chunk);
        }
        Ok((status.as_u16(), body))
    }

    fn proxy_url(&self, name: &str) -> Result<reqwest::Url, ProbeError> {
        if name.is_empty() || matches!(name, "." | "..") {
            return Err(ProbeError::InvalidSnapshot);
        }
        let mut url = self.config.url.clone();
        url.path_segments_mut()
            .expect("HTTP URL")
            .extend(["proxies", name]);
        Ok(url)
    }

    pub async fn proxy(&self, name: &str) -> Result<ProxyInfo, ProbeError> {
        let (_, bytes) = self.send(self.client.get(self.proxy_url(name)?)).await?;
        serde_json::from_slice(&bytes).map_err(|_| ProbeError::InvalidSnapshot)
    }

    /// 显式写路径；调用者负责原值记录与恢复。 / Explicit mutation; caller journals and restores selection.
    pub async fn select_proxy(&self, group: &str, name: &str) -> Result<(), ProbeError> {
        self.send(
            self.client
                .put(self.proxy_url(group)?)
                .json(&serde_json::json!({"name": name})),
        )
        .await?;
        Ok(())
    }

    pub async fn version(&self) -> Result<String, ProbeError> {
        #[derive(Deserialize)]
        struct Version {
            version: String,
        }
        let version: Version = serde_json::from_slice(&self.get("version").await?)
            .map_err(|_| ProbeError::InvalidSnapshot)?;
        if version.version.trim().is_empty() {
            return Err(ProbeError::InvalidSnapshot);
        }
        Ok(version.version)
    }

    pub async fn snapshot(&self) -> Result<Snapshot, ProbeError> {
        self.snapshot_with_status()
            .await
            .map(|(_, snapshot)| snapshot)
            .map_err(|failure| failure.error)
    }

    pub async fn http_proxy_address(&self) -> Result<Option<String>, ProbeError> {
        #[derive(Deserialize)]
        struct Ports {
            #[serde(default, rename = "mixed-port")]
            mixed: u16,
            #[serde(default)]
            port: u16,
        }
        let ports: Ports = serde_json::from_slice(&self.get("configs").await?)
            .map_err(|_| ProbeError::InvalidSnapshot)?;
        let port = if ports.mixed > 0 {
            ports.mixed
        } else {
            ports.port
        };
        if port == 0 {
            return Ok(None);
        }
        let mut address = self.config.url.clone();
        address
            .set_port(Some(port))
            .map_err(|_| ProbeError::InvalidSnapshot)?;
        Ok(Some(address.to_string()))
    }

    pub async fn snapshot_with_status(&self) -> Result<(u16, Snapshot), ProbeFailure> {
        let (status, body) = self
            .send_with_status(
                self.client
                    .get(self.config.url.join("connections").expect("API path")),
            )
            .await?;
        let snapshot = parse_snapshot(&body).map_err(|error| ProbeFailure {
            error,
            http_status: Some(status),
        })?;
        Ok((status, snapshot))
    }
}

#[derive(Debug, Deserialize)]
pub struct ProxyInfo {
    #[serde(rename = "type")]
    pub proxy_type: String,
    pub now: Option<String>,
    #[serde(default)]
    pub all: Vec<String>,
}

/// 单遍流式解析 Mihomo `/connections` 快照，边解码边校验连接与去重，跳过未知字段并避免双份中间集合分配。
/// Single-pass streaming `/connections` parser validating each entry inline while skipping unknown fields.
struct ValidatedConnections(Vec<Connection>);

impl<'de> Deserialize<'de> for ValidatedConnections {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct ConnectionsSeqVisitor;

        impl<'de> Visitor<'de> for ConnectionsSeqVisitor {
            type Value = ValidatedConnections;

            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str("a sequence of Mihomo connections or null")
            }

            fn visit_unit<E: de::Error>(self) -> Result<Self::Value, E> {
                // Go 的 nil slice 是 null；字段缺失仍由外层拒绝。 / A Go nil slice is null; absent field is still invalid.
                Ok(ValidatedConnections(Vec::new()))
            }

            fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Self::Value, A::Error> {
                let mut ids = BTreeSet::new();
                let mut connections = Vec::with_capacity(seq.size_hint().unwrap_or(0).min(1024));
                while let Some(c) = seq.next_element::<WireConnection>()? {
                    if c.id.trim().is_empty() || !ids.insert(c.id.clone()) {
                        return Err(de::Error::custom("duplicate or empty connection id"));
                    }
                    let m = c.metadata;
                    for ip in [&m.source_ip, &m.destination_ip].into_iter().flatten() {
                        if !ip.is_empty() && ip.parse::<std::net::IpAddr>().is_err() {
                            return Err(de::Error::custom("invalid ip address"));
                        }
                    }
                    let source_port = m
                        .source_port
                        .map(Port::parse)
                        .transpose()
                        .map_err(|_| de::Error::custom("invalid source port"))?;
                    let destination_port = m
                        .destination_port
                        .map(Port::parse)
                        .transpose()
                        .map_err(|_| de::Error::custom("invalid destination port"))?;
                    connections.push(Connection {
                        id: c.id,
                        upload: c.upload,
                        download: c.download,
                        start: nonempty(c.start),
                        metadata: Metadata {
                            network: nonempty(m.network),
                            inbound_type: nonempty(m.inbound_type),
                            source_ip: nonempty(m.source_ip),
                            destination_ip: nonempty(m.destination_ip),
                            source_port,
                            destination_port,
                            host: nonempty(m.host),
                            process: nonempty(m.process)
                                .and_then(|p| p.rsplit(['/', '\\']).next().map(str::to_owned))
                                .filter(|p| !p.is_empty()),
                            pid: m.pid.filter(|p| *p != 0),
                        },
                        chains: c.chains,
                        rule: nonempty(c.rule),
                    });
                }
                Ok(ValidatedConnections(connections))
            }
        }

        deserializer.deserialize_any(ConnectionsSeqVisitor)
    }
}

struct StreamingSnapshot(Snapshot);

impl<'de> Deserialize<'de> for StreamingSnapshot {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct SnapshotVisitor;

        impl<'de> Visitor<'de> for SnapshotVisitor {
            type Value = StreamingSnapshot;

            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str("a Mihomo connections snapshot object")
            }

            fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Self::Value, A::Error> {
                let mut upload_total = None;
                let mut download_total = None;
                let mut connections = None;

                while let Some(key) = map.next_key::<&str>()? {
                    match key {
                        "uploadTotal" => {
                            if upload_total.is_some() {
                                return Err(de::Error::duplicate_field("uploadTotal"));
                            }
                            upload_total = Some(map.next_value::<u64>()?);
                        }
                        "downloadTotal" => {
                            if download_total.is_some() {
                                return Err(de::Error::duplicate_field("downloadTotal"));
                            }
                            download_total = Some(map.next_value::<u64>()?);
                        }
                        "connections" => {
                            if connections.is_some() {
                                return Err(de::Error::duplicate_field("connections"));
                            }
                            connections = Some(map.next_value::<ValidatedConnections>()?.0);
                        }
                        _ => {
                            let _ = map.next_value::<de::IgnoredAny>()?;
                        }
                    }
                }

                Ok(StreamingSnapshot(Snapshot {
                    upload_total: upload_total
                        .ok_or_else(|| de::Error::missing_field("uploadTotal"))?,
                    download_total: download_total
                        .ok_or_else(|| de::Error::missing_field("downloadTotal"))?,
                    connections: connections
                        .ok_or_else(|| de::Error::missing_field("connections"))?,
                }))
            }
        }

        deserializer.deserialize_map(SnapshotVisitor)
    }
}

#[derive(Deserialize)]
struct WireConnection {
    id: String,
    upload: u64,
    download: u64,
    start: Option<String>,
    metadata: WireMetadata,
    #[serde(default, deserialize_with = "nullable_chains")]
    chains: Vec<String>,
    rule: Option<String>,
}

fn nullable_chains<'de, D: serde::Deserializer<'de>>(d: D) -> Result<Vec<String>, D::Error> {
    Ok(Option::<Vec<String>>::deserialize(d)?.unwrap_or_default())
}

#[derive(Deserialize)]
#[serde(untagged)]
enum Port {
    Text(String),
    Number(u16),
}

impl Port {
    fn parse(self) -> Result<u16, ProbeError> {
        match self {
            Self::Text(s) => s.parse().map_err(|_| ProbeError::InvalidSnapshot),
            Self::Number(n) => Ok(n),
        }
    }
}

#[derive(Deserialize)]
struct WireMetadata {
    network: Option<String>,
    #[serde(rename = "type")]
    inbound_type: Option<String>,
    #[serde(rename = "sourceIP")]
    source_ip: Option<String>,
    #[serde(rename = "destinationIP")]
    destination_ip: Option<String>,
    #[serde(rename = "sourcePort")]
    source_port: Option<Port>,
    #[serde(rename = "destinationPort")]
    destination_port: Option<Port>,
    host: Option<String>,
    process: Option<String>,
    pid: Option<u32>,
}

fn nonempty(s: Option<String>) -> Option<String> {
    s.filter(|s| !s.is_empty())
}

pub fn parse_snapshot(bytes: &[u8]) -> Result<Snapshot, ProbeError> {
    let parsed: StreamingSnapshot =
        serde_json::from_slice(bytes).map_err(|_| ProbeError::InvalidSnapshot)?;
    Ok(parsed.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn streaming_parser_validates_and_skips_unknown_fields() {
        let payload = br#"{
            "memory": 999999,
            "uploadTotal": 120,
            "downloadTotal": 340,
            "extraNested": {"a": [1, 2, 3]},
            "connections": [
                {
                    "id": "conn-1",
                    "upload": 12,
                    "download": 34,
                    "rule": "Match",
                    "chains": ["DIRECT"],
                    "unknownField": true,
                    "metadata": {
                        "network": "tcp",
                        "sourceIP": "127.0.0.1",
                        "sourcePort": "54321",
                        "destinationIP": "1.1.1.1",
                        "destinationPort": 443,
                        "host": "example.com",
                        "process": "C:\\Program Files\\App\\demo.exe",
                        "pid": 42
                    }
                }
            ]
        }"#;
        let snap = parse_snapshot(payload).expect("valid snapshot");
        assert_eq!(snap.upload_total, 120);
        assert_eq!(snap.download_total, 340);
        assert_eq!(snap.connections.len(), 1);
        assert_eq!(snap.connections[0].metadata.process.as_deref(), Some("demo.exe"));
        assert_eq!(snap.connections[0].metadata.source_port, Some(54321));

        let null_conns = br#"{"uploadTotal":0,"downloadTotal":0,"connections":null}"#;
        assert!(parse_snapshot(null_conns).unwrap().connections.is_empty());

        let dup_ids = br#"{"uploadTotal":0,"downloadTotal":0,"connections":[{"id":"a","upload":0,"download":0,"metadata":{}},{"id":"a","upload":0,"download":0,"metadata":{}}]}"#;
        assert_eq!(parse_snapshot(dup_ids).unwrap_err(), ProbeError::InvalidSnapshot);
    }
}
