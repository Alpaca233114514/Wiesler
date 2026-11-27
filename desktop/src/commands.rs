use serde::Serialize;
use std::{
    collections::HashSet,
    path::{Path, PathBuf},
    time::{Duration, Instant, UNIX_EPOCH},
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
    let secret = wiesler::config::controller_secret(&controller);
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
pub async fn system_snapshot() -> Result<wiesler::sockets::SocketSnapshot, String> {
    tauri::async_runtime::spawn_blocking(|| {
        wiesler::sockets::collect_system().map_err(|e| e.to_string())
    })
    .await
    .map_err(|_| "读取系统连接失败".to_string())?
}

#[tauri::command]
pub async fn inspect_dns() -> Result<wiesler::probes::privacy::DnsInspection, String> {
    tauri::async_runtime::spawn_blocking(wiesler::probes::privacy::inspect)
        .await
        .map_err(|_| "DNS 检查任务失败".to_string())?
}

#[tauri::command]
pub async fn probe_https_204() -> Result<wiesler::probes::egress::HttpsProbeResult, String> {
    tauri::async_runtime::spawn_blocking(wiesler::probes::egress::probe_https_204)
        .await
        .map_err(|_| "HTTPS 204 探测任务失败".to_string())
}

#[tauri::command]
pub async fn egress_address(
    controller: Option<String>,
    via_proxy: bool,
) -> Result<wiesler::probes::egress::EgressAddress, String> {
    let proxy = if via_proxy {
        let controller = controller.ok_or("请先连接代理控制器")?;
        let secret = wiesler::config::controller_secret(&controller);
        let config = ControllerConfig::new(&controller, secret.as_deref(), Duration::from_secs(3))
            .map_err(|e| e.to_string())?;
        let probe = Mihomo::new(config).map_err(|_| "无法读取代理端口")?;
        Some(
            probe
                .http_proxy_address()
                .await
                .map_err(|e| e.to_string())?
                .ok_or("控制器未启用 HTTP/Mixed 代理端口")?,
        )
    } else {
        None
    };
    tauri::async_runtime::spawn_blocking(move || wiesler::probes::egress::measure(proxy.as_deref()))
        .await
        .map_err(|_| "出口探测任务失败".to_string())?
}

#[derive(Debug, Serialize)]
pub struct DiscoveredDatabase {
    pub path: String,
    pub name: String,
    pub directory: String,
    pub modified_at_utc: u64,
    pub size_bytes: u64,
    pub session_count: usize,
    pub latest_session_at_utc: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct DatabaseDiscoveryReport {
    pub databases: Vec<DiscoveredDatabase>,
    pub scanned_dirs: Vec<String>,
}

fn clean_display_path(path: &Path) -> PathBuf {
    let text = path.to_string_lossy();
    if let Some(rest) = text.strip_prefix(r"\\?\UNC\") {
        PathBuf::from(format!(r"\\{rest}"))
    } else if let Some(rest) = text.strip_prefix(r"\\?\") {
        PathBuf::from(rest)
    } else {
        path.to_path_buf()
    }
}

fn has_sqlite_extension(path: &Path) -> bool {
    path.extension()
        .and_then(|ext| ext.to_str())
        .is_some_and(|ext| {
            ext.eq_ignore_ascii_case("sqlite3")
                || ext.eq_ignore_ascii_case("sqlite")
                || ext.eq_ignore_ascii_case("db")
        })
}

fn is_ignored_subdir(name: &str) -> bool {
    name.starts_with('.')
        || name.eq_ignore_ascii_case("target")
        || name.eq_ignore_ascii_case("node_modules")
        || name.eq_ignore_ascii_case("WebView2")
}

fn push_dir_pair(dirs: &mut Vec<(PathBuf, bool)>, base: PathBuf, explicit: bool) {
    dirs.push((base.join("data"), explicit));
    dirs.push((base, explicit));
}

fn candidate_database_dirs(extra_dirs: &[String]) -> Vec<(PathBuf, bool)> {
    let mut dirs = Vec::new();

    for raw in extra_dirs {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            continue;
        }
        let path = PathBuf::from(trimmed);
        if path.is_dir() {
            push_dir_pair(&mut dirs, path, true);
        } else if let Some(parent) = path.parent().filter(|p| p.is_dir()) {
            dirs.push((parent.to_path_buf(), true));
        }
    }

    for key in ["WIESLER_DATA_DIR", "WIESLER_DB_DIR"] {
        if let Some(dir) = std::env::var_os(key).filter(|v| !v.is_empty()) {
            push_dir_pair(&mut dirs, PathBuf::from(dir), true);
        }
    }

    if let Ok(cwd) = std::env::current_dir() {
        push_dir_pair(&mut dirs, cwd, false);
    }

    if let Ok(exe) = std::env::current_exe()
        && let Some(exe_dir) = exe.parent()
    {
        push_dir_pair(&mut dirs, exe_dir.to_path_buf(), false);
        // 支持从 target/release 或 dist/<bundle> 启动时向上定位仓库根目录的 data/。
        // Walk up a few ancestors when launched from build or portable bundle subdirectories.
        for ancestor in exe_dir.ancestors().skip(1).take(4) {
            if ancestor.join("data").is_dir() || ancestor.join("Cargo.toml").is_file() {
                push_dir_pair(&mut dirs, ancestor.to_path_buf(), false);
            }
        }
    }

    for (env_key, folder) in [
        ("LOCALAPPDATA", "Wiesler"),
        ("APPDATA", "Wiesler"),
        ("XDG_DATA_HOME", "wiesler"),
    ] {
        if let Some(base) = std::env::var_os(env_key).filter(|v| !v.is_empty()) {
            push_dir_pair(&mut dirs, PathBuf::from(base).join(folder), false);
        }
    }

    let home = std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .filter(|v| !v.is_empty())
        .map(PathBuf::from);
    if let Some(home) = home {
        push_dir_pair(&mut dirs, home.join(".wiesler"), false);
        push_dir_pair(&mut dirs, home.join("Wiesler"), false);
        push_dir_pair(&mut dirs, home.join("Documents").join("Wiesler"), false);
        push_dir_pair(
            &mut dirs,
            home.join("Documents").join("GitHub").join("Wiesler"),
            false,
        );
        push_dir_pair(&mut dirs, home.join("Desktop"), false);
        dirs.push((home.join("Downloads"), false));
    }

    dirs
}

fn scan_directory(
    dir: &Path,
    allow_subdirs: bool,
    seen_files: &mut HashSet<PathBuf>,
    databases: &mut Vec<DiscoveredDatabase>,
) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    let mut subdirs = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(file_type) = entry.file_type() else {
            continue;
        };
        if file_type.is_dir() {
            if allow_subdirs
                && subdirs.len() < 32
                && let Some(name) = path.file_name().and_then(|n| n.to_str())
                && !is_ignored_subdir(name)
            {
                subdirs.push(path);
            }
            continue;
        }
        if !file_type.is_file() || !has_sqlite_extension(&path) {
            continue;
        }
        if !wiesler::store::is_wiesler_database(&path) {
            continue;
        }
        let canonical = std::fs::canonicalize(&path).unwrap_or(path);
        if !seen_files.insert(canonical.clone()) {
            continue;
        }
        let Ok(sessions) = wiesler::store::sessions(&canonical) else {
            continue;
        };
        let display = clean_display_path(&canonical);
        let meta = std::fs::metadata(&canonical).ok();
        let modified_at_utc = meta
            .as_ref()
            .and_then(|m| m.modified().ok())
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map(|d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX))
            .unwrap_or(0);
        let size_bytes = meta.as_ref().map(|m| m.len()).unwrap_or(0);
        let name = display
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        let directory = display
            .parent()
            .map(|p| p.display().to_string())
            .unwrap_or_default();
        let latest_session_at_utc = sessions.iter().map(|s| s.started_at_utc).max();
        databases.push(DiscoveredDatabase {
            path: display.display().to_string(),
            name,
            directory,
            modified_at_utc,
            size_bytes,
            session_count: sessions.len(),
            latest_session_at_utc,
        });
    }
    for subdir in subdirs {
        scan_directory(&subdir, false, seen_files, databases);
    }
}

