use serde::Serialize;
use std::{collections::BTreeSet, net::SocketAddr, time::Duration};
use wiesler::{
    config::{ControllerConfig, local_controller},
    probes::mihomo::{Mihomo, ProbeError},
};

#[derive(Debug, Serialize)]
pub struct DiscoveredController {
    pub controller: String,
    pub version: Option<String>,
    pub ready: bool,
    pub configured: bool,
}

#[derive(Serialize)]
pub struct DiscoveryReport {
    pub controllers: Vec<DiscoveredController>,
    pub scanned: usize,
    pub warning: Option<String>,
}

fn listening_addresses(output: &str) -> BTreeSet<SocketAddr> {
    output
        .lines()
        .filter_map(|line| {
            let fields: Vec<_> = line.split_whitespace().collect();
            if fields.len() < 4 || fields[0] != "TCP" || fields[3] != "LISTENING" {
                return None;
            }
            let mut address: SocketAddr = fields[1].parse().ok()?;
            if address.port() == 0 {
                return None;
            }
            if address.ip().is_unspecified() {
                address.set_ip(if address.is_ipv4() {
                    std::net::Ipv4Addr::LOCALHOST.into()
                } else {
                    std::net::Ipv6Addr::LOCALHOST.into()
                });
            }
            address.ip().is_loopback().then_some(address)
        })
        .collect()
}

#[cfg(windows)]
fn local_listeners() -> Result<BTreeSet<SocketAddr>, String> {
    use std::os::windows::process::CommandExt;
    let output = std::process::Command::new("netstat.exe")
        .args(["-ano"])
        .creation_flags(0x08000000)
        .output()
        .map_err(|_| "无法读取本机监听端口".to_string())?;
    if !output.status.success() {
        return Err("无法读取本机监听端口".into());
    }
    Ok(listening_addresses(&String::from_utf8_lossy(
        &output.stdout,
    )))
}

#[cfg(not(windows))]
fn local_listeners() -> Result<BTreeSet<SocketAddr>, String> {
    Err("此平台暂不支持监听端口枚举，可使用高级设置".into())
}

async fn inspect(
    controller: String,
    secret: Option<String>,
    configured: bool,
) -> Option<DiscoveredController> {
    let config =
        ControllerConfig::new(&controller, secret.as_deref(), Duration::from_millis(800)).ok()?;
    let probe = Mihomo::new(config).ok()?;
    let version = match probe.version().await {
        Ok(version) => version,
        Err(ProbeError::Authentication(_)) => {
            return Some(DiscoveredController {
                controller,
                version: None,
                ready: false,
                configured,
            });
        }
        Err(_) => return None,
    };
    // 两个只读接口都匹配才自动连接，避免把代理入口或普通 HTTP 服务当控制器。
    // Verify both read-only APIs before auto-connecting to a listener.
    match probe.snapshot().await {
        Ok(_) => Some(DiscoveredController {
            controller,
            version: Some(version),
            ready: true,
            configured,
        }),
        Err(ProbeError::Authentication(_)) => Some(DiscoveredController {
            controller,
            version: Some(version),
            ready: false,
            configured,
        }),
        Err(_) => None,
    }
}

