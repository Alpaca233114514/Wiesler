use serde::Serialize;
use std::{
    path::Path,
    time::{Duration, Instant},
};
use wiesler::{
    config::ControllerConfig,
    core::event::{Connection, Stamp},
    probes::mihomo::Mihomo,
};

#[derive(Serialize)]
pub struct LiveSnapshot {
    http_status: u16,
    observed_at: u64,
    connections: Vec<Connection>,
    upload_total: u64,
    download_total: u64,
}

#[derive(Debug, Serialize)]
pub struct SnapshotError {
    message: String,
    http_status: Option<u16>,
}

impl From<String> for SnapshotError {
    fn from(message: String) -> Self {
        Self {
            message,
            http_status: None,
        }
    }
}

#[tauri::command]
pub async fn snapshot(controller: String) -> Result<LiveSnapshot, SnapshotError> {
    let secret = std::env::var("WIESLER_MIHOMO_SECRET").ok();
    let config = ControllerConfig::new(&controller, secret.as_deref(), Duration::from_secs(3))
        .map_err(|e| e.to_string())?;
    let probe = Mihomo::new(config).map_err(|_| "无法初始化控制器客户端".to_string())?;
    let (http_status, data) = probe
        .snapshot_with_status()
        .await
        .map_err(|e| SnapshotError {
            message: e.error.to_string(),
            http_status: e.http_status,
        })?;
    Ok(LiveSnapshot {
        http_status,
        observed_at: Stamp::now(Instant::now()).utc_ms,
        connections: data.connections,
        upload_total: data.upload_total,
        download_total: data.download_total,
    })
}

#[tauri::command]
pub async fn sessions(database: String) -> Result<Vec<wiesler::store::Session>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        wiesler::store::sessions(Path::new(&database)).map_err(|e| e.to_string())
    })
    .await
    .map_err(|_| "读取会话任务失败".to_string())?
}

#[tauri::command]
pub async fn diagnose(database: String, session: String) -> Result<String, String> {
    if session.is_empty() {
        return Err("请选择会话".into());
    }
    tauri::async_runtime::spawn_blocking(move || {
        wiesler::diagnostics::report(Path::new(&database), &[session])
            .map(|report| report.markdown())
            .map_err(|e| e.to_string())
    })
    .await
    .map_err(|_| "诊断任务失败".to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        io::{Read, Write},
        net::TcpListener,
    };

    #[test]
    fn desktop_snapshot_uses_real_probe() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = format!("http://{}", listener.local_addr().unwrap());
        let worker = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            let mut request = Vec::new();
            let mut byte = [0];
            while !request.ends_with(b"\r\n\r\n") {
                assert_eq!(stream.read(&mut byte).unwrap(), 1);
                request.push(byte[0]);
            }
            assert!(request.starts_with(b"GET /connections HTTP/1.1"));
            let body = include_str!("../../tests/fixtures/snapshot.json");
            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            )
            .unwrap();
        });
        let data = tauri::async_runtime::block_on(snapshot(address)).unwrap();
        assert_eq!(data.connections.len(), 1);
        assert_eq!(data.http_status, 200);
        assert!(data.observed_at > 0);
        worker.join().unwrap();
    }

    #[test]
    fn desktop_snapshot_preserves_http_error_codes_without_response_body() {
        for code in [200, 401, 403, 503] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let address = format!("http://{}", listener.local_addr().unwrap());
            let worker = std::thread::spawn(move || {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(3)))
                    .unwrap();
                let mut request = Vec::new();
                let mut byte = [0];
                while !request.ends_with(b"\r\n\r\n") {
                    assert_eq!(stream.read(&mut byte).unwrap(), 1);
                    request.push(byte[0]);
                }
                write!(
                    stream,
                    "HTTP/1.1 {code} Test\r\nContent-Length: 7\r\nConnection: close\r\n\r\nPRIVATE"
                )
                .unwrap();
            });
            let error = tauri::async_runtime::block_on(snapshot(address))
                .err()
                .unwrap();
            assert_eq!(error.http_status, Some(code));
            assert!(!error.message.contains("PRIVATE"));
            worker.join().unwrap();
        }
    }

    #[test]
    fn invalid_address_and_missing_database_do_not_create_data() {
        assert!(tauri::async_runtime::block_on(snapshot("http://192.0.2.1:9090".into())).is_err());
        let path =
            std::env::temp_dir().join(format!("wiesler-missing-{}.sqlite3", std::process::id()));
        assert!(!path.exists());
        assert!(tauri::async_runtime::block_on(sessions(path.to_string_lossy().into())).is_err());
        assert!(!path.exists());
        assert!(
            tauri::async_runtime::block_on(diagnose(path.to_string_lossy().into(), String::new()))
                .is_err()
        );
    }
}
