use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DnsInspection {
    pub observed_at: u64,
    pub resolvers: Vec<Resolver>,
    pub queries: Vec<Query>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Resolver {
    pub server: String,
    pub configured_interface: String,
    pub description: String,
    pub hardware: bool,
    pub route_interface: Option<String>,
    pub next_hop: Option<String>,
    pub route_error: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Query {
    pub server: String,
    pub domain: String,
    pub addresses: Vec<String>,
    pub elapsed_ms: u64,
    pub error: Option<String>,
}

#[cfg(windows)]
pub fn inspect() -> Result<DnsInspection, String> {
    use std::{
        io::{Read, Write},
        os::windows::process::CommandExt,
        process::{Command, Stdio},
        time::{Duration, Instant},
    };
    let mut child = Command::new("powershell.exe")
        .args(["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "-"])
        .creation_flags(0x08000000)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| e.to_string())?;
    let script = include_str!("../../scripts/inspect-dns.ps1");
    let write = child
        .stdin
        .take()
        .unwrap()
        .write_all(format!("{script}\n\n").as_bytes());
    if let Err(e) = write {
        let _ = child.kill();
        let _ = child.wait();
        return Err(e.to_string());
    }
    let stdout = child.stdout.take().unwrap();
    let reader = std::thread::spawn(move || {
        let mut bytes = Vec::new();
        stdout.take(262145).read_to_end(&mut bytes).map(|_| bytes)
    });
    let start = Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                let bytes = reader
                    .join()
                    .map_err(|_| "DNS 读取任务失败")?
                    .map_err(|e| e.to_string())?;
                if !status.success() || bytes.len() > 262144 {
                    return Err("DNS 系统采集失败".into());
                }
                return serde_json::from_slice(&bytes)
                    .map_err(|_| "DNS 系统采集未返回有效结果".into());
            }
            Ok(None) if start.elapsed() < Duration::from_secs(40) => {
                std::thread::sleep(Duration::from_millis(50))
            }
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                let _ = reader.join();
                return Err("DNS 检查超时或进程异常".into());
            }
        }
    }
}

#[cfg(not(windows))]
pub fn inspect() -> Result<DnsInspection, String> {
    Err("DNS 系统检查目前仅支持 Windows".into())
}

#[cfg(test)]
mod tests {
    #[test]
    #[ignore = "reads local adapters and queries configured resolvers"]
    fn real_dns_inspection() {
        let result = super::inspect().expect("DNS inspection");
        assert!(!result.resolvers.is_empty(), "no configured DNS servers");
        assert!(!result.queries.is_empty(), "no queries attempted");
        println!("{}", serde_json::to_string_pretty(&result).unwrap());
    }
}
