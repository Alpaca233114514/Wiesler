use anyhow::{Result, bail};
use reqwest::{Url, header::HeaderValue};
use std::{net::IpAddr, time::Duration};

pub struct ControllerConfig {
    pub url: Url,
    pub authorization: Option<HeaderValue>,
    pub timeout: Duration,
}

impl ControllerConfig {
    pub fn new(address: &str, secret: Option<&str>, timeout: Duration) -> Result<Self> {
        let url = Url::parse(address).map_err(|_| anyhow::anyhow!("控制器 URL 无效"))?;
        let host = url.host_str().unwrap_or_default().trim_matches(['[', ']']);
        let loopback = host.parse::<IpAddr>().is_ok_and(|ip| ip.is_loopback());
        if url.scheme() != "http"
            || !loopback
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
            || url.path() != "/"
        {
            bail!("仅支持回环 IP 的 HTTP 根地址；不得含用户信息、路径、查询或片段");
        }
        if timeout.is_zero() {
            bail!("超时必须大于零");
        }
        let authorization = secret
            .filter(|s| !s.is_empty())
            .map(|secret| {
                let mut header =
                    HeaderValue::from_str(&format!("Bearer {secret}")).map_err(|_| {
                        anyhow::anyhow!("WIESLER_MIHOMO_SECRET 不是合法的 HTTP header 值")
                    })?;
                header.set_sensitive(true);
                Ok::<_, anyhow::Error>(header)
            })
            .transpose()?;
        Ok(Self {
            url,
            authorization,
            timeout,
        })
    }
}

pub struct LocalController {
    pub address: String,
    pub secret: Option<String>,
}

// 仅解析运行配置中的两个顶层标量，不加载订阅或节点。 / Read only the two runtime controller scalars.
fn controller_scalar(value: &str) -> Option<String> {
    let value = value.trim();
    if value.starts_with('"') {
        let mut stream = serde_json::Deserializer::from_str(value).into_iter::<String>();
        let parsed = stream.next()?.ok()?;
        let rest = value[stream.byte_offset()..].trim();
        return (rest.is_empty() || rest.starts_with('#')).then_some(parsed);
    }
    if let Some(value) = value.strip_prefix('\'') {
        let mut result = String::new();
        let mut chars = value.chars().peekable();
        while let Some(ch) = chars.next() {
            if ch == '\'' {
                if chars.peek() == Some(&'\'') {
                    chars.next();
                    result.push('\'');
                } else {
                    let rest: String = chars.collect();
                    return (rest.trim().is_empty() || rest.trim().starts_with('#'))
                        .then_some(result);
                }
            } else {
                result.push(ch);
            }
        }
        return None;
    }
    let plain = value.split(" #").next()?.trim();
    if plain.starts_with(['!', '&', '*', '{', '[', '|', '>']) || plain.contains('\n') {
        return None;
    }
    Some(if matches!(plain, "null" | "~") || plain.starts_with('#') {
        String::new()
    } else {
        plain.to_string()
    })
}

fn parse_local_controller(content: &str) -> Option<LocalController> {
    let mut address = None;
    let mut secret = None;
    let mut secret_seen = false;
    for line in content.lines() {
        if let Some(value) = line.strip_prefix("external-controller:") {
            if address.is_some() {
                return None;
            }
            address = Some(controller_scalar(value)?);
        } else if let Some(value) = line.strip_prefix("secret:") {
            if secret_seen {
                return None;
            }
            secret_seen = true;
            secret = Some(controller_scalar(value)?).filter(|s| !s.is_empty());
        }
    }
    let authority = address?;
    let socket: std::net::SocketAddr = authority.parse().ok()?;
    let ip = if socket.ip().is_unspecified() {
        if socket.is_ipv4() {
            IpAddr::V4(std::net::Ipv4Addr::LOCALHOST)
        } else {
            IpAddr::V6(std::net::Ipv6Addr::LOCALHOST)
        }
    } else if socket.ip().is_loopback() {
        socket.ip()
    } else {
        return None;
    };
    if socket.port() == 0 {
        return None;
    }
    Some(LocalController {
        address: format!("http://{}", std::net::SocketAddr::new(ip, socket.port())),
        secret,
    })
}

pub fn local_controller() -> Option<LocalController> {
    let path = std::path::PathBuf::from(std::env::var_os("APPDATA")?)
        .join("io.github.clash-verge-rev.clash-verge-rev")
        .join("clash-verge.yaml");
    parse_local_controller(&std::fs::read_to_string(path).ok()?)
}

pub fn controller_secret(address: &str) -> Option<String> {
    if let Ok(secret) = std::env::var("WIESLER_MIHOMO_SECRET") {
        return Some(secret);
    }
    let local = local_controller()?;
    let requested = Url::parse(address).ok()?;
    // 本地密钥只交给配置指定的端点。 / Bind the local credential to its configured endpoint.
    (requested == Url::parse(&local.address).ok()?)
        .then_some(local.secret)
        .flatten()
}

#[cfg(test)]
mod local_tests {
    use super::*;

    #[test]
    fn runtime_config_keeps_credentials_bound_to_loopback() {
        let local = parse_local_controller("external-controller: 0.0.0.0:9097\nsecret: 'test''key' # comment\nproxies:\n  - secret: ignored").unwrap();
        assert_eq!(local.address, "http://127.0.0.1:9097");
        assert_eq!(local.secret.as_deref(), Some("test'key"));
        assert!(parse_local_controller("external-controller: 192.0.2.1:9097").is_none());
        assert!(parse_local_controller("external-controller: 127.0.0.1:0").is_none());
        let v6 = parse_local_controller(
            "external-controller: '[::]:9097'\nsecret: \"test-key\" # comment",
        )
        .unwrap();
        assert_eq!(v6.address, "http://[::1]:9097");
        assert_eq!(v6.secret.as_deref(), Some("test-key"));
    }

    #[test]
    fn ambiguous_yaml_does_not_supply_credentials() {
        for value in [
            "*alias",
            "|",
            "\"unterminated",
            "'unterminated",
            "!!str key",
        ] {
            assert!(
                parse_local_controller(&format!(
                    "external-controller: 127.0.0.1:9097\nsecret: {value}"
                ))
                .is_none()
            );
        }
        assert!(
            parse_local_controller("external-controller: 127.0.0.1:9097\nsecret: one\nsecret: two")
                .is_none()
        );
        assert!(
            parse_local_controller(
                "external-controller: 127.0.0.1:9097\nexternal-controller: 127.0.0.1:9098"
            )
            .is_none()
        );
        assert!(
            parse_local_controller("external-controller: 127.0.0.1:9097\nsecret: null")
                .unwrap()
                .secret
                .is_none()
        );
    }
}
