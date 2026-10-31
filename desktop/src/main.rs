#![windows_subsystem = "windows"]
mod commands;
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
    let text =
        format!("Wiesler 启动失败 / Startup failed\n\n{summary}\n\n日志 / Log: {saved_path}");
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
        let title: Vec<u16> = "Wiesler".encode_utf16().chain(Some(0)).collect();
        // 启动失败时提供可见反馈 / Show errors even without a console.
        unsafe {
            MessageBoxW(std::ptr::null_mut(), wide.as_ptr(), title.as_ptr(), 0x10);
        }
    }
    #[cfg(not(target_os = "windows"))]
    eprintln!("{text}");
}
fn main() {
    std::panic::set_hook(Box::new(|info| {
        report(&format!(
            "{info}\n{}",
            std::backtrace::Backtrace::force_capture()
        ))
    }));
    log("starting desktop");
    let result = tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            commands::snapshot,
            commands::sessions,
            commands::diagnose
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
            tauri::WebviewWindowBuilder::new(
                app,
                "main",
                tauri::WebviewUrl::App("index.html".into()),
            )
            .title("Wiesler · Network Observatory")
            .inner_size(1360.0, 900.0)
            .min_inner_size(900.0, 640.0)
            .data_directory(data_dir)
            .build()
            .map_err(|error| {
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
