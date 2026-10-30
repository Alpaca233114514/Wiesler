use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SocketObservation {
    pub process: String,
    pub pid: u32,
    pub process_started_at: Option<String>,
    pub local_ip: String,
    pub local_port: u16,
    pub remote_ip: String,
    pub remote_port: u16,
    pub state: String,
    pub role: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SocketSnapshot {
    pub process_count: usize,
    pub observed_at_utc: u64,
    pub sockets: Vec<SocketObservation>,
}

pub fn read_snapshot(path: &std::path::Path) -> Result<SocketSnapshot> {
    use std::io::Read;
    let mut bytes = vec![];
    std::fs::File::open(path)?
        .take(4 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)?;
    decode(&bytes)
}

fn decode(bytes: &[u8]) -> Result<SocketSnapshot> {
    if bytes.len() > 4 * 1024 * 1024 {
        bail!("socket_snapshot_too_large");
    }
    let result: SocketSnapshot = serde_json::from_slice(bytes)?;
    if result.process_count == 0 {
        bail!("socket_processes_not_visible");
    }
    for socket in &result.sockets {
        if socket.local_ip.parse::<std::net::IpAddr>().is_err()
            || socket.remote_ip.parse::<std::net::IpAddr>().is_err()
        {
            bail!("invalid_socket_address");
        }
    }
    Ok(result)
}

#[cfg(windows)]
pub fn collect() -> Result<SocketSnapshot> {
    use std::os::windows::process::CommandExt;
    use std::{
        io::{Read, Write},
        process::{Command, Stdio},
        time::{Duration, Instant},
    };
    let script = include_str!("../scripts/observe-sockets.ps1");
    let mut child = Command::new("powershell.exe")
        .args(["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "-"])
        .creation_flags(0x08000000)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    {
        let mut input = child.stdin.take().expect("piped stdin");
        input.write_all(script.as_bytes())?;
        input.write_all(b"\n\n")?;
    }
    let mut stdout = child.stdout.take().expect("piped stdout");
    let reader = std::thread::spawn(move || {
        let mut bytes = vec![];
        stdout
            .by_ref()
            .take(4 * 1024 * 1024 + 1)
            .read_to_end(&mut bytes)
            .map(|_| bytes)
    });
    let start = Instant::now();
    loop {
        if let Some(status) = child.try_wait()? {
            let bytes = reader
                .join()
                .map_err(|_| anyhow::anyhow!("socket_reader_failed"))??;
            if !status.success() || bytes.len() > 4 * 1024 * 1024 {
                bail!("socket_probe_failed");
            }
            return decode(&bytes);
        }
        if start.elapsed() > Duration::from_secs(10) {
            let _ = child.kill();
            let _ = child.wait();
            let _ = reader.join();
            bail!("socket_probe_timeout");
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[cfg(not(windows))]
pub fn collect() -> Result<SocketSnapshot> {
    bail!("socket_probe_windows_only")
}
