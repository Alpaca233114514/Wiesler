use std::{
    fs,
    io::{Read, Write},
    net::TcpListener,
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use wiesler::{
    config::ControllerConfig,
    probes::soak::{SoakOptions, run},
};

const GROUP: &str = "🔰 节点 /?选择";
const FIRST: &str = "🇨🇦 候选 /?一";
const SECOND: &str = "🇹🇼 候选二";

#[derive(Default)]
struct State {
    current: String,
    writes: Vec<String>,
    requests: Vec<String>,
    proxy_requests: usize,
    download_size: usize,
    ping_status: u16,
    slow_download: bool,
    tamper: bool,
    reject_second: bool,
    deny_restore: bool,
}

struct Server {
    address: String,
    state: Arc<Mutex<State>>,
    stop: Arc<AtomicBool>,
    worker: Option<std::thread::JoinHandle<()>>,
}

fn proxy_path(name: &str) -> String {
    let mut url = reqwest::Url::parse("http://127.0.0.1").unwrap();
    url.path_segments_mut().unwrap().extend(["proxies", name]);
    url.path().to_owned()
}

impl Server {
    fn new() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let address = format!("http://{}", listener.local_addr().unwrap());
        let state = Arc::new(Mutex::new(State {
            current: "original".into(),
            download_size: 64,
            ping_status: 200,
            ..State::default()
        }));
        let stop = Arc::new(AtomicBool::new(false));
        let state_copy = state.clone();
        let stop_copy = stop.clone();
        let worker = std::thread::spawn(move || {
            while !stop_copy.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        stream.set_nonblocking(false).unwrap();
                        let state = state_copy.clone();
                        std::thread::spawn(move || {
                            stream
                                .set_read_timeout(Some(Duration::from_secs(2)))
                                .unwrap();
                            let mut bytes = Vec::new();
                            let mut byte = [0];
                            while !bytes.ends_with(b"\r\n\r\n") {
                                if stream.read(&mut byte).unwrap_or(0) == 0 {
                                    return;
                                }
                                bytes.push(byte[0]);
                            }
                            let headers = String::from_utf8(bytes).unwrap();
                            let line = headers.lines().next().unwrap().to_owned();
                            let length = headers
                                .lines()
                                .find_map(|s| {
                                    s.to_lowercase()
                                        .strip_prefix("content-length:")
                                        .and_then(|n| n.trim().parse::<usize>().ok())
                                })
                                .unwrap_or(0);
                            let mut body = vec![0; length];
                            if stream.read_exact(&mut body).is_err() {
                                return;
                            }
                            let parts: Vec<_> = line.split_whitespace().collect();
                            let method = parts[0];
                            let path = parts[1];
                            let mut s = state.lock().unwrap();
                            s.requests.push(line.clone());
                            let (status, body, delay) = if path
                                .starts_with("http://example.invalid/")
                            {
                                s.proxy_requests += 1;
                                assert!(!headers.to_lowercase().contains("authorization:"));
                                if path.ends_with("/download") {
                                    let size = s.download_size;
                                    if s.tamper {
                                        s.current = "outside".into();
                                    }
                                    (
                                        200,
                                        vec![b'x'; size],
                                        if s.slow_download { 1500 } else { 0 },
                                    )
                                } else {
                                    (s.ping_status, b"ping".to_vec(), 0)
                                }
                            } else {
                                assert!(
                                    headers
                                        .to_lowercase()
                                        .contains("authorization: bearer synthetic-secret\r\n")
                                );
                                if path == "/version" {
                                    (200, br#"{"version":"synthetic-v1"}"#.to_vec(), 0)
                                } else if path == proxy_path(GROUP) {
                                    if method == "PUT" {
                                        let name =
                                            serde_json::from_slice::<serde_json::Value>(&body)
                                                .unwrap()["name"]
                                                .as_str()
                                                .unwrap()
                                                .to_owned();
                                        s.writes.push(name.clone());
                                        if (s.reject_second && name == SECOND)
                                            || (s.deny_restore && name == "original")
                                        {
                                            (403, b"private synthetic failure".to_vec(), 0)
                                        } else {
                                            s.current = name;
                                            (204, Vec::new(), 0)
                                        }
                                    } else {
                                        (200, serde_json::to_vec(&serde_json::json!({"type":"Selector", "now":s.current,
                                            "all":["original", FIRST, SECOND]})).unwrap(), 0)
                                    }
                                } else if ["original", FIRST, SECOND]
                                    .iter()
                                    .any(|n| path == proxy_path(n))
                                {
                                    (200, br#"{"type":"Shadowsocks"}"#.to_vec(), 0)
                                } else {
                                    (404, Vec::new(), 0)
                                }
                            };
                            drop(s);
                            std::thread::sleep(Duration::from_millis(delay));
                            let response = format!(
                                "HTTP/1.1 {status} Test\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                                body.len()
                            );
                            let _ = stream.write_all(response.as_bytes());
                            let _ = stream.write_all(&body);
                        });
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(2))
                    }
                    Err(error) => panic!("mock accept failed: {error}"),
                }
            }
        });
        Self {
            address,
            state,
            stop,
            worker: Some(worker),
        }
    }

    fn options(&self) -> SoakOptions {
        static SEQUENCE: AtomicU64 = AtomicU64::new(0);
        let unique = format!(
            "{}-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        );
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("target/soak-tests")
            .join(unique);
        fs::create_dir_all(&dir).unwrap();
        SoakOptions {
            controller: ControllerConfig::new(
                &self.address,
                Some("synthetic-secret"),
                Duration::from_secs(2),
            )
            .unwrap(),
            proxy: self.address.clone(),
            group: GROUP.into(),
            from: None,
            nodes: vec![FIRST.into(), SECOND.into()],
            top: 5,
            apply: true,
            dry_run: false,
            bytes: 64,
            pings: 3,
            download_url: "http://example.invalid/download".into(),
            url: "http://example.invalid/ping".into(),
            download_timeout: Duration::from_secs(3),
            ping_timeout: Duration::from_secs(1),
            output: dir.join("report.json"),
            lock_file: dir.join("soak.lock"),
        }
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        self.worker.take().unwrap().join().unwrap();
    }
}

