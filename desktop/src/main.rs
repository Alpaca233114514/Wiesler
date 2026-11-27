#![windows_subsystem = "windows"]
mod commands;
mod discovery;
mod dns_capture;
mod packet_loss;
mod rf;
use std::{fs::OpenOptions, io::Write, path::PathBuf};

fn data_root() -> PathBuf {
    std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join("Wiesler")
}
fn log(message: &str) -> String {
    let mut paths = Vec::new();
    if let Ok(exe) = std::env::current_exe()
        && let Some(parent) = exe.parent()
    {
        paths.push(parent.join("wiesler-startup.log"));
    }
    paths.push(data_root().join("desktop.log"));
    paths.push(std::env::temp_dir().join("wiesler-startup.log"));
    let mut failures = Vec::new();
    for path in paths {
        let result = (|| -> std::io::Result<()> {
            if let Some(parent) = path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut file = OpenOptions::new().create(true).append(true).open(&path)?;
            writeln!(
                file,
                "{:?} pid={} {message}",
                std::time::SystemTime::now(),
                std::process::id()
            )?;
            for failure in &failures {
                writeln!(file, "log fallback: {failure}")?;
            }
            file.flush()
        })();
        match result {
            Ok(()) => return path.display().to_string(),
            Err(error) => failures.push(format!("{}: {error}", path.display())),
        }
    }
    format!("Log write failed: {}", failures.join("; "))
}
fn report(message: &str) {
    let saved_path = log(message);
    let summary = message.lines().take(3).collect::<Vec<_>>().join("\n");
    let text = format!(
        "Wiesler Desktop 启动失败 / Startup failed\n\n{summary}\n\n日志 / Log: {saved_path}"
    );
    #[cfg(target_os = "windows")]
    {
        #[link(name = "user32")]
        unsafe extern "system" {
            fn MessageBoxW(
                window: *mut std::ffi::c_void,
                text: *const u16,
                caption: *const u16,
                flags: u32,
            ) -> i32;
        }
        let wide: Vec<u16> = text.encode_utf16().chain(Some(0)).collect();
        let title: Vec<u16> = "Wiesler Desktop".encode_utf16().chain(Some(0)).collect();
        // 启动失败时提供可见反馈 / Show errors even without a console.
        unsafe {
            MessageBoxW(std::ptr::null_mut(), wide.as_ptr(), title.as_ptr(), 0x10);
        }
    }
    #[cfg(not(target_os = "windows"))]
    eprintln!("{text}");
}
fn main() {
    let args = std::env::args().skip(1).collect::<Vec<_>>();
    if let Some(result) = wiesler::probes::dns_capture::run_authorized_helper(&args) {
        std::process::exit(if result.is_ok() { 0 } else { 1 });
    }
    std::panic::set_hook(Box::new(|info| {
        report(&format!(
            "{info}\n{}",
            std::backtrace::Backtrace::force_capture()
        ))
    }));
    log("starting desktop");
    let result = tauri::Builder::default()
        .manage(dns_capture::State::default())
        .manage(rf::State::default())
        .on_window_event(|window, event| {
            if matches!(event, tauri::WindowEvent::Destroyed) {
                use tauri::Manager;
                let state = window.state::<dns_capture::State>();
                if let Ok(mut capture) = state.0.lock() {
                    if let Some(capture) = capture.as_mut() {
                        capture.stop();
                    }
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::snapshot,
            commands::system_snapshot,
            commands::egress_address,
            commands::inspect_dns,
            commands::probe_https_204,
            dns_capture::dns_capture_start,
            dns_capture::dns_capture_snapshot,
            dns_capture::dns_capture_probe,
            dns_capture::dns_capture_stop,
            discovery::discover_controllers,
            commands::discover_databases,
            commands::sessions,
            commands::diagnose,
            packet_loss::ping,
            rf::rf_probe,
            rf::rf_reset
        ])
        .setup(|app| {
            let data_dir = data_root().join("WebView2");
            log(&format!(
                "creating webview; data_dir={}",
                data_dir.display()
            ));
            std::fs::create_dir_all(&data_dir).map_err(|error| {
                log(&format!(
                    "create data directory FAILED: {}: {error:?}",
                    data_dir.display()
                ));
                error
            })?;
            log("data directory ready; creating WebView2 window");
            let mut builder = tauri::WebviewWindowBuilder::new(
                app,
                "main",
                tauri::WebviewUrl::App("studio.html".into()),
            )
            .title("Wiesler Desktop")
            .background_color(tauri::utils::config::Color(16, 16, 16, 255))
            .visible(false)
            .on_page_load(|window, payload| {
                if payload.event() == tauri::webview::PageLoadEvent::Finished {
                    let _ = window.show();
                }
            })
            .decorations(false)
            .inner_size(1040.0, 808.0)
            .min_inner_size(900.0, 640.0)
            .data_directory(data_dir);
            if let Some(icon) = app.default_window_icon().cloned() {
                builder = builder.icon(icon)?;
            }
            builder.build().map_err(|error| {
                log(&format!("WebView2 window creation FAILED: {error:?}"));
                error
            })?;
            log("main window created");
            Ok(())
        })
        .run(tauri::generate_context!());
    if let Err(error) = result {
        report(&error.to_string());
        std::process::exit(1);
    }
    log("desktop exited normally");
}
