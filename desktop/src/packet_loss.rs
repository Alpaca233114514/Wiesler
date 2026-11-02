use serde::Serialize;
use std::net::Ipv4Addr;

#[derive(Debug, Serialize)]
pub struct PingSample {
    pub outcome: &'static str,
    pub rtt_ms: Option<u32>,
    pub message: String,
}

fn target(value: &str) -> Result<Ipv4Addr, String> {
    let ip: Ipv4Addr = value.parse().map_err(|_| "请输入有效的 IPv4 地址")?;
    if ip.is_unspecified() || ip.octets()[0] >= 224 {
        return Err("请使用单播 IPv4 地址".into());
    }
    Ok(ip)
}

fn sample(status: u32, rtt: u32) -> PingSample {
    match status {
        0 => PingSample {
            outcome: "reply",
            rtt_ms: Some(rtt),
            message: "收到回包".into(),
        },
        11010 => PingSample {
            outcome: "lost",
            rtt_ms: None,
            message: "2 秒内未收到回包".into(),
        },
        _ => PingSample {
            outcome: "error",
            rtt_ms: None,
            message: format!("ICMP 不可用 / 系统错误 {status}（不计入丢包率）"),
        },
    }
}

#[tauri::command]
pub async fn ping(target_ip: String) -> Result<PingSample, String> {
    let ip = target(&target_ip)?;
    tauri::async_runtime::spawn_blocking(move || platform_ping(ip))
        .await
        .map_err(|_| "ICMP 采样任务失败".to_string())?
}

#[cfg(not(target_os = "windows"))]
fn platform_ping(_: Ipv4Addr) -> Result<PingSample, String> {
    Err("当前桌面 ICMP 采样仅支持 Windows".into())
}

#[cfg(target_os = "windows")]
fn platform_ping(ip: Ipv4Addr) -> Result<PingSample, String> {
    use std::ffi::c_void;
    #[link(name = "iphlpapi")]
    unsafe extern "system" {
        fn IcmpCreateFile() -> *mut c_void;
        fn IcmpCloseHandle(handle: *mut c_void) -> i32;
        fn IcmpSendEcho(
            handle: *mut c_void,
            address: u32,
            data: *const c_void,
            size: u16,
            options: *const c_void,
            reply: *mut c_void,
            reply_size: u32,
            timeout: u32,
        ) -> u32;
    }
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetLastError() -> u32;
    }

    // 对齐缓冲区覆盖两种指针宽度；只读取共有的前三个 DWORD。 / Read only the shared fixed reply prefix.
    #[repr(C, align(8))]
    struct ReplyBuffer([u32; 64]);
    let mut reply = ReplyBuffer([0u32; 64]);
    let data = [0u8; 32];
    // 参数和缓冲区在同步调用期间有效，所有已创建句柄均关闭。 / Keep buffers alive and close the handle.
    unsafe {
        let handle = IcmpCreateFile();
        if handle as isize == -1 || handle.is_null() {
            return Ok(sample(GetLastError(), 0));
        }
        let count = IcmpSendEcho(
            handle,
            u32::from_ne_bytes(ip.octets()),
            data.as_ptr().cast(),
            data.len() as u16,
            std::ptr::null(),
            reply.0.as_mut_ptr().cast(),
            std::mem::size_of_val(&reply) as u32,
            2000,
        );
        let status = if count == 0 {
            GetLastError()
        } else {
            reply.0[1]
        };
        IcmpCloseHandle(handle);
        Ok(sample(status, reply.0[2]))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_non_unicast_and_command_text() {
        for ip in [
            "",
            "localhost",
            "::1",
            "1.1.1.1;whoami",
            "-c 99",
            "0.0.0.0",
            "255.255.255.255",
            "224.0.0.1",
        ] {
            assert!(target(ip).is_err(), "{ip}");
        }
        assert!(target("127.0.0.1").is_ok());
    }
    #[test]
    fn only_timeout_counts_as_loss() {
        assert_eq!(sample(0, 12).rtt_ms, Some(12));
        assert_eq!(sample(11010, 0).outcome, "lost");
        for status in [5, 11001, 11003, 11050] {
            assert_eq!(sample(status, 0).outcome, "error");
            assert_eq!(sample(status, 0).rtt_ms, None);
        }
    }
    #[cfg(target_os = "windows")]
    #[test]
    fn native_loopback_receives_echo() {
        let result = platform_ping(Ipv4Addr::LOCALHOST).unwrap();
        assert_eq!(result.outcome, "reply", "{result:?}");
        assert!(result.rtt_ms.is_some());
    }
}