fn flag() -> Arc<AtomicBool> {
    Arc::new(AtomicBool::new(false))
}

#[tokio::test]
async fn preview_sorts_existing_input_without_mutation_or_traffic() {
    let server = Server::new();
    let mut options = server.options();
    let input = options.output.with_file_name("stable.json");
    fs::write(
        &input,
        serde_json::to_vec(&serde_json::json!([
            {"name": FIRST, "ok": 11, "fail": 1, "p90": 10, "median": 5},
            {"name": SECOND, "ok": 12, "fail": 0, "p90": 20, "median": 10}
        ]))
        .unwrap(),
    )
    .unwrap();
    options.nodes.clear();
    options.from = Some(input);
    options.apply = false;
    let report = run(options, flag()).await.unwrap();
    assert_eq!(report.status, "preview");
    assert_eq!(report.candidates, [SECOND, FIRST]);
    let s = server.state.lock().unwrap();
    assert!(s.writes.is_empty());
    assert_eq!(s.proxy_requests, 0);
}

#[tokio::test]
async fn batch_uses_encoded_names_exact_bytes_and_restores_original() {
    let server = Server::new();
    let options = server.options();
    let output = options.output.clone();
    let report = run(options, flag()).await.unwrap();
    assert_eq!(report.status, "completed");
    assert_eq!(report.restoration, "restored");
    assert!(!report.route_verified);
    assert_eq!(report.selection_changes.len(), 2);
    for row in &report.results {
        assert!(row.summary.soak_ok);
        assert_eq!(row.summary.soak_bytes, 64);
        assert_eq!(row.summary.ping_ok, 3);
        assert_eq!(row.summary.ping_fail, 0);
        assert!(row.summary.p90_ms.unwrap() >= row.summary.median_ms.unwrap());
        assert_eq!(
            row.ping_samples.iter().map(|s| s.round).collect::<Vec<_>>(),
            [1, 2, 3]
        );
    }
    let saved = fs::read_to_string(output).unwrap();
    assert!(!saved.contains("synthetic-secret"));
    assert!(!saved.contains("private synthetic failure"));
    let state = server.state.lock().unwrap();
    assert_eq!(state.current, "original");
    assert_eq!(state.writes, [FIRST, SECOND, "original"]);
    assert_eq!(state.proxy_requests, 8);
    assert!(state.requests.iter().any(|r| r.contains("%2F%3F")));
}

#[tokio::test]
async fn current_route_never_writes_selection() {
    let server = Server::new();
    let mut options = server.options();
    options.nodes.clear();
    options.apply = false;
    let report = run(options, flag()).await.unwrap();
    assert_eq!(report.mode, "current_route");
    assert_eq!(report.status, "completed");
    assert_eq!(report.restoration, "not_needed");
    assert!(server.state.lock().unwrap().writes.is_empty());
}

#[tokio::test]
async fn short_download_and_http_errors_are_failures_with_null_statistics() {
    let server = Server::new();
    {
        let mut state = server.state.lock().unwrap();
        state.download_size = 32;
        state.ping_status = 503;
    }
    let report = run(server.options(), flag()).await.unwrap();
    assert_eq!(report.status, "completed_with_failures");
    assert_eq!(report.restoration, "restored");
    for row in report.results {
        assert!(!row.summary.soak_ok);
        assert_eq!(
            row.soak.unwrap().error_class.as_deref(),
            Some("size_mismatch")
        );
        assert_eq!(row.summary.ping_fail, 3);
        assert!(row.summary.median_ms.is_none());
        assert!(
            row.ping_samples
                .iter()
                .all(|s| s.error_class.as_deref() == Some("http_5xx"))
        );
    }
}

