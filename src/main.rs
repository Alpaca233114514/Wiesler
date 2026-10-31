use clap::{CommandFactory, Parser, Subcommand};
use std::{
    fs::OpenOptions,
    io::{BufWriter, Write},
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use wiesler::{
    config::ControllerConfig,
    diagnostics,
    observer::{LogKind, ObserveOptions, observe},
    probes::soak::{SoakOptions, run as soak},
    recorder::{RecordOptions, record},
    store,
};

#[derive(Parser)]
#[command(
    version,
    about = "Wiesler — 本地网络与代理可观测性工具",
    after_help = "观察命令只读；nodes soak --apply 会临时切换代理组并恢复。控制器密钥仅从 WIESLER_MIHOMO_SECRET 环境变量读取。"
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Subcommand)]
enum Command {
    /// 节点主动测试；批量切换需要显式 --apply
    Nodes {
        #[command(subcommand)]
        command: NodeCommand,
    },
    /// 持续观察 DSHObserver / Codex 日志及 Windows TCP；--polls 1 可导入现有数据
    Observe {
        #[arg(long, default_value = "wiesler.sqlite3")]
        db: PathBuf,
        #[arg(long)]
        dsh: Vec<PathBuf>,
        #[arg(long)]
        codex_log: Vec<PathBuf>,
        #[arg(long)]
        codex_sockets: bool,
        /// 导入由 observe-sockets.ps1 生成的带采样时间的 TCP 快照
        #[arg(long)]
        socket_input: Vec<PathBuf>,
        #[arg(long)]
        from_end: bool,
        #[arg(long, default_value_t = 1000)]
        interval_ms: u64,
        #[arg(long)]
        polls: Option<u64>,
        #[arg(long, default_value_t = 1_000_000)]
        max_events: u64,
    },
    /// 汇总 DSH TPS、Codex 耗时/错误和代理证据；--json 供程序读取
    Diagnose {
        #[arg(long)]
        db: PathBuf,
        #[arg(long)]
        session: Vec<String>,
        #[arg(long)]
        json: bool,
    },
    /// 采集回环 Mihomo 控制器，写入 SQLite
    Record {
        #[arg(long)]
        controller: String,
        #[arg(long, default_value = "wiesler.sqlite3")]
        db: PathBuf,
        #[arg(long, default_value_t = 1000)]
        interval_ms: u64,
        #[arg(long, default_value_t = 3000)]
        timeout_ms: u64,
        /// 有限次采样；省略则运行到 Ctrl+C
        #[arg(long)]
        samples: Option<u64>,
        #[arg(long, default_value_t = 8)]
        queue_capacity: usize,
        /// 数据库总事件数上限；达到后停止，不删除数据
        #[arg(long, default_value_t = 1_000_000)]
        max_events: u64,
    },
    /// 列出数据库中的采集会话
    Sessions {
        #[arg(long)]
        db: PathBuf,
    },
    /// 一致性读取并导出会话 JSONL；仅新建文件
    Export {
        #[arg(long)]
        db: PathBuf,
        #[arg(long)]
        session: String,
        #[arg(long)]
        output: Option<PathBuf>,
    },
    /// 从事件日志重建连接状态及证据引用
    Replay {
        #[arg(long)]
        db: PathBuf,
        #[arg(long)]
        session: String,
    },
}

