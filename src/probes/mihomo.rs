use crate::{
    config::ControllerConfig,
    core::event::{Connection, Metadata, Snapshot},
};
use reqwest::{Client, StatusCode, header::AUTHORIZATION};
use serde::Deserialize;
use std::collections::BTreeSet;

const MAX_BODY: usize = 16 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProbeError {
    Authentication,
    Timeout,
    Network,
    Http(u16),
    InvalidSnapshot,
    Oversized,
}

impl std::fmt::Display for ProbeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Authentication => f.write_str("authentication_failed"),
            Self::Timeout => f.write_str("timeout"),
            Self::Network => f.write_str("network_error"),
            Self::Http(status) => write!(f, "http_{status}"),
            Self::InvalidSnapshot => f.write_str("invalid_snapshot"),
            Self::Oversized => f.write_str("response_too_large"),
        }
    }
}

impl std::error::Error for ProbeError {}

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
        let client = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(config.timeout)
            .build()?;
        Ok(Self { config, client })
    }

    async fn get(&self, path: &str) -> Result<Vec<u8>, ProbeError> {
        let mut request = self
            .client
            .get(self.config.url.join(path).expect("fixed API path"));
        if let Some(header) = &self.config.authorization {
            request = request.header(AUTHORIZATION, header.clone());
        }
        let mut response = request.send().await.map_err(network_error)?;
        match response.status() {
            StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN => {
                return Err(ProbeError::Authentication);
            }
            code if !code.is_success() => return Err(ProbeError::Http(code.as_u16())),
            _ => {}
        }
        let mut body = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(network_error)? {
            if body.len().saturating_add(chunk.len()) > MAX_BODY {
                return Err(ProbeError::Oversized);
            }
            body.extend_from_slice(&chunk);
        }
        Ok(body)
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
        parse_snapshot(&self.get("connections").await?)
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireSnapshot {
    upload_total: u64,
    download_total: u64,
    #[serde(deserialize_with = "connections")]
    connections: Vec<WireConnection>,
}

// Go 的 nil slice 是 null；字段缺失仍是错误。 / A Go nil slice is null; an absent field is still invalid.
fn connections<'de, D: serde::Deserializer<'de>>(d: D) -> Result<Vec<WireConnection>, D::Error> {
    Ok(Option::<Vec<WireConnection>>::deserialize(d)?.unwrap_or_default())
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
    let wire: WireSnapshot =
        serde_json::from_slice(bytes).map_err(|_| ProbeError::InvalidSnapshot)?;
    let mut ids = BTreeSet::new();
    let mut connections = Vec::with_capacity(wire.connections.len());
    for c in wire.connections {
        if c.id.trim().is_empty() || !ids.insert(c.id.clone()) {
            return Err(ProbeError::InvalidSnapshot);
        }
        let m = c.metadata;
        for ip in [&m.source_ip, &m.destination_ip].into_iter().flatten() {
            if !ip.is_empty() && ip.parse::<std::net::IpAddr>().is_err() {
                return Err(ProbeError::InvalidSnapshot);
            }
        }
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
                source_port: m.source_port.map(Port::parse).transpose()?,
                destination_port: m.destination_port.map(Port::parse).transpose()?,
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
    Ok(Snapshot {
        connections,
        upload_total: wire.upload_total,
        download_total: wire.download_total,
    })
}
