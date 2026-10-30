use clap::{CommandFactory, Parser, Subcommand};
use std::{
    fs::OpenOptions,
    io::{BufWriter, Write},
    path::PathBuf,
    time::Duration,
};
use wiesler::{
    config::ControllerConfig,
    diagnostics,
    observer::{LogKind, ObserveOptions, observe},
    recorder::{RecordOptions, record},
    store,
};

#[derive(Parser)]
#[command(
    version,
    about = "Wiesler — 本地网络与代理可观测性工具",
    after_help = "只读采样；连接消失不等于请求失败。控制器密钥仅从 WIESLER_MIHOMO_SECRET 环境变量读取。"
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Subcommand)]
enum Command {
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

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    match Cli::parse().command {
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