#[tauri::command]
pub async fn discover_controllers() -> Result<DiscoveryReport, String> {
    let (listeners, local) =
        tauri::async_runtime::spawn_blocking(|| (local_listeners(), local_controller()))
            .await
            .map_err(|_| "扫描本机控制器失败".to_string())?;
    let (listeners, warning) = match listeners {
        Ok(listeners) => (listeners, None),
        Err(message) => (BTreeSet::new(), Some(message)),
    };
    let mut candidates: BTreeSet<String> = listeners
        .into_iter()
        .map(|s| format!("http://{s}"))
        .collect();
    if let Some(local) = &local {
        candidates.insert(local.address.clone());
    }
    let candidates: Vec<_> = candidates.into_iter().collect();
    let scanned = candidates.len();
    let mut controllers = Vec::new();
    for batch in candidates.chunks(8) {
        let mut tasks = Vec::new();
        for address in batch {
            let configured = local
                .as_ref()
                .is_some_and(|local| local.address == *address);
            // 扫描未知服务不携带凭证。 / Never send credentials to unidentified listeners.
            let secret = if configured {
                std::env::var("WIESLER_MIHOMO_SECRET")
                    .ok()
                    .or_else(|| local.as_ref().and_then(|c| c.secret.clone()))
            } else {
                None
            };
            tasks.push(tauri::async_runtime::spawn(inspect(
                address.clone(),
                secret,
                configured,
            )));
        }
        for task in tasks {
            if let Ok(Some(controller)) = task.await {
                controllers.push(controller);
            }
        }
    }
    controllers.sort_by_key(|c| (!c.ready, !c.configured, c.controller.clone()));
    Ok(DiscoveryReport {
        controllers,
        scanned,
        warning,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        io::{Read, Write},
        net::TcpListener,
    };

    #[test]
    #[ignore = "requires a running local Clash Verge controller"]
    fn local_controller_acceptance() {
        tauri::async_runtime::block_on(async {
            let local = local_controller().expect("local runtime configuration");
            let report = discover_controllers().await.unwrap();
            let controller = report
                .controllers
                .iter()
                .find(|c| c.controller == local.address)
                .expect("configured listener must be discovered");
            assert!(
                controller.ready,
                "configured controller must authenticate and validate"
            );
            assert!(
                !report
                    .controllers
                    .iter()
                    .any(|c| c.ready && c.controller.ends_with(":7897"))
            );
            assert!(
                crate::commands::snapshot(controller.controller.clone())
                    .await
                    .is_ok()
            );
            let system = crate::commands::system_snapshot().await.unwrap();
            assert!(system.process_count > 0);
            assert!(!system.processes.is_empty());
            let ping = crate::packet_loss::ping("127.0.0.1".into()).await.unwrap();
            assert_eq!(ping.outcome, "reply");
            if let Ok(database) = std::env::var("WIESLER_TEST_DATABASE") {
                let sessions = crate::commands::sessions(database.clone()).await.unwrap();
                assert!(!sessions.is_empty());
                let report = crate::commands::diagnose(database, sessions[0].session_id.clone())
                    .await
                    .unwrap();
                assert!(!report.is_empty());
                println!("sqlite_sessions={} diagnose=ok", sessions.len());
            }
            println!(
                "scanned={} controller={} ready=true snapshot=ok system_sockets={} windows_processes={} udp_owners={} loopback_ping=ok",
                report.scanned,
                controller.controller,
                system.sockets.len(),
                system.processes.len(),
                system.udp_owners.len()
            );
        });
    }

    #[test]
    fn enumerates_only_loopback_reachable_listeners() {
        let addresses = listening_addresses(
            "TCP 0.0.0.0:9097 0.0.0.0:0 LISTENING 100\nTCP 127.0.0.1:9097 0.0.0.0:0 LISTENING 100\nTCP [::]:9098 [::]:0 LISTENING 100\nTCP 192.0.2.1:9099 0.0.0.0:0 LISTENING 100\nTCP 127.0.0.1:6000 127.0.0.1:9097 ESTABLISHED 100\nUDP 127.0.0.1:53 *:* 100",
        );
        assert_eq!(
            addresses
                .into_iter()
                .map(|s| s.to_string())
                .collect::<Vec<_>>(),
            ["127.0.0.1:9097", "[::1]:9098"]
        );
    }

    fn server(responses: Vec<(u16, &'static str)>) -> (String, std::thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = format!("http://{}", listener.local_addr().unwrap());
        let worker = std::thread::spawn(move || {
            for (index, (status, body)) in responses.into_iter().enumerate() {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(2)))
                    .unwrap();
                let mut request = Vec::new();
                let mut byte = [0];
                while !request.ends_with(b"\r\n\r\n") {
                    assert_eq!(stream.read(&mut byte).unwrap(), 1);
                    request.push(byte[0]);
                }
                let request = String::from_utf8(request).unwrap();
                assert!(request.starts_with(if index == 0 {
                    "GET /version "
                } else {
                    "GET /connections "
                }));
                assert!(!request.to_lowercase().contains("authorization:"));
                write!(stream, "HTTP/1.1 {status} Test\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
            }
        });
        (address, worker)
    }

    #[test]
    fn validates_both_apis_and_rejects_proxy_and_generic_http_ports() {
        for (responses, ready) in [
            (
                vec![
                    (200, r#"{"version":"fixture"}"#),
                    (200, include_str!("../../tests/fixtures/snapshot.json")),
                ],
                Some(true),
            ),
            (vec![(200, r#"{"version":"generic"}"#), (200, "{}")], None),
            (vec![(400, "proxy port")], None),
            (vec![(401, "unauthorized")], Some(false)),
        ] {
            let (address, worker) = server(responses);
            let result = tauri::async_runtime::block_on(inspect(address, None, false));
            assert_eq!(result.map(|c| c.ready), ready);
            worker.join().unwrap();
        }
    }
}
