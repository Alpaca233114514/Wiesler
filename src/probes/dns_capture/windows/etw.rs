use super::super::{Interface, Observation, now};
use super::{Input, NativeError, Sink, filetime, native_error, utf16, wide};
use std::{
    ffi::c_void,
    mem::{offset_of, size_of},
    ptr::null,
    sync::atomic::Ordering,
};
use windows_sys::{
    Win32::{
        Foundation::GetLastError,
        NetworkManagement::IpHelper::{GetIfEntry2, MIB_IF_ROW2},
        System::Diagnostics::Etw::*,
    },
    core::GUID,
};

const PROVIDER: GUID = GUID::from_u128(0x1c95126e_7eea_49a9_a3fe_a378b03ddb4d);
#[repr(C)]
struct Properties {
    p: EVENT_TRACE_PROPERTIES,
    name: [u16; 128],
}
impl Properties {
    fn new(name: &[u16]) -> Box<Self> {
        let mut value = Box::new(Self {
            p: EVENT_TRACE_PROPERTIES::default(),
            name: [0; 128],
        });
        value.p.Wnode.BufferSize = size_of::<Self>() as u32;
        value.p.Wnode.Flags = WNODE_FLAG_TRACED_GUID;
        value.p.Wnode.ClientContext = 2;
        value.p.LogFileMode = EVENT_TRACE_REAL_TIME_MODE;
        value.p.BufferSize = 64;
        value.p.MinimumBuffers = 4;
        value.p.MaximumBuffers = 16;
        value.p.FlushTimer = 1;
        value.p.LoggerNameOffset = offset_of!(Self, name) as u32;
        value.name[..name.len()].copy_from_slice(name);
        value
    }
}
pub(super) struct Session {
    handle: CONTROLTRACE_HANDLE,
    consumer: PROCESSTRACE_HANDLE,
    properties: Box<Properties>,
    context: Box<Sink>,
    worker: Option<std::thread::JoinHandle<()>>,
}
impl Session {
    pub fn start(sink: Sink) -> Result<Self, NativeError> {
        let name = wide(&format!("Wiesler-DNS-{}-{}", std::process::id(), now()));
        let mut properties = Properties::new(&name);
        let mut handle = CONTROLTRACE_HANDLE::default();
        let result = unsafe { StartTraceW(&mut handle, name.as_ptr(), &mut properties.p) };
        if result != 0 {
            return Err(native_error("DNS ETW StartTrace", result));
        }
        let mut session = Self {
            handle,
            consumer: PROCESSTRACE_HANDLE { Value: u64::MAX },
            properties,
            context: Box::new(sink),
            worker: None,
        };
        let mut log = EVENT_TRACE_LOGFILEW {
            LoggerName: session.properties.name.as_mut_ptr(),
            ..Default::default()
        };
        log.Anonymous1.ProcessTraceMode =
            PROCESS_TRACE_MODE_REAL_TIME | PROCESS_TRACE_MODE_EVENT_RECORD;
        log.Anonymous2.EventRecordCallback = Some(event);
        log.Context = (&mut *session.context as *mut Sink).cast::<c_void>();
        session.consumer = unsafe { OpenTraceW(&mut log) };
        if session.consumer.Value == u64::MAX {
            return Err(native_error("DNS ETW OpenTrace", unsafe { GetLastError() }));
        }
        let result = unsafe {
            EnableTraceEx2(
                handle,
                &PROVIDER,
                EVENT_CONTROL_CODE_ENABLE_PROVIDER,
                5,
                u64::MAX,
                0,
                // 异步启用，避免慢提供者在一秒后被误判不可用。 / Enable asynchronously instead of discarding slow providers.
                0,
                null(),
            )
        };
        if result != 0 {
            return Err(native_error("DNS ETW EnableTrace", result));
        }
        let consumer = session.consumer;
        let errors = session.context.clone();
        session.worker = Some(
            std::thread::Builder::new()
                .name("dns-etw-events".into())
                .spawn(move || {
                    let result = unsafe { ProcessTrace(&consumer, 1, null(), null()) };
                    if result != 0 && result != 1223 {
                        errors.send(Input::Error {
                            source: "event",
                            message: native_error("DNS ETW ProcessTrace", result).message,
                        });
                    }
                })
                .map_err(|e| NativeError {
                    permission: false,
                    message: e.to_string(),
                })?,
        );
        Ok(session)
    }
    pub fn loss(&mut self) {
        if unsafe {
            ControlTraceW(
                self.handle,
                null(),
                &mut self.properties.p,
                EVENT_TRACE_CONTROL_QUERY,
            )
        } == 0
        {
            self.context.event_loss.store(
                self.properties.p.EventsLost as u64 + self.properties.p.RealTimeBuffersLost as u64,
                Ordering::Relaxed,
            );
        }
    }
}
impl Drop for Session {
    fn drop(&mut self) {
        self.loss();
        let result = unsafe {
            ControlTraceW(
                self.handle,
                null(),
                &mut self.properties.p,
                EVENT_TRACE_CONTROL_STOP,
            )
        };
        if result != 0 {
            self.context.send(Input::Error {
                source: "event",
                message: native_error("DNS ETW Stop", result).message,
            });
        }
        self.context.event_loss.fetch_max(
            self.properties.p.EventsLost as u64 + self.properties.p.RealTimeBuffersLost as u64,
            Ordering::Relaxed,
        );
        // 停止成功后先读完最后的缓冲区。 / Drain the final buffers before closing the consumer.
        if result != 0 && self.consumer.Value != u64::MAX {
            unsafe { CloseTrace(self.consumer) };
        }
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
        if result == 0 && self.consumer.Value != u64::MAX {
            unsafe { CloseTrace(self.consumer) };
        }
    }
}
fn property(record: *const EVENT_RECORD, key: &str) -> Option<Vec<u8>> {
    let key = wide(key);
    let descriptor = PROPERTY_DATA_DESCRIPTOR {
        PropertyName: key.as_ptr() as u64,
        ArrayIndex: u32::MAX,
        Reserved: 0,
    };
    let mut size = 0;
    if unsafe { TdhGetPropertySize(record, 0, null(), 1, &descriptor, &mut size) } != 0
        || size > 65536
    {
        return None;
    }
    let mut bytes = vec![0; size as usize];
    if unsafe { TdhGetProperty(record, 0, null(), 1, &descriptor, size, bytes.as_mut_ptr()) } != 0 {
        return None;
    }
    Some(bytes)
}
fn string(record: *const EVENT_RECORD, key: &str) -> Option<String> {
    let bytes = property(record, key)?;
    if bytes.len() % 2 != 0 {
        return None;
    }
    let text = utf16(
        &bytes
            .chunks_exact(2)
            .map(|b| u16::from_le_bytes([b[0], b[1]]))
            .collect::<Vec<_>>(),
    );
    if text.is_empty() {
        None
    } else {
        Some(text.chars().take(8192).collect())
    }
}
fn number(record: *const EVENT_RECORD, key: &str) -> Option<u32> {
    let bytes = property(record, key)?;
    match bytes.as_slice() {
        [a] => Some(u32::from(*a)),
        [a, b] => Some(u32::from(u16::from_le_bytes([*a, *b]))),
        [a, b, c, d] => Some(u32::from_le_bytes([*a, *b, *c, *d])),
        _ => None,
    }
}
fn interface(index: u32) -> Option<Interface> {
    if index == 0 {
        return None;
    }
    let mut row = MIB_IF_ROW2 {
        InterfaceIndex: index,
        ..Default::default()
    };
    if unsafe { GetIfEntry2(&mut row) } != 0 {
        return None;
    }
    Some(Interface {
        component_id: 0,
        name: utf16(&row.Alias),
        index: Some(index),
        hardware: Some(super::pktmon::is_hardware_adapter(&row)),
    })
}
fn interface_by_name(name: String) -> Interface {
    for row in super::pktmon::adapter_rows() {
        let alias = utf16(&row.Alias);
        let desc = utf16(&row.Description);
        if alias.eq_ignore_ascii_case(&name) || desc.eq_ignore_ascii_case(&name) {
            return Interface {
                component_id: 0,
                name: if alias.is_empty() { name } else { alias },
                index: Some(row.InterfaceIndex),
                hardware: Some(super::pktmon::is_hardware_adapter(&row)),
            };
        }
    }
    Interface {
        name,
        ..Default::default()
    }
}
unsafe extern "system" fn event(record: *mut EVENT_RECORD) {
    // Windows 拥有回调内存；不跨回调保存原始指针。 / Copy fields before the callback returns.
    if record.is_null() {
        return;
    }
    let r = unsafe { &*record };
    if r.UserContext.is_null() {
        return;
    }
    let sink = unsafe { &*r.UserContext.cast::<Sink>() };
    let id = r.EventHeader.EventDescriptor.Id;
    let phase = match id {
        3006 => "started",
        3008 => "completed",
        3010 => "send",
        3011 => "receive",
        3016 => "cache_lookup",
        3018 => "cache_result",
        3020 => "network_result",
        8043 | 8044 => "encrypted_query",
        _ => return,
    };
    // 跳过无查询名的内部辅助事件，不把整个事件流标为失败。 / Skip internal events with empty query name.
    let domain = string(record, if id >= 8000 { "NameQuery" } else { "QueryName" });
    if domain.is_none() {
        return;
    }
    let client_pid = number(record, "ClientPID").filter(|p| *p != 0);
    let pid = client_pid.unwrap_or(r.EventHeader.ProcessId);
    let activity = &r.EventHeader.ActivityId;
    let activity_id = if activity.data1 == 0
        && activity.data2 == 0
        && activity.data3 == 0
        && activity.data4 == [0; 8]
    {
        None
    } else {
        Some(format!(
            "{:08x}-{:04x}-{:04x}-{}",
            activity.data1,
            activity.data2,
            activity.data3,
            activity
                .data4
                .iter()
                .map(|v| format!("{v:02x}"))
                .collect::<String>()
        ))
    };
    let iface = number(record, "InterfaceIndex")
        .and_then(interface)
        .or_else(|| string(record, "InterfaceName").map(interface_by_name));
    sink.send(Input::Dns(Box::new(Observation {
        observed_at: filetime(r.EventHeader.TimeStamp),
        source: "windows-dns".into(),
        phase: phase.into(),
        domain,
        query_type: number(record, "QueryType").and_then(|t| u16::try_from(t).ok()),
        answers: string(record, "QueryResults")
            .unwrap_or_default()
            .split(';')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .take(64)
            .map(str::to_owned)
            .collect(),
        status: number(record, "QueryStatus")
            .or_else(|| number(record, "ResponseStatus"))
            .or_else(|| number(record, "Status")),
        pid: (pid != 0).then_some(pid),
        process_evidence: Some(
            if client_pid.is_some() {
                "client_pid"
            } else {
                "event_pid"
            }
            .into(),
        ),
        resolver: string(record, "DnsServerIpAddress")
            .or_else(|| string(record, "Server"))
            .or_else(|| string(record, "ServerList")),
        transport: match id {
            8043 => "DoH",
            8044 => "DoT",
            _ => "Windows DNS",
        }
        .into(),
        interface: iface,
        activity_id,
        event_id: Some(id),
        ..Default::default()
    })));
}