fn discover_databases_in(dirs: &[(PathBuf, bool)]) -> DatabaseDiscoveryReport {
    let mut seen_dirs = HashSet::new();
    let mut seen_files = HashSet::new();
    let mut scanned_dirs = Vec::new();
    let mut databases = Vec::new();

    for (dir, explicit) in dirs {
        if !dir.is_dir() {
            continue;
        }
        let canonical = std::fs::canonicalize(dir).unwrap_or_else(|_| dir.clone());
        if !seen_dirs.insert(canonical.clone()) {
            continue;
        }
        scanned_dirs.push(clean_display_path(&canonical).display().to_string());
        let is_data_dir = canonical
            .file_name()
            .and_then(|n| n.to_str())
            .is_some_and(|n| n.eq_ignore_ascii_case("data"));
        scan_directory(
            &canonical,
            *explicit || is_data_dir,
            &mut seen_files,
            &mut databases,
        );
    }

    databases.sort_by(|a, b| {
        b.latest_session_at_utc
            .cmp(&a.latest_session_at_utc)
            .then_with(|| b.modified_at_utc.cmp(&a.modified_at_utc))
            .then_with(|| a.path.cmp(&b.path))
    });
    databases.truncate(100);

    DatabaseDiscoveryReport {
        databases,
        scanned_dirs,
    }
}