#[tokio::test]
async fn cancellation_kills_active_transfer_saves_partial_report_and_restores() {
    let server = Server::new();
    server.state.lock().unwrap().slow_download = true;
    let cancel = flag();
    let setter = cancel.clone();
    let state = server.state.clone();
    let worker = std::thread::spawn(move || {
        for _ in 0..500 {
            if state.lock().unwrap().proxy_requests > 0 {
                setter.store(true, Ordering::Relaxed);
                return;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        panic!("transfer did not start");
    });
    let report = run(server.options(), cancel).await.unwrap();
    worker.join().unwrap();
    assert_eq!(report.status, "cancelled");
    assert_eq!(report.restoration, "restored");
    assert_eq!(report.results.len(), 1);
    assert_eq!(
        report.results[0]
            .soak
            .as_ref()
            .unwrap()
            .error_class
            .as_deref(),
        Some("cancelled")
    );
    assert_eq!(server.state.lock().unwrap().current, "original");
}

#[tokio::test]
async fn external_selection_change_is_reported_and_preserved() {
    let server = Server::new();
    server.state.lock().unwrap().tamper = true;
    let report = run(server.options(), flag()).await.unwrap();
    assert_eq!(report.status, "incomplete");
    assert_eq!(report.restoration, "conflict");
    assert!(!report.results[0].group_selection_unchanged);
    let state = server.state.lock().unwrap();
    assert_eq!(state.current, "outside");
    assert_eq!(state.writes, [FIRST]);
}

#[tokio::test]
async fn rejected_second_switch_still_restores_first_switch() {
    let server = Server::new();
    server.state.lock().unwrap().reject_second = true;
    let report = run(server.options(), flag()).await.unwrap();
    assert_eq!(report.status, "incomplete");
    assert_eq!(report.restoration, "restored");
    assert_eq!(server.state.lock().unwrap().current, "original");
}

#[tokio::test]
async fn restore_failure_is_explicit_and_does_not_claim_success() {
    let server = Server::new();
    server.state.lock().unwrap().deny_restore = true;
    let report = run(server.options(), flag()).await.unwrap();
    assert_eq!(report.status, "incomplete");
    assert_eq!(report.restoration, "failed");
    assert_eq!(server.state.lock().unwrap().current, SECOND);
}

#[tokio::test]
async fn existing_report_and_busy_lock_block_before_any_mutation() {
    let server = Server::new();
    let options = server.options();
    fs::write(&options.output, "keep existing evidence").unwrap();
    let output = options.output.clone();
    assert!(run(options, flag()).await.is_err());
    assert_eq!(
        fs::read_to_string(output).unwrap(),
        "keep existing evidence"
    );
    let options = server.options();
    let lock = fs::OpenOptions::new()
        .create_new(true)
        .read(true)
        .write(true)
        .open(&options.lock_file)
        .unwrap();
    lock.try_lock().unwrap();
    assert!(run(options, flag()).await.is_err());
    assert!(server.state.lock().unwrap().writes.is_empty());
    assert_eq!(server.state.lock().unwrap().proxy_requests, 0);
}

#[tokio::test]
async fn transfer_timeout_is_classified_and_original_is_restored() {
    let server = Server::new();
    server.state.lock().unwrap().slow_download = true;
    let mut options = server.options();
    options.nodes.truncate(1);
    options.download_timeout = Duration::from_millis(100);
    let report = run(options, flag()).await.unwrap();
    assert_eq!(report.status, "completed_with_failures");
    assert_eq!(report.restoration, "restored");
    assert_eq!(
        report.results[0]
            .soak
            .as_ref()
            .unwrap()
            .error_class
            .as_deref(),
        Some("timeout")
    );
}

#[test]
fn cli_executes_through_explicit_proxy_and_returns_nonzero_for_failed_samples() {
    let server = Server::new();
    for expected_code in [0, 1] {
        if expected_code == 1 {
            server.state.lock().unwrap().ping_status = 503;
        }
        let options = server.options();
        let output = std::process::Command::new(env!("CARGO_BIN_EXE_wiesler"))
            .args([
                "nodes",
                "soak",
                "--controller",
                &server.address,
                "--proxy",
                &server.address,
                "--group",
                GROUP,
                "--node",
                FIRST,
                "--apply",
                "--bytes",
                "64",
                "--pings",
                "2",
                "--download-url",
                "http://example.invalid/download",
                "--url",
                "http://example.invalid/ping",
            ])
            .arg("--out")
            .arg(&options.output)
            .arg("--lock-file")
            .arg(&options.lock_file)
            .env("WIESLER_MIHOMO_SECRET", "synthetic-secret")
            .env("NO_PROXY", "*")
            .output()
            .unwrap();
        assert_eq!(
            output.status.code(),
            Some(expected_code),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let report: serde_json::Value =
            serde_json::from_slice(&fs::read(options.output).unwrap()).unwrap();
        assert_eq!(report["restoration"], "restored");
        assert_eq!(
            report["results"][0]["summary"]["ping_ok"],
            if expected_code == 0 { 2 } else { 0 }
        );
        assert_eq!(server.state.lock().unwrap().current, "original");
    }
}
