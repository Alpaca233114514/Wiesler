use crate::{
    config::ControllerConfig,
    core::{
        connection::StateMachine,
        event::{Snapshot, Stamp},
    },
    probes::mihomo::{Mihomo, ProbeError},
    store::Store,
};
use anyhow::{Result, bail};
use std::{
    path::PathBuf,
    time::{Duration, Instant},
};
use tokio::sync::{mpsc, oneshot};

pub struct RecordOptions {
    pub controller: ControllerConfig,
    pub database: PathBuf,
    pub interval: Duration,
    pub samples: Option<u64>,
    pub queue_capacity: usize,
    pub max_events: u64,
}

enum Observation {
    Snapshot(Snapshot, String),
    Failure(String),
    Finish(String),
}
struct Message {
    at: Stamp,
    dropped: Option<(u64, u64)>,
    observation: Observation,
}

#[derive(Debug)]
pub struct RecordResult {
    pub session_id: String,
    pub attempts: u64,
    pub failures: u64,
    pub dropped_samples: u64,
}

fn backoff(failures: u32, jitter: u64) -> Duration {
    let ceiling = (1_u64 << failures.saturating_sub(1).min(5)).min(30) * 1000;
    Duration::from_millis(ceiling - ceiling / 10 + jitter % (ceiling / 10 + 1))
}

pub async fn record(options: RecordOptions) -> Result<RecordResult> {
    if options.interval.is_zero()
        || options.queue_capacity == 0
        || options.samples == Some(0)
        || options.max_events == 0
    {
        bail!("采样间隔、队列容量、采样次数及事件上限必须大于零");
    }
    let probe = Mihomo::new(options.controller)?;
    let origin = Instant::now();
    let at = Stamp::now(origin);
    let (tx, mut rx) = mpsc::channel::<Message>(options.queue_capacity);
    let (ready_tx, ready_rx) = oneshot::channel();
    let interval_ms = options.interval.as_millis() as u64;
    let worker = std::thread::spawn(move || -> Result<String> {
        let mut store = Store::open(&options.database, options.max_events)?;
        // SQLite 随机 ID 避免进程重启/时钟回拨碰撞。 / SQLite random IDs avoid restart and wall-clock collisions.
        let random = rusqlite::Connection::open_in_memory()?.query_row(
            "SELECT lower(hex(randomblob(16)))",
            [],
            |row| row.get::<_, String>(0),
        )?;
        let session = format!("s-{random}");
        let mut state = StateMachine::new(session.clone());
        store.start(&state.start(at, interval_ms))?;
        let _ = ready_tx.send(session.clone());
        let result = (|| -> Result<()> {
            while let Some(message) = rx.blocking_recv() {
                let mut events = vec![];
                if let Some((from, count)) = message.dropped {
                    events.extend(state.dropped(message.at, from, count));
                }
                match message.observation {
                    Observation::Snapshot(snapshot, version) => {
                        events.extend(state.snapshot(message.at, snapshot, version))
                    }
                    Observation::Failure(reason) => {
                        events.extend(state.unavailable(message.at, reason))
                    }
                    Observation::Finish(reason) => {
                        events.extend(state.finish(message.at, reason.clone()));
                        store.finish(&events, &session, message.at.utc_ms, &reason)?;
                        return Ok(());
                    }
                }
                store.append(&events)?;
            }
            bail!("采集任务异常结束，缺少结束记录")
        })();
        if result.is_err() {
            store.mark_incomplete(&session);
        }
        result?;
        Ok(session)
    });
    let ready = ready_rx.await;
    if ready.is_err() {
        tokio::task::spawn_blocking(move || {
            worker
                .join()
                .map_err(|_| anyhow::anyhow!("数据库线程异常退出"))?
        })
        .await??;
        bail!("数据库线程未返回就绪会话");
    }
    let session_id = ready.expect("ready session");
    eprintln!("采集会话 {session_id}；Ctrl+C 停止并保存。");
    let mut attempts = 0;
    let mut failures = 0;
    let mut dropped_samples = 0;
    let mut consecutive = 0_u32;
    let mut dropped: Option<(u64, u64)> = None;
    let stop = tokio::signal::ctrl_c();
    tokio::pin!(stop);
    let reason = loop {
        let poll = async {
            let version = probe.version().await?;
            let snapshot = probe.snapshot().await?;
            Ok::<_, ProbeError>((snapshot, version))
        };
        let result = tokio::select! {
            biased;
            _ = tx.closed() => break "storage_failed",
            signal = &mut stop => break if signal.is_ok() { "stopped" } else { "signal_failed" },
            result = poll => result,
        };
        attempts += 1;
        let at = Stamp::now(origin);
        let auth_failed = matches!(result, Err(ProbeError::Authentication(_)));
        let observation = match result {
            Ok((snapshot, version)) => {
                consecutive = 0;
                Observation::Snapshot(snapshot, version)
            }
            Err(error) => {
                failures += 1;
                consecutive = consecutive.saturating_add(1);
                if consecutive == 1 || auth_failed {
                    eprintln!("控制器采集异常：{error}");
                }
                Observation::Failure(error.to_string())
            }
        };
        let message = Message {
            at,
            dropped: dropped.take(),
            observation,
        };
        match tx.try_send(message) {
            Ok(()) => {}
            Err(mpsc::error::TrySendError::Full(message)) => {
                dropped_samples += 1;
                let (from, count) = message.dropped.unwrap_or((at.monotonic_ms, 0));
                dropped = Some((from, count + 1));
            }
            Err(mpsc::error::TrySendError::Closed(_)) => break "storage_failed",
        }
        if auth_failed {
            break "authentication_failed";
        }
        if options.samples.is_some_and(|limit| attempts >= limit) {
            break if failures == 0 && dropped_samples == 0 {
                "completed"
            } else {
                "completed_with_gaps"
            };
        }
        let delay = if consecutive == 0 {
            options.interval
        } else {
            backoff(consecutive, at.utc_ms ^ at.monotonic_ms)
        };
        tokio::select! {
            biased;
            _ = tx.closed() => break "storage_failed",
            signal = &mut stop => break if signal.is_ok() { "stopped" } else { "signal_failed" },
            _ = tokio::time::sleep(delay) => {}
        }
    };
    let final_message = Message {
        at: Stamp::now(origin),
        dropped,
        observation: Observation::Finish(reason.into()),
    };
    let _ = tx.send(final_message).await;
    drop(tx);
    tokio::task::spawn_blocking(move || {
        worker
            .join()
            .map_err(|_| anyhow::anyhow!("数据库线程异常退出"))?
    })
    .await??;
    if reason == "authentication_failed" {
        bail!("控制器拒绝鉴权，已停止重试；请检查 WIESLER_MIHOMO_SECRET");
    }
    if reason == "signal_failed" {
        bail!("无法监听停止信号，已结束采集");
    }
    if attempts > 0 && failures == attempts {
        bail!("本次未获得有效快照；采集异常已保存，会话 {session_id}");
    }
    Ok(RecordResult {
        session_id,
        attempts,
        failures,
        dropped_samples,
    })
}