#[tauri::command]
pub async fn discover_databases(
    extra_dirs: Option<Vec<String>>,
) -> Result<DatabaseDiscoveryReport, String> {
    let extra_dirs = extra_dirs.unwrap_or_default();
    tauri::async_runtime::spawn_blocking(move || {
        let dirs = candidate_database_dirs(&extra_dirs);
        discover_databases_in(&dirs)
    })
    .await
    .map_err(|_| "扫描本机证据库失败".to_string())
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
    #[ignore = "requires internet and the local proxy controller"]
    fn real_egress_acceptance() {
        tauri::async_runtime::block_on(async {
            let controller = wiesler::config::local_controller().unwrap().address;
            let system = tauri::async_runtime::spawn(egress_address(None, false));
            let proxy = egress_address(Some(controller), true).await;
            let system = system.await.unwrap();
            for (name, result) in [("system", &system), ("proxy", &proxy)] {
                match result {
                    Ok(value) => println!(
                        "{name}: ip={} location={:?} location_error={:?}",
                        value.ip, value.location, value.location_error
                    ),
                    Err(error) => println!("{name}: {error}"),
                }
            }
            assert!(
                system.is_ok(),
                "system egress: {}",
                system.err().unwrap_or_default()
            );
            assert!(
                proxy.is_ok(),
                "proxy egress: {}",
                proxy.err().unwrap_or_default()
            );
            let system = system.unwrap();
            let proxy = proxy.unwrap();
            assert!(
                system.location.is_some(),
                "system geography: {:?}",
                system.location_error
            );
            assert!(
                proxy.location.is_some(),
                "proxy geography: {:?}",
                proxy.location_error
            );
            println!("system_egress=ok proxy_egress=ok system_geography=ok proxy_geography=ok");
        });
    }

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

    #[test]
    fn discovers_only_valid_wiesler_databases_sorted_by_latest_session() {
        let root = std::env::temp_dir().join(format!(
            "wiesler-db-discover-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let data_dir = root.join("data");
        let nested_dir = data_dir.join("2026-11-19");
        let ignored_dir = data_dir.join("target");
        std::fs::create_dir_all(&nested_dir).unwrap();
        std::fs::create_dir_all(&ignored_dir).unwrap();

        let older = data_dir.join("older.sqlite3");
        {
            let mut store = wiesler::store::Store::open(&older, 100).unwrap();
            let mut machine = wiesler::core::connection::StateMachine::new("session-old".into());
            store
                .start(&machine.start(
                    Stamp {
                        utc_ms: 1_000,
                        monotonic_ms: 0,
                    },
                    1000,
                ))
                .unwrap();
        }
        let newer = nested_dir.join("newer.db");
        {
            let mut store = wiesler::store::Store::open(&newer, 100).unwrap();
            let mut machine = wiesler::core::connection::StateMachine::new("session-new".into());
            store
                .start(&machine.start(
                    Stamp {
                        utc_ms: 5_000,
                        monotonic_ms: 0,
                    },
                    1000,
                ))
                .unwrap();
        }
        let ignored_db = ignored_dir.join("ignored.sqlite3");
        let _ignored_store = wiesler::store::Store::open(&ignored_db, 100).unwrap();

        let foreign = data_dir.join("foreign.sqlite3");
        std::fs::write(
            &foreign,
            b"SQLite format 3\0not-wiesler-content-padding-000000000000000000000000000000000000",
        )
        .unwrap();
        std::fs::write(data_dir.join("notes.txt"), b"hello").unwrap();

        let report = discover_databases_in(&[
            (data_dir.clone(), false),
            (data_dir.clone(), false),
            (root.clone(), false),
        ]);
        assert_eq!(report.databases.len(), 2);
        assert_eq!(report.databases[0].name, "newer.db");
        assert_eq!(report.databases[0].session_count, 1);
        assert_eq!(report.databases[0].latest_session_at_utc, Some(5_000));
        assert_eq!(report.databases[1].name, "older.sqlite3");
        assert_eq!(report.databases[1].latest_session_at_utc, Some(1_000));
        assert!(!report.databases[0].path.starts_with(r"\\?\"));

        let _ = std::fs::remove_dir_all(&root);
    }
}
