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
