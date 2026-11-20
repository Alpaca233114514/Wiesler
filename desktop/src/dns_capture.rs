use serde::Serialize;
use std::sync::{Arc, Mutex};
use wiesler::probes::dns_capture::{
    Assessment, Capture, Policy, Report, assess, trigger_local_probe,
};

#[derive(Default)]
pub struct State(pub Arc<Mutex<Option<Capture>>>);
#[derive(Serialize)]
pub struct View {
    report: Report,
    assessment: Assessment,
    policy: Policy,
}
fn view(report: Report, policy: Policy) -> View {
    let assessment = assess(&report, policy.clone());
    View {
        report,
        assessment,
        policy,
    }
}

#[tauri::command]
pub fn dns_capture_start(
    state: tauri::State<'_, State>,
    seconds: u32,
    policy: Policy,
) -> Result<View, String> {
    start(&state, seconds, policy)
}
fn start(state: &State, seconds: u32, policy: Policy) -> Result<View, String> {
    if !matches!(seconds, 30 | 120) {
        return Err("请选择 30 秒或 2 分钟".into());
    }
    let mut capture = state.0.lock().map_err(|_| "DNS 采集状态不可用")?;
    if let Some(current) = capture.as_ref() {
        if current.snapshot().running {
            return Ok(view(current.snapshot(), policy));
        }
    }
    let next = Capture::start_auto(seconds)?;
    let report = next.snapshot();
    *capture = Some(next);
    Ok(view(report, policy))
}
#[tauri::command]
pub fn dns_capture_snapshot(
    state: tauri::State<'_, State>,
    policy: Policy,
) -> Result<Option<View>, String> {
    let capture = state.0.lock().map_err(|_| "DNS 采集状态不可用")?;
    Ok(capture.as_ref().map(|c| view(c.snapshot(), policy)))
}
#[tauri::command]
pub fn dns_capture_probe() -> String {
    trigger_local_probe()
}
#[tauri::command]
pub async fn dns_capture_stop(
    state: tauri::State<'_, State>,
    policy: Policy,
) -> Result<Option<View>, String> {
    // 不在 UI 线程等待 ETW 冲刷。 / Drain native callbacks away from the UI thread.
    let shared = state.0.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let mut capture = shared
            .lock()
            .map_err(|_| "DNS 采集状态不可用".to_string())?;
        Ok(capture.as_mut().map(|c| view(c.stop(), policy)))
    })
    .await
    .map_err(|_| "停止 DNS 采集失败")?
}
