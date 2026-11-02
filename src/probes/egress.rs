use serde::{Deserialize, Serialize};
use std::{
    net::IpAddr,
    process::Command,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const GEO_PROVIDER: &str = "https://ipwho.is";

const PROVIDERS: [&str; 2] = ["https://api64.ipify.org", "https://api.ipify.org"];

#[derive(Serialize)]
pub struct EgressAddress {
    pub ip: String,
    pub observed_at: u64,
    pub provider: &'static str,
    pub proxy: Option<String>,
    pub location: Option<EgressLocation>,
    pub location_error: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct EgressLocation {
    pub country: Option<String>,
    pub country_code: Option<String>,
    pub region: Option<String>,
    pub city: Option<String>,
    pub isp: Option<String>,
    pub timezone: Option<String>,
}

fn parse_location(bytes: &[u8], expected_ip: &str) -> Result<EgressLocation, String> {
    #[derive(Deserialize, Default)]
    struct Connection {
        isp: Option<String>,
    }
    #[derive(Deserialize, Default)]
    struct Timezone {
        id: Option<String>,
    }
    #[derive(Deserialize)]
    struct Location {
        ip: String,
        success: bool,
        country: Option<String>,
        country_code: Option<String>,
        region: Option<String>,
        city: Option<String>,
        #[serde(default)]
        connection: Connection,
        #[serde(default)]
        timezone: Timezone,
    }
    let data: Location = serde_json::from_slice(bytes).map_err(|_| "地理信息不可用".to_string())?;
    // 地址库必须对应本次实测 IP，不能将另一条路由的所在地混入。 / Bind location data to the measured IP.
    let expected = expected_ip.parse::<IpAddr>().map_err(|_| "出口 IP 无效")?;
    if !data.success || data.ip.parse::<IpAddr>().ok() != Some(expected) {
        return Err("地理信息与出口 IP 不匹配".into());
    }
    let clean = |value: Option<String>| {
        value
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
    };
    Ok(EgressLocation {
        country: clean(data.country),
        country_code: clean(data.country_code),
        region: clean(data.region),
        city: clean(data.city),
        isp: clean(data.connection.isp),
        timezone: clean(data.timezone.id),
    })
}

fn arguments(proxy: Option<&str>, provider: &str) -> Vec<String> {
    [
        "-q",
        "--silent",
        "--show-error",
        "--fail",
        "--connect-timeout",
        "5",
        "--max-time",
        "10",
        "--max-filesize",
        "8192",
        "--proto",
        "=https",
        "--proxy",
        proxy.unwrap_or(""),
        "--noproxy",
        if proxy.is_some() { "" } else { "*" },
        provider,
    ]
    .into_iter()
    .map(str::to_owned)
    .collect()
}

fn parse_address(bytes: &[u8]) -> Result<String, String> {
    std::str::from_utf8(bytes)
        .ok()
        .and_then(|s| s.trim().parse::<IpAddr>().ok())
        .map(|ip| ip.to_string())
        .ok_or_else(|| "出口服务未返回有效 IP 地址".into())
}

pub fn measure(proxy: Option<&str>) -> Result<EgressAddress, String> {
    if let Some(proxy) = proxy {
        crate::config::ControllerConfig::new(proxy, None, Duration::from_secs(1))
            .map_err(|_| "出口探测只支持本机 HTTP 代理".to_string())?;
    }
    let mut last_error = String::new();
    for provider in PROVIDERS {
        match request(proxy, provider) {
            Ok(result) => return Ok(result),
            Err(error) => last_error = error,
        }
    }
    Err(last_error)
}

fn fetch(proxy: Option<&str>, provider: &str) -> Result<Vec<u8>, String> {
    let mut command = Command::new(if cfg!(windows) { "curl.exe" } else { "curl" });
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    // 不继承 curlrc 或代理环境，保留 TLS 证书验证。 / Isolate proxy selection while retaining TLS validation.
    let output = command
        .args(arguments(proxy, provider))
        .output()
        .map_err(|_| "无法启动系统 curl".to_string())?;
    if !output.status.success() {
        return Err(match output.status.code() {
            Some(28) => "出口探测超时".into(),
            Some(35 | 60) => "出口探测 TLS 验证或连接失败".into(),
            Some(22) => "出口服务或代理返回 HTTP 错误".into(),
            Some(code) => format!("出口探测失败（curl {code}）"),
            None => "出口探测已中断".into(),
        });
    }
    Ok(output.stdout)
}

fn request(proxy: Option<&str>, provider: &'static str) -> Result<EgressAddress, String> {
    let ip = parse_address(&fetch(proxy, provider)?)?;
    let observed_at = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64;
    let lookup =
        fetch(proxy, &format!("{GEO_PROVIDER}/{ip}")).and_then(|body| parse_location(&body, &ip));
    let (location, location_error) = match lookup {
        Ok(value) => (Some(value), None),
        Err(error) => (None, Some(error)),
    };
    Ok(EgressAddress {
        ip,
        observed_at,
        provider,
        proxy: proxy.map(str::to_owned),
        location,
        location_error,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn system_and_proxy_arguments_are_explicit_and_keep_tls_verification() {
        let system = arguments(None, PROVIDERS[0]);
        let proxy = arguments(Some("http://127.0.0.1:7897"), PROVIDERS[0]);
        assert!(system.windows(2).any(|a| a == ["--proxy", ""]));
        assert!(system.windows(2).any(|a| a == ["--noproxy", "*"]));
        assert!(
            proxy
                .windows(2)
                .any(|a| a == ["--proxy", "http://127.0.0.1:7897"])
        );
        assert!(proxy.windows(2).any(|a| a == ["--noproxy", ""]));
        assert!(
            !proxy
                .iter()
                .any(|a| a == "-k" || a == "--insecure" || a == "--location")
        );
        assert!(measure(Some("http://example.com:7897")).is_err());
    }
    #[test]
    fn location_matches_measured_ip_and_rejects_failed_or_unrelated_responses() {
        let body=br#"{"ip":"203.0.113.42","success":true,"country":"Example Country","country_code":"US","region":"Region","city":"City","connection":{"isp":"Example ISP"}}"#;
        let location = parse_location(body, "203.0.113.42").unwrap();
        assert_eq!(location.city.as_deref(), Some("City"));
        assert_eq!(location.isp.as_deref(), Some("Example ISP"));
        assert!(parse_location(body, "203.0.113.43").is_err());
        assert!(
            parse_location(br#"{"ip":"203.0.113.42","success":false}"#, "203.0.113.42").is_err()
        );
    }
    #[test]
    fn accepts_only_ip_responses_instead_of_html_or_error_text() {
        assert_eq!(parse_address(b"203.0.113.42\n").unwrap(), "203.0.113.42");
        assert_eq!(parse_address(b"2001:db8::1").unwrap(), "2001:db8::1");
        assert!(parse_address(b"<html>proxy login</html>").is_err());
        assert!(parse_address(b"127.0.0.1 extra").is_err());
    }
}
