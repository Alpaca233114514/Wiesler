use std::sync::{Arc, Mutex};
use wiesler::probes::rf::{RfLinkContext, RfMonitorState, RfSnapshotReport, probe_once};

#[derive(Default)]
pub struct State(pub Arc<Mutex<RfMonitorState>>);

#[tauri::command]
pub async fn rf_probe(
    state: tauri::State<'_, State>,
    link: Option<RfLinkContext>,
) -> Result<RfSnapshotReport, String> {
    let ctx = link.unwrap_or_default();
    let shared = Arc::clone(&state.0);
    tauri::async_runtime::spawn_blocking(move || {
        let mut guard = shared
            .lock()
            .map_err(|_| "射频监测状态锁不可用".to_string())?;
        Ok(probe_once(&mut guard, &ctx))
    })
    .await
    .map_err(|_| "射频监测任务执行失败".to_string())?
}

#[tauri::command]
pub fn rf_reset(state: tauri::State<'_, State>) -> Result<(), String> {
    let mut guard = state
        .0
        .lock()
        .map_err(|_| "射频监测状态锁不可用".to_string())?;
    guard.reset();
    Ok(())
}
