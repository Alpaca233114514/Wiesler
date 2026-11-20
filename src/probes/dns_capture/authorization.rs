use super::{
    Capture, Report, now,
    windows::{utf16, wide},
};
use std::{
    mem::size_of,
    ptr::{null, null_mut},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
use windows_sys::Win32::{
    Foundation::{
        CloseHandle, ERROR_NO_DATA, ERROR_PIPE_CONNECTED, ERROR_PIPE_LISTENING, GetLastError,
        HANDLE, INVALID_HANDLE_VALUE, LocalFree, WAIT_OBJECT_0,
    },
    Security::{
        Authorization::{
            ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
        },
        GetTokenInformation, SECURITY_ATTRIBUTES, TOKEN_QUERY, TOKEN_USER, TokenUser,
    },
    Storage::FileSystem::{
        CreateFileW, FILE_FLAG_FIRST_PIPE_INSTANCE, FILE_READ_DATA, FILE_WRITE_ATTRIBUTES,
        FILE_WRITE_DATA, OPEN_EXISTING, PIPE_ACCESS_DUPLEX, ReadFile, WriteFile,
    },
    System::{
        Com::{COINIT_APARTMENTTHREADED, COINIT_DISABLE_OLE1DDE, CoInitializeEx, CoUninitialize},
        Pipes::*,
        Threading::{
            GetCurrentProcess, GetProcessId, OpenProcess, OpenProcessToken,
            PROCESS_QUERY_LIMITED_INFORMATION, QueryFullProcessImageNameW, WaitForSingleObject,
        },
    },
    UI::{
        Shell::{SEE_MASK_NOASYNC, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW, ShellExecuteExW},
        WindowsAndMessaging::SW_HIDE,
    },
};
const PREFIX: &str = r"\\.\pipe\Wiesler-DNS-";
const FRAME_LIMIT: usize = 32 * 1024 * 1024;
const CANCELLED: &str = "已取消 Windows 授权，未开始本地检查";
struct Handle(HANDLE);
impl Drop for Handle {
    fn drop(&mut self) {
        if !self.0.is_null() && self.0 != INVALID_HANDLE_VALUE {
            unsafe { CloseHandle(self.0) };
        }
    }
}
struct Local(*mut std::ffi::c_void);
impl Drop for Local {
    fn drop(&mut self) {
        unsafe { LocalFree(self.0) };
    }
}
struct Com(bool);
impl Drop for Com {
    fn drop(&mut self) {
        if self.0 {
            unsafe { CoUninitialize() };
        }
    }
}
fn error(context: &str) -> String {
    format!("{context} (Windows {})", unsafe { GetLastError() })
}
fn create_pipe(name: &str) -> Result<Handle, String> {
    unsafe {
        let mut token = null_mut();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
            return Err(error("读取当前登录身份失败"));
        }
        let token = Handle(token);
        let mut needed = 0;
        GetTokenInformation(token.0, TokenUser, null_mut(), 0, &mut needed);
        let mut buffer = vec![0usize; (needed as usize).div_ceil(size_of::<usize>())];
        if GetTokenInformation(
            token.0,
            TokenUser,
            buffer.as_mut_ptr().cast(),
            needed,
            &mut needed,
        ) == 0
        {
            return Err(error("读取用户 SID 失败"));
        }
        let user = &*buffer.as_ptr().cast::<TOKEN_USER>();
        let mut sid = null_mut();
        if ConvertSidToStringSidW(user.User.Sid, &mut sid) == 0 {
            return Err(error("读取用户 SID 失败"));
        }
        let owned = Local(sid.cast());
        let mut len = 0;
        while *sid.add(len) != 0 {
            len += 1;
        }
        let sid = utf16(std::slice::from_raw_parts(sid, len));
        drop(owned);
        let sddl = wide(&format!("D:P(A;;GA;;;SY)(A;;GA;;;{sid})"));
        let mut descriptor = null_mut();
        if ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.as_ptr(),
            1,
            &mut descriptor,
            null_mut(),
        ) == 0
        {
            return Err(error("创建本地通道权限失败"));
        }
        let descriptor = Local(descriptor);
        let security = SECURITY_ATTRIBUTES {
            nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: descriptor.0,
            bInheritHandle: 0,
        };
        let pipe = CreateNamedPipeW(
            wide(name).as_ptr(),
            PIPE_ACCESS_DUPLEX | FILE_FLAG_FIRST_PIPE_INSTANCE,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_NOWAIT | PIPE_REJECT_REMOTE_CLIENTS,
            1,
            65536,
            65536,
            0,
            &security,
        );
        if pipe == INVALID_HANDLE_VALUE {
            return Err(error("创建本地采集通道失败"));
        }
        Ok(Handle(pipe))
    }
}
fn write_all(pipe: HANDLE, mut bytes: &[u8]) -> Result<(), String> {
    while !bytes.is_empty() {
        let mut written = 0;
        let size = bytes.len().min(65536) as u32;
        if unsafe { WriteFile(pipe, bytes.as_ptr(), size, &mut written, null_mut()) } == 0
            || written == 0
        {
            return Err(error("本地采集通道写入失败"));
        }
        bytes = &bytes[written as usize..];
    }
    Ok(())
}
fn read_available(pipe: HANDLE, buffer: &mut Vec<u8>) -> Result<(), String> {
    let mut available = 0;
    if unsafe { PeekNamedPipe(pipe, null_mut(), 0, null_mut(), &mut available, null_mut()) } == 0 {
        return Err(error("本地采集通道已断开"));
    }
    if available == 0 {
        return Ok(());
    }
    let mut bytes = vec![0; available.min(65536) as usize];
    let mut read = 0;
    if unsafe {
        ReadFile(
            pipe,
            bytes.as_mut_ptr(),
            bytes.len() as u32,
            &mut read,
            null_mut(),
        )
    } == 0
    {
        return Err(error("读取本地采集通道失败"));
    }
    buffer.extend_from_slice(&bytes[..read as usize]);
    if buffer.len() > FRAME_LIMIT {
        return Err("本地采集报告超出传输上限".into());
    }
    Ok(())
}
fn same_executable(pid: u32) -> bool {
    unsafe {
        let p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if p.is_null() {
            return false;
        }
        let p = Handle(p);
        let mut b = vec![0u16; 32768];
        let mut len = b.len() as u32;
        if QueryFullProcessImageNameW(p.0, 0, b.as_mut_ptr(), &mut len) == 0 {
            return false;
        }
        let peer = String::from_utf16_lossy(&b[..len as usize]);
        std::env::current_exe().is_ok_and(|p| p.to_string_lossy().eq_ignore_ascii_case(&peer))
    }
}
pub(super) fn run(
    report: Arc<Mutex<Report>>,
    stop: Arc<AtomicBool>,
    seconds: u32,
    authorize: bool,
) {
    if let Err(message) = broker(&report, &stop, seconds, authorize) {
        let mut r = report.lock().unwrap();
        let state = if message == CANCELLED {
            "cancelled"
        } else {
            "failed"
        };
        r.packet_state = state.into();
        r.event_state = state.into();
        r.error(message);
    }
}
fn broker(
    report: &Arc<Mutex<Report>>,
    stop: &Arc<AtomicBool>,
    seconds: u32,
    authorize: bool,
) -> Result<(), String> {
    let name = format!("{PREFIX}{}-{}", std::process::id(), now());
    let pipe = create_pipe(&name)?;
    unsafe {
        ConnectNamedPipe(pipe.0, null_mut());
    }
    let _com = Com(unsafe {
        CoInitializeEx(
            null(),
            (COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE) as u32,
        )
    } >= 0);
    let executable = wide(
        &std::env::current_exe()
            .map_err(|e| e.to_string())?
            .to_string_lossy(),
    );
    let params = wide(&format!(
        "--dns-capture-helper {name} {} {seconds}",
        std::process::id()
    ));
    let verb = wide(if authorize { "runas" } else { "open" });
    let mut launch = SHELLEXECUTEINFOW {
        cbSize: size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC,
        lpVerb: verb.as_ptr(),
        lpFile: executable.as_ptr(),
        lpParameters: params.as_ptr(),
        nShow: SW_HIDE,
        ..Default::default()
    };
    // 开始检查缺权限时只提升本轮助手。 / Elevate only this bounded collector when needed.
    if unsafe { ShellExecuteExW(&mut launch) } == 0 {
        if unsafe { GetLastError() } == 1223 {
            return Err(CANCELLED.into());
        }
        return Err(error("Windows 授权被取消或无法启动采集助手"));
    }
    if launch.hProcess.is_null() {
        return Err("没有取得采集助手进程句柄".into());
    }
    let process = Handle(launch.hProcess);
    let expected = unsafe { GetProcessId(process.0) };
    let connected = Instant::now();
    loop {
        if unsafe { ConnectNamedPipe(pipe.0, null_mut()) } != 0 {
            break;
        }
        let code = unsafe { GetLastError() };
        if code == ERROR_PIPE_CONNECTED {
            break;
        }
        if code != ERROR_PIPE_LISTENING && code != ERROR_NO_DATA {
            return Err(error("连接采集助手失败"));
        }
        if connected.elapsed() > Duration::from_secs(20)
            || unsafe { WaitForSingleObject(process.0, 0) } == WAIT_OBJECT_0
        {
            return Err("采集助手未建立本地连接".into());
        }
        if stop.load(Ordering::Acquire) {
            return Err("已取消等待采集助手".into());
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    let mut actual = 0;
    if unsafe { GetNamedPipeClientProcessId(pipe.0, &mut actual) } == 0 || actual != expected {
        return Err("本地采集通道身份不匹配".into());
    }
    let mode = PIPE_READMODE_BYTE | PIPE_WAIT;
    if unsafe { SetNamedPipeHandleState(pipe.0, &mode, null(), null()) } == 0 {
        return Err(error("配置本地采集通道失败"));
    }
    let mut bytes = Vec::new();
    let mut stopping = false;
    let start = Instant::now();
    loop {
        if stop.load(Ordering::Acquire) && !stopping {
            write_all(pipe.0, b"STOP\n")?;
            stopping = true;
        }
        read_available(pipe.0, &mut bytes)?;
        while let Some(end) = bytes.iter().position(|c| *c == b'\n') {
            let frame = bytes.drain(..=end).collect::<Vec<_>>();
            let received: Report =
                serde_json::from_slice(&frame).map_err(|_| "采集助手返回无效报告")?;
            let done = !received.running;
            *report.lock().unwrap() = received;
            if done {
                return Ok(());
            }
        }
        if start.elapsed() > Duration::from_secs(seconds as u64 + 20) {
            return Err("等待采集助手完成超时".into());
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}
pub(super) fn helper(args: &[String]) -> Result<(), String> {
    if args.len() != 4 {
        return Err("无效的采集助手参数".into());
    }
    let name = &args[1];
    let parent = args[2].parse::<u32>().map_err(|_| "无效的父进程")?;
    let seconds = args[3].parse::<u32>().map_err(|_| "无效的观察时长")?;
    if !name.starts_with(&format!("{PREFIX}{parent}-"))
        || name.len() > 120
        || !name[PREFIX.len()..]
            .bytes()
            .all(|b| b.is_ascii_digit() || b == b'-')
        || !(1..=120).contains(&seconds)
    {
        return Err("无效的本地采集请求".into());
    }
    let raw = unsafe {
        CreateFileW(
            wide(name).as_ptr(),
            FILE_READ_DATA | FILE_WRITE_DATA | FILE_WRITE_ATTRIBUTES,
            0,
            null(),
            OPEN_EXISTING,
            0,
            null_mut(),
        )
    };
    if raw == INVALID_HANDLE_VALUE {
        return Err(error("连接桌面程序失败"));
    }
    let pipe = Handle(raw);
    let mut actual = 0;
    if unsafe { GetNamedPipeServerProcessId(pipe.0, &mut actual) } == 0
        || actual != parent
        || !same_executable(parent)
    {
        return Err("采集助手只接受同一程序的本地请求".into());
    }
    let mut capture = Capture::start(seconds)?;
    let mut controls = Vec::new();
    let mut send = Instant::now() - Duration::from_secs(1);
    loop {
        read_available(pipe.0, &mut controls)?;
        if controls.len() >= 5 {
            if controls != b"STOP\n" {
                return Err("不支持的采集控制命令".into());
            }
            capture.stop();
            controls.clear();
        }
        if send.elapsed() >= Duration::from_millis(500) {
            let report = capture.snapshot();
            let done = !report.running;
            let mut frame = serde_json::to_vec(&report).map_err(|e| e.to_string())?;
            frame.push(b'\n');
            if frame.len() > FRAME_LIMIT {
                return Err("本地采集报告超出传输上限".into());
            }
            write_all(pipe.0, &frame)?;
            send = Instant::now();
            if done {
                return Ok(());
            }
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn peer_identity_accepts_current_executable() {
        assert!(same_executable(std::process::id()));
    }
    #[test]
    fn rejects_unscoped_helper_requests_without_starting_capture() {
        for args in [
            vec!["--dns-capture-helper"],
            vec!["--dns-capture-helper", r"\\remote\pipe\test", "1", "30"],
            vec![
                "--dns-capture-helper",
                r"\\.\pipe\Wiesler-DNS-1-2",
                "1",
                "999",
            ],
        ] {
            assert!(helper(&args.into_iter().map(str::to_owned).collect::<Vec<_>>()).is_err());
        }
    }
    #[test]
    fn private_pipe_cannot_be_created_twice() {
        let name = format!("{PREFIX}{}-{}", std::process::id(), now());
        let _pipe = create_pipe(&name).unwrap();
        assert!(create_pipe(&name).is_err());
    }
}