#[derive(Subcommand)]
enum NodeCommand {
    /// 持续下载后逐次发起真实请求；候选节点默认只预览
    Soak {
        #[arg(long, default_value = "http://127.0.0.1:9097")]
        controller: String,
        /// mixed-port / HTTP 代理入口，不是控制器
        #[arg(long)]
        proxy: String,
        #[arg(long)]
        group: String,
        /// 导入 stable.json 数组，按 fail、p90、median 排序
        #[arg(long, conflicts_with = "node")]
        from: Option<PathBuf>,
        /// 显式候选，可重复；省略 from/node 时测试当前路由
        #[arg(long)]
        node: Vec<String>,
        #[arg(long, default_value_t = 5)]
        top: usize,
        /// 允许临时切换组；结束和 Ctrl+C 后恢复原选择
        #[arg(long, conflicts_with = "dry_run")]
        apply: bool,
        #[arg(long)]
        dry_run: bool,
        #[arg(long, default_value_t = 60_000_000)]
        bytes: u64,
        #[arg(long, default_value_t = 15)]
        pings: u32,
        /// 下载端点必须实际返回 --bytes 指定的完整字节数
        #[arg(long)]
        download_url: Option<String>,
        #[arg(long, default_value = "https://chatgpt.com/cdn-cgi/trace")]
        url: String,
        #[arg(long, default_value_t = 90_000)]
        download_timeout_ms: u64,
        #[arg(long, default_value_t = 20_000)]
        timeout_ms: u64,
        #[arg(long, default_value_t = 3000)]
        controller_timeout_ms: u64,
        /// 仅新建，不覆盖已有结果
        #[arg(long, default_value = "nodes-soak.json")]
        out: PathBuf,
        /// 默认使用当前用户临时目录中的共用锁
        #[arg(long)]
        lock_file: Option<PathBuf>,
    },
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    match Cli::parse().command {
        Some(Command::Nodes {
            command:
                NodeCommand::Soak {
                    controller,
                    proxy,
                    group,
                    from,
                    node,
                    top,
                    apply,
                    dry_run,
                    bytes,
                    pings,
                    download_url,
                    url,
                    download_timeout_ms,
                    timeout_ms,
                    controller_timeout_ms,
                    out,
                    lock_file,
                },
        }) => {
            let secret = std::env::var("WIESLER_MIHOMO_SECRET").ok();
            let controller = ControllerConfig::new(
                &controller,
                secret.as_deref(),
                Duration::from_millis(controller_timeout_ms),
            )?;
            let cancelled = Arc::new(AtomicBool::new(false));
            let signal_flag = cancelled.clone();
            let signal = tokio::spawn(async move {
                while tokio::signal::ctrl_c().await.is_ok() {
                    signal_flag.store(true, Ordering::Relaxed);
                }
            });
            let result = soak(
                SoakOptions {
                    controller,
                    proxy,
                    group,
                    from,
                    nodes: node,
                    top,
                    apply,
                    dry_run,
                    bytes,
                    pings,
                    download_url: download_url.unwrap_or_else(|| {
                        format!("https://speed.cloudflare.com/__down?bytes={bytes}")
                    }),
                    url,
                    download_timeout: Duration::from_millis(download_timeout_ms),
                    ping_timeout: Duration::from_millis(timeout_ms),
                    output: out.clone(),
                    lock_file: lock_file
                        .unwrap_or_else(|| std::env::temp_dir().join("wiesler-nodes-soak.lock")),
                },
                cancelled,
            )
            .await;
            signal.abort();
            let report = result?;
            println!(
                "{}；结果 {}；原选择 {}；恢复状态 {}。",
                report.status,
                out.display(),
                report.original_selection,
                report.restoration
            );
            for row in &report.results {
                println!(
                    "{}：下载 {} bytes / {}，请求 {}/{}，中位 {:?} ms，p90 {:?} ms。",
                    row.name,
                    row.summary.soak_bytes,
                    row.summary.soak_ok,
                    row.summary.ping_ok,
                    report.pings,
                    row.summary.median_ms,
                    row.summary.p90_ms
                );
            }
            if report.status == "preview" {
                println!(
                    "候选：{}。未发送测速流量；候选切换测试需指定 --from/--node 并加 --apply。",
                    report.candidates.join("、")
                );
            }
            anyhow::ensure!(
                matches!(report.status.as_str(), "preview" | "completed"),
                "浸泡测试未完整通过，详情见 {}",
                out.display()
            );
        }
        Some(Command::Observe {
            db,
            dsh,
            codex_log,
            codex_sockets,
            socket_input,
            from_end,
            interval_ms,
            polls,
            max_events,
        }) => {
            let inputs = dsh
                .into_iter()
                .map(|p| (LogKind::Dsh, p))
                .chain(codex_log.into_iter().map(|p| (LogKind::Codex, p)))
                .collect();
            let session = observe(ObserveOptions {
                database: db,
                inputs,
                codex_sockets,
                socket_inputs: socket_input,
                from_end,
                interval: Duration::from_millis(interval_ms),
                polls,
                max_events,
            })
            .await?;
            println!("应用观测已保存，会话 {session}");
        }
        Some(Command::Diagnose { db, session, json }) => {
            let report = diagnostics::report(&db, &session)?;
            if json {
                println!("{}", serde_json::to_string_pretty(&report)?);
            } else {
                print!("{}", report.markdown());
            }
        }
        None => {
            Cli::command().print_help()?;
            println!();
        }
        Some(Command::Record {
            controller,
            db,
            interval_ms,
            timeout_ms,
            samples,
            queue_capacity,
            max_events,
        }) => {
            let secret = std::env::var("WIESLER_MIHOMO_SECRET").ok();
            let controller = ControllerConfig::new(
                &controller,
                secret.as_deref(),
                Duration::from_millis(timeout_ms),
            )?;
            let result = record(RecordOptions {
                controller,
                database: db,
                interval: Duration::from_millis(interval_ms),
                samples,
                queue_capacity,
                max_events,
            })
            .await?;
            println!(
                "会话 {} 已保存；采样 {} 次，失败 {} 次，队列丢弃 {} 次。",
                result.session_id, result.attempts, result.failures, result.dropped_samples
            );
        }
        Some(Command::Sessions { db }) => {
            println!("{}", serde_json::to_string_pretty(&store::sessions(&db)?)?)
        }
        Some(Command::Export {
            db,
            session,
            output,
        }) => {
            if let Some(path) = output {
                let mut writer =
                    BufWriter::new(OpenOptions::new().write(true).create_new(true).open(path)?);
                store::export(&db, &session, &mut writer)?;
                writer.flush()?;
            } else {
                store::export(&db, &session, &mut std::io::stdout().lock())?;
            }
        }
        Some(Command::Replay { db, session }) => println!(
            "{}",
            serde_json::to_string_pretty(&store::replay(&db, &session)?)?
        ),
    }
    Ok(())
}
