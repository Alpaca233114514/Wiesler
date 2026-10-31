use super::mihomo::Mihomo;
use anyhow::{Context, Result, bail, ensure};
use reqwest::Url;
use serde::{Deserialize, Serialize};
use std::{
    fs::{File, OpenOptions},
    io::{Seek, Write},
    path::PathBuf,
    process::{Command, Stdio},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

pub struct SoakOptions {
    pub controller: crate::config::ControllerConfig,
    pub proxy: String,
    pub group: String,
    pub from: Option<PathBuf>,
    pub nodes: Vec<String>,
    pub top: usize,
    pub apply: bool,
    pub dry_run: bool,
    pub bytes: u64,
    pub pings: u32,
    pub download_url: String,
    pub url: String,
    pub download_timeout: Duration,
    pub ping_timeout: Duration,
    pub output: PathBuf,
    pub lock_file: PathBuf,
}

#[derive(Debug, Deserialize)]
struct Candidate {
    name: String,
    ok: u32,
    fail: u32,
    p90: Option<f64>,
    median: Option<f64>,
}

#[derive(Debug, Serialize)]
pub struct SoakReport {
    pub schema: u32,
    pub measured_at_utc_ms: u64,
    pub finished_at_utc_ms: Option<u64>,
    pub status: String,
    pub error_class: Option<String>,
    pub controller_version: String,
    pub controller: String,
    pub proxy: String,
    pub group: String,
    pub original_selection: String,
    pub expected_selection: String,
    pub restoration: String,
    pub mode: String,
    pub input: Option<PathBuf>,
    pub input_freshness: String,
    pub candidates: Vec<String>,
    pub measurement: String,
    pub concurrency: u32,
    pub download_url: String,
    pub url: String,
    pub timeout_ms: u128,
    pub download_timeout_ms: u128,
    pub bytes: u64,
    pub pings: u32,
    pub route_verified: bool,
    pub selection_changes: Vec<SelectionChange>,
    pub results: Vec<NodeResult>,
}

#[derive(Debug, Serialize)]
pub struct SelectionChange {
    pub measured_at_utc_ms: u64,
    pub from: String,
    pub to: String,
    pub status: String,
}

#[derive(Debug, Serialize)]
pub struct NodeResult {
    pub name: String,
    pub group_selection_unchanged: bool,
    pub soak: Option<Sample>,
    pub ping_samples: Vec<Sample>,
    pub summary: Summary,
}

#[derive(Debug, Serialize, Default)]
pub struct Summary {
    pub soak_ok: bool,
    pub soak_bytes: u64,
    pub soak_kibps: Option<f64>,
    pub ping_ok: usize,
    pub ping_fail: usize,
    pub median_ms: Option<f64>,
    pub p90_ms: Option<f64>,
    pub max_ms: Option<f64>,
    pub stdev_ms: Option<f64>,
}

#[derive(Debug, Serialize)]
pub struct Sample {
    pub measured_at_utc_ms: u64,
    pub round: u32,
    pub ok: bool,
    pub error_class: Option<String>,
    pub exit_code: Option<i32>,
    pub http_status: Option<u16>,
    pub elapsed_ms: f64,
    pub bytes: u64,
    pub kibps: f64,
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn target_url(value: &str) -> Result<()> {
    let url = Url::parse(value).map_err(|_| anyhow::anyhow!("测试 URL 无效"))?;
    ensure!(
        matches!(url.scheme(), "http" | "https")
            && url.host_str().is_some()
            && url.username().is_empty()
            && url.password().is_none()
            && url.fragment().is_none(),
        "测试 URL 仅支持无凭证、无 fragment 的 HTTP/HTTPS 地址"
    );
    Ok(())
}

impl SoakOptions {
    fn validate(&self) -> Result<()> {
        crate::config::ControllerConfig::new(&self.proxy, None, Duration::from_secs(1))
            .context("--proxy 必须是回环 HTTP 代理根地址")?;
        target_url(&self.download_url)?;
        target_url(&self.url)?;
        ensure!(
            self.bytes > 0 && self.pings > 0 && self.top > 0,
            "bytes、pings、top 必须大于零"
        );
        ensure!(
            !self.download_timeout.is_zero() && !self.ping_timeout.is_zero(),
            "超时必须大于零"
        );
        ensure!(
            self.from.is_none() || self.nodes.is_empty(),
            "--from 和 --node 不能同时使用"
        );
        ensure!(
            !self.apply || (!self.dry_run && (self.from.is_some() || !self.nodes.is_empty())),
            "--apply 需要 --from 或 --node，且不能与 --dry-run 同用"
        );
        ensure!(!self.group.trim().is_empty(), "代理组不能为空");
        Ok(())
    }

    fn candidates(&self, original: &str) -> Result<Vec<String>> {
        let names = if let Some(path) = &self.from {
            let mut rows: Vec<Candidate> = serde_json::from_reader(File::open(path)?)
                .context("--from 需要 stable.json 格式的数组：name、ok、fail、p90、median")?;
            rows.retain(|r| r.ok > 0);
            rows.sort_by(|a, b| {
                a.fail
                    .cmp(&b.fail)
                    .then_with(|| {
                        a.p90
                            .unwrap_or(f64::INFINITY)
                            .total_cmp(&b.p90.unwrap_or(f64::INFINITY))
                    })
                    .then_with(|| {
                        a.median
                            .unwrap_or(f64::INFINITY)
                            .total_cmp(&b.median.unwrap_or(f64::INFINITY))
                    })
                    .then_with(|| a.name.cmp(&b.name))
            });
            let mut seen = std::collections::BTreeSet::new();
            rows.into_iter()
                .map(|r| r.name)
                .filter(|n| seen.insert(n.clone()))
                .take(self.top)
                .collect()
        } else if !self.nodes.is_empty() {
            self.nodes.clone()
        } else {
            vec![original.to_owned()]
        };
        ensure!(!names.is_empty(), "没有可浸泡的候选节点");
        let mut seen = std::collections::BTreeSet::new();
        ensure!(
            names.iter().all(|n| !n.trim().is_empty() && seen.insert(n)),
            "节点名为空或重复"
        );
        Ok(names)
    }
}

fn checkpoint(file: &mut File, report: &SoakReport) -> Result<()> {
    file.rewind()?;
    serde_json::to_writer_pretty(&mut *file, report)?;
    file.write_all(b"\n")?;
    let end = file.stream_position()?;
    file.set_len(end)?;
    file.sync_all()?;
    Ok(())
}

async fn selection(controller: &Mihomo, group: &str) -> Result<String> {
    let info = controller.proxy(group).await?;
    ensure!(
        info.proxy_type == "Selector",
        "仅支持 Selector 组，避免自动选路污染归属"
    );
    info.now
        .filter(|n| !n.is_empty())
        .context("控制器没有返回当前组选择")
}

/// 仅切换模式会写控制器；无论测量结果如何都走统一恢复路径。 / Mutation mode always reaches restoration.
pub async fn run(options: SoakOptions, cancelled: Arc<AtomicBool>) -> Result<SoakReport> {
    options.validate()?;
    let switching = options.from.is_some() || !options.nodes.is_empty();
    let preview = options.dry_run || (switching && !options.apply);
    let _lock = if !preview {
        if let Some(parent) = options
            .lock_file
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
        {
            std::fs::create_dir_all(parent)?;
        }
        let file = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(&options.lock_file)?;
        file.try_lock()
            .context("已有浸泡测试占用锁；所有实例应使用同一个 --lock-file")?;
        Some(file)
    } else {
        None
    };
    let controller_address = options.controller.url.to_string();
    let controller = Mihomo::new(crate::config::ControllerConfig {
        url: options.controller.url.clone(),
        authorization: options.controller.authorization.clone(),
        timeout: options.controller.timeout,
    })?;
    let version = controller.version().await?;
    let original = selection(&controller, &options.group).await?;
    let candidates = options.candidates(&original)?;
    let group_info = controller.proxy(&options.group).await?;
    for name in &candidates {
        ensure!(
            group_info.all.contains(name),
            "候选节点不属于指定组：{name}"
        );
        let info = controller.proxy(name).await?;
        ensure!(
            info.all.is_empty()
                && !matches!(
                    info.proxy_type.as_str(),
                    "Selector"
                        | "URLTest"
                        | "Fallback"
                        | "LoadBalance"
                        | "Relay"
                        | "Direct"
                        | "Reject"
                        | "RejectDrop"
                        | "Pass"
                        | "Compatible"
                ),
            "候选必须为具体出口节点：{name}"
        );
    }
    let mut report = SoakReport {
        schema: 1,
        measured_at_utc_ms: now_ms(),
        finished_at_utc_ms: None,
        status: if preview { "preview" } else { "running" }.into(),
        error_class: None,
        controller_version: version,
        controller: controller_address,
        proxy: options.proxy.clone(),
        group: options.group.clone(),
        original_selection: original.clone(),
        expected_selection: original,
        restoration: "not_needed".into(),
        mode: if preview {
            "preview"
        } else if switching {
            "selector_switch"
        } else {
            "current_route"
        }
        .into(),
        input: options.from.clone(),
        input_freshness: "unknown_not_verified".into(),
        candidates,
        measurement: "curl_wall_clock_ms_fresh_connection_no_retry".into(),
        concurrency: 1,
        download_url: options.download_url.clone(),
        url: options.url.clone(),
        timeout_ms: options.ping_timeout.as_millis(),
        download_timeout_ms: options.download_timeout.as_millis(),
        bytes: options.bytes,
        pings: options.pings,
        route_verified: false,
        selection_changes: Vec::new(),
        results: Vec::new(),
    };
    let mut file = OpenOptions::new()
        .read(true)
        .write(true)
        .create_new(true)
        .open(&options.output)
        .context("结果文件只能新建；请更换 --out 路径")?;
    checkpoint(&mut file, &report)?;
    if preview {
        return Ok(report);
    }

    // 在改变路由前确认 curl 可执行，-q 禁止读取用户 curlrc。 / Check curl before any route mutation; ignore curlrc.
    let available = Command::new(curl_program())
        .args(["-q", "--version"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|s| s.success());
    let result = if available {
        measure(
            &options,
            &controller,
            &mut report,
            &mut file,
            cancelled.clone(),
        )
        .await
    } else {
        Err(anyhow::anyhow!("curl_unavailable"))
    };
    if let Err(error) = result {
        report.status = "incomplete".into();
        report.error_class = Some(error.to_string());
    } else {
        report.status = if report
            .results
            .iter()
            .any(|r| !r.summary.soak_ok || r.summary.ping_fail > 0)
        {
            "completed_with_failures"
        } else {
            "completed"
        }
        .into();
    }
    if cancelled.load(Ordering::Relaxed) {
        report.status = "cancelled".into();
    }

    if report.restoration == "pending" {
        match selection(&controller, &options.group).await {
            Ok(current) if current == report.original_selection => {
                report.restoration = "restored".into()
            }
            Ok(current)
                if current == report.expected_selection
                    || report.selection_changes.last().is_some_and(|change| {
                        change.status == "requested" && current == change.from
                    }) =>
            {
                report.restoration = match controller
                    .select_proxy(&options.group, &report.original_selection)
                    .await
                {
                    Ok(()) => match selection(&controller, &options.group).await {
                        Ok(value) if value == report.original_selection => "restored",
                        _ => "failed",
                    },
                    Err(_) => "failed",
                }
                .into();
            }
            Ok(_) => report.restoration = "conflict".into(),
            Err(_) => report.restoration = "failed".into(),
        }
        if report.restoration != "restored" {
            report.status = "incomplete".into();
            report.error_class = Some("selection_restore_failed_or_conflict".into());
        }
    }
    report.finished_at_utc_ms = Some(now_ms());
    checkpoint(&mut file, &report)?;
    Ok(report)
}

async fn measure(
    options: &SoakOptions,
    controller: &Mihomo,
    report: &mut SoakReport,
    file: &mut File,
    cancelled: Arc<AtomicBool>,
) -> Result<()> {
    for name in report.candidates.clone() {
        if cancelled.load(Ordering::Relaxed) {
            break;
        }
        ensure!(
            selection(controller, &options.group).await? == report.expected_selection,
            "selection_changed"
        );
        if options.apply && name != report.expected_selection {
            report.selection_changes.push(SelectionChange {
                measured_at_utc_ms: now_ms(),
                from: report.expected_selection.clone(),
                to: name.clone(),
                status: "requested".into(),
            });
            report.expected_selection = name.clone();
            report.restoration = "pending".into();
            checkpoint(file, report)?;
            controller.select_proxy(&options.group, &name).await?;
        }
        ensure!(
            selection(controller, &options.group).await? == name,
            "selection_changed"
        );
        if let Some(change) = report.selection_changes.last_mut() {
            change.status = "confirmed".into();
        }
        report.results.push(NodeResult {
            name: name.clone(),
            group_selection_unchanged: true,
            soak: None,
            ping_samples: Vec::new(),
            summary: Summary::default(),
        });
        let index = report.results.len() - 1;
        let transfer = RequestSpec {
            proxy: options.proxy.clone(),
            url: options.download_url.clone(),
            timeout: options.download_timeout,
            expected_bytes: Some(options.bytes),
            round: 0,
        };
        report.results[index].soak = Some(request(transfer, cancelled.clone()).await?);
        report.results[index].summary = summarize(&report.results[index]);
        checkpoint(file, report)?;
        for round in 1..=options.pings {
            if cancelled.load(Ordering::Relaxed) {
                break;
            }
            ensure_unchanged(controller, options, report, file, index, &name).await?;
            let ping = RequestSpec {
                proxy: options.proxy.clone(),
                url: options.url.clone(),
                timeout: options.ping_timeout,
                expected_bytes: None,
                round,
            };
            let sample = request(ping, cancelled.clone()).await?;
            report.results[index].ping_samples.push(sample);
            report.results[index].summary = summarize(&report.results[index]);
            checkpoint(file, report)?;
        }
        ensure_unchanged(controller, options, report, file, index, &name).await?;
    }
    Ok(())
}

async fn ensure_unchanged(
    controller: &Mihomo,
    options: &SoakOptions,
    report: &mut SoakReport,
    file: &mut File,
    index: usize,
    name: &str,
) -> Result<()> {
    match selection(controller, &options.group).await {
        Ok(current) if current == name => Ok(()),
        _ => {
            report.results[index].group_selection_unchanged = false;
            checkpoint(file, report)?;
            bail!("selection_changed_or_unavailable")
        }
    }
}

struct RequestSpec {
    proxy: String,
    url: String,
    timeout: Duration,
    expected_bytes: Option<u64>,
    round: u32,
}

fn curl_program() -> &'static str {
    if cfg!(windows) { "curl.exe" } else { "curl" }
}

async fn request(spec: RequestSpec, cancelled: Arc<AtomicBool>) -> Result<Sample> {
    tokio::task::spawn_blocking(move || curl_request(spec, cancelled)).await?
}

fn curl_request(spec: RequestSpec, cancelled: Arc<AtomicBool>) -> Result<Sample> {
    let start = Instant::now();
    let mut sample = Sample {
        measured_at_utc_ms: now_ms(),
        round: spec.round,
        ok: false,
        error_class: None,
        exit_code: None,
        http_status: None,
        elapsed_ms: 0.0,
        bytes: 0,
        kibps: 0.0,
    };
    let mut command = Command::new(curl_program());
    command.args([
        "-q",
        "--silent",
        "--proxy",
        &spec.proxy,
        "--noproxy",
        "",
        "--proto",
        "=http,https",
        "--max-redirs",
        "0",
        "--retry",
        "0",
        "--max-time",
        &spec.timeout.as_secs_f64().to_string(),
        "--output",
        if cfg!(windows) { "nul" } else { "/dev/null" },
        "--write-out",
        "%{http_code} %{time_total} %{size_download} %{speed_download}",
    ]);
    if let Some(bytes) = spec.expected_bytes {
        command.args(["--max-filesize", &bytes.to_string()]);
    }
    command
        .arg("--url")
        .arg(&spec.url)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(_) => {
            sample.error_class = Some("curl_unavailable".into());
            return Ok(sample);
        }
    };
    let mut stopped = None;
    loop {
        if cancelled.load(Ordering::Relaxed) {
            stopped = Some("cancelled");
        } else if start.elapsed() > spec.timeout + Duration::from_secs(2) {
            stopped = Some("timeout");
        }
        if stopped.is_some() {
            let _ = child.kill();
            break;
        }
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => std::thread::sleep(Duration::from_millis(20)),
            Err(_) => {
                let _ = child.kill();
                stopped = Some("process_error");
                break;
            }
        }
    }
    let output = child
        .wait_with_output()
        .context("curl_process_wait_failed")?;
    sample.elapsed_ms = start.elapsed().as_secs_f64() * 1000.0;
    sample.exit_code = output.status.code();
    let metrics = String::from_utf8_lossy(&output.stdout);
    let values: Vec<&str> = metrics.split_whitespace().collect();
    if let [status, elapsed, bytes, speed] = values.as_slice() {
        sample.http_status = status.parse().ok();
        if let (Ok(elapsed), Ok(bytes), Ok(speed)) = (
            elapsed.parse::<f64>(),
            bytes.parse::<u64>(),
            speed.parse::<f64>(),
        ) && elapsed.is_finite()
            && elapsed >= 0.0
            && speed.is_finite()
            && speed >= 0.0
        {
            sample.elapsed_ms = elapsed * 1000.0;
            sample.bytes = bytes;
            sample.kibps = speed / 1024.0;
        } else {
            stopped = stopped.or(Some("invalid_metrics"));
        }
    } else {
        stopped = stopped.or(Some("invalid_metrics"));
    }
    let class = stopped.or_else(|| match sample.exit_code {
        Some(0) => match sample.http_status {
            Some(200..=299) if spec.expected_bytes.is_some_and(|n| sample.bytes != n) => {
                Some("size_mismatch")
            }
            Some(200..=299) => None,
            Some(400..=499) => Some("http_4xx"),
            Some(500..=599) => Some("http_5xx"),
            _ => Some("http_status"),
        },
        Some(28) => Some("timeout"),
        Some(35) => Some("tls_handshake"),
        Some(51 | 58 | 60 | 77 | 83 | 90 | 91) => Some("tls_certificate"),
        Some(5 | 6) => Some("dns"),
        Some(18) => Some("incomplete_transfer"),
        Some(56) => Some("receive_error"),
        Some(63) => Some("size_limit"),
        _ => Some("transport"),
    });
    sample.ok = class.is_none();
    sample.error_class = class.map(str::to_owned);
    Ok(sample)
}

fn summarize(result: &NodeResult) -> Summary {
    let mut values: Vec<f64> = result
        .ping_samples
        .iter()
        .filter(|s| s.ok)
        .map(|s| s.elapsed_ms)
        .collect();
    values.sort_by(f64::total_cmp);
    let n = values.len();
    let mut summary = Summary {
        ping_ok: n,
        ping_fail: result.ping_samples.len() - n,
        ..Summary::default()
    };
    if let Some(soak) = &result.soak {
        summary.soak_ok = soak.ok;
        summary.soak_bytes = soak.bytes;
        summary.soak_kibps = Some(soak.kibps);
    }
    if n > 0 {
        summary.median_ms = Some((values[(n - 1) / 2] + values[n / 2]) / 2.0);
        summary.p90_ms = Some(values[(n * 9).div_ceil(10) - 1]);
        summary.max_ms = values.last().copied();
        let mean = values.iter().sum::<f64>() / n as f64;
        summary.stdev_ms =
            Some((values.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / n as f64).sqrt());
    }
    summary
}
