use super::super::{Interface, now};
use super::{Input, NativeError, Sink, filetime, native_error, utf16, wide};
use std::{
    collections::{HashMap, HashSet},
    ffi::c_void,
    mem::size_of,
    ptr::null_mut,
    sync::atomic::{AtomicU64, Ordering},
};
use windows_sys::{
    Win32::{
        Foundation::{FreeLibrary, GetLastError, HMODULE},
        NetworkManagement::IpHelper::{
            ConvertInterfaceGuidToLuid, FreeMibTable, GetIfEntry2, GetIfTable2, MIB_IF_ROW2,
        },
        System::LibraryLoader::{GetProcAddress, LOAD_LIBRARY_SEARCH_SYSTEM32, LoadLibraryExW},
    },
    core::GUID,
};

type Handle = *mut c_void;
#[repr(C)]
#[derive(Clone, Copy)]
struct Source {
    kind: u32,
    name: [u16; 64],
    description: [u16; 128],
    id: u32,
    secondary: u32,
    parent: u32,
    present: u32,
    detail: [u64; 2],
}
#[repr(C)]
struct Constraint {
    name: [u16; 64],
    present: u32,
    mac1: [u8; 6],
    mac2: [u8; 6],
    vlan: u16,
    ether: u16,
    dscp: u16,
    protocol: u8,
    ip1: [u64; 2],
    ip2: [u64; 2],
    prefix1: u8,
    prefix2: u8,
    port1: u16,
    port2: u16,
    tcp_flags: u8,
    encap: u32,
    vxlan: u16,
    packets: u64,
    bytes: u64,
}
#[repr(C)]
struct StreamConfig {
    context: Handle,
    event: Option<unsafe extern "system" fn(Handle, *const c_void, u32)>,
    data: Option<unsafe extern "system" fn(Handle, *const Descriptor)>,
    multiplier: u16,
    truncation: u16,
}
#[repr(C)]
struct Descriptor {
    data: *const u8,
    size: u32,
    metadata_offset: u32,
    packet_offset: u32,
    packet_length: u32,
    missed_write: u32,
    missed_read: u32,
}
#[repr(C)]
#[derive(Clone, Copy)]
struct Metadata {
    group: u64,
    count: u16,
    appearance: u16,
    direction: u16,
    kind: u16,
    component: u16,
    edge: u16,
    reserved: u16,
    drop_reason: u32,
    drop_location: u32,
    processor: u16,
    timestamp: i64,
}

type Initialize = unsafe extern "system" fn(u32, Handle, *mut Handle) -> i32;
type Uninitialize = unsafe extern "system" fn(Handle);
type CreateSession = unsafe extern "system" fn(Handle, *const u16, *mut Handle) -> i32;
type Close = unsafe extern "system" fn(Handle);
type Active = unsafe extern "system" fn(Handle, u8) -> i32;
type EnumSources = unsafe extern "system" fn(Handle, u32, u8, usize, *mut usize, Handle) -> i32;
type AddSource = unsafe extern "system" fn(Handle, *const Source) -> i32;
type AddConstraint = unsafe extern "system" fn(Handle, *const Constraint) -> i32;
type CreateStream = unsafe extern "system" fn(Handle, *const StreamConfig, *mut Handle) -> i32;
type Attach = unsafe extern "system" fn(Handle, Handle) -> i32;
struct Library(HMODULE);
impl Drop for Library {
    fn drop(&mut self) {
        unsafe { FreeLibrary(self.0) };
    }
}
struct Api {
    _library: Library,
    initialize: Initialize,
    uninitialize: Uninitialize,
    create_session: CreateSession,
    close_session: Close,
    active: Active,
    enum_sources: EnumSources,
    add_source: AddSource,
    add_constraint: AddConstraint,
    create_stream: CreateStream,
    close_stream: Close,
    attach: Attach,
}
impl Api {
    fn load() -> Result<Self, NativeError> {
        let module = unsafe {
            LoadLibraryExW(
                wide("PktMonApi.dll").as_ptr(),
                null_mut(),
                LOAD_LIBRARY_SEARCH_SYSTEM32,
            )
        };
        if module.is_null() {
            return Err(native_error(
                "Packet Monitor API（需要支持多会话的 Windows）",
                unsafe { GetLastError() },
            ));
        }
        let library = Library(module);
        macro_rules! load {
            ($name:literal,$kind:ty) => {{
                let symbol = unsafe { GetProcAddress(module, concat!($name, "\0").as_ptr()) }
                    .ok_or_else(|| native_error($name, unsafe { GetLastError() }))?;
                unsafe {
                    std::mem::transmute::<unsafe extern "system" fn() -> isize, $kind>(symbol)
                }
            }};
        }
        Ok(Self {
            initialize: load!("PacketMonitorInitialize", Initialize),
            uninitialize: load!("PacketMonitorUninitialize", Uninitialize),
            create_session: load!("PacketMonitorCreateLiveSession", CreateSession),
            close_session: load!("PacketMonitorCloseSessionHandle", Close),
            active: load!("PacketMonitorSetSessionActive", Active),
            enum_sources: load!("PacketMonitorEnumDataSources", EnumSources),
            add_source: load!("PacketMonitorAddSingleDataSourceToSession", AddSource),
            add_constraint: load!("PacketMonitorAddCaptureConstraint", AddConstraint),
            create_stream: load!("PacketMonitorCreateRealtimeStream", CreateStream),
            close_stream: load!("PacketMonitorCloseRealtimeStream", Close),
            attach: load!("PacketMonitorAttachOutputToSession", Attach),
            _library: library,
        })
    }
}
fn check(api: &str, result: i32) -> Result<(), NativeError> {
    if result < 0 {
        Err(native_error(api, result as u32))
    } else {
        Ok(())
    }
}

pub(super) struct Session {
    api: Api,
    monitor: Handle,
    session: Handle,
    stream: Handle,
    context: Box<Sink>,
    added: HashSet<u32>,
    pub interfaces: HashMap<u32, Interface>,
}
impl Session {
    pub fn start(sink: Sink) -> Result<Self, NativeError> {
        let api = Api::load()?;
        let mut monitor = null_mut();
        check("PacketMonitorInitialize", unsafe {
            (api.initialize)(0x00010000, null_mut(), &mut monitor)
        })?;
        let mut s = Self {
            api,
            monitor,
            session: null_mut(),
            stream: null_mut(),
            context: Box::new(sink),
            added: HashSet::new(),
            interfaces: HashMap::new(),
        };
        check("PacketMonitorCreateLiveSession", unsafe {
            (s.api.create_session)(
                monitor,
                wide(&format!("Wiesler-DNS-{}-{}", std::process::id(), now())).as_ptr(),
                &mut s.session,
            )
        })?;
        s.refresh()?;
        for c in dns_constraints() {
            check("PacketMonitorAddCaptureConstraint", unsafe {
                (s.api.add_constraint)(s.session, &c)
            })?;
        }
        if s.added.is_empty() {
            return Err(NativeError {
                permission: false,
                message: "没有可捕获的网卡数据源".into(),
            });
        }
        let cfg = StreamConfig {
            context: (&mut *s.context as *mut Sink).cast(),
            event: Some(stream_event),
            data: Some(packet),
            multiplier: 10,
            truncation: 9000,
        };
        check("PacketMonitorCreateRealtimeStream", unsafe {
            (s.api.create_stream)(monitor, &cfg, &mut s.stream)
        })?;
        check("PacketMonitorAttachOutputToSession", unsafe {
            (s.api.attach)(s.session, s.stream)
        })?;
        check("PacketMonitorSetSessionActive", unsafe {
            (s.api.active)(s.session, 1)
        })?;
        Ok(s)
    }
    fn sources(&self, kind: u32, hidden: bool) -> Result<Vec<Source>, NativeError> {
        let mut needed = 0;
        let first = unsafe {
            (self.api.enum_sources)(
                self.monitor,
                kind,
                u8::from(hidden),
                0,
                &mut needed,
                null_mut(),
            )
        };
        if needed == 0 || needed > 4 * 1024 * 1024 {
            return Err(native_error(
                "PacketMonitorEnumDataSources",
                if first < 0 { first as u32 } else { 13 },
            ));
        }
        let mut buffer = vec![0usize; needed.div_ceil(size_of::<usize>())];
        let capacity = buffer.len() * size_of::<usize>();
        check("PacketMonitorEnumDataSources", unsafe {
            (self.api.enum_sources)(
                self.monitor,
                kind,
                u8::from(hidden),
                capacity,
                &mut needed,
                buffer.as_mut_ptr().cast(),
            )
        })?;
        let start = buffer.as_ptr() as usize;
        let count = unsafe { *(start as *const u32) } as usize;
        let pointer_offset = size_of::<usize>();
        if count > 4096 || pointer_offset + count * size_of::<usize>() > capacity {
            return Err(native_error("PacketMonitor sources layout", 13));
        }
        let mut sources = Vec::new();
        for i in 0..count {
            let address =
                unsafe { *((start + pointer_offset + i * size_of::<usize>()) as *const usize) };
            if address < start
                || address
                    .checked_add(size_of::<Source>())
                    .is_none_or(|end| end > start + capacity)
            {
                return Err(native_error("PacketMonitor source pointer", 13));
            }
            sources.push(unsafe { std::ptr::read_unaligned(address as *const Source) });
        }
        Ok(sources)
    }
    pub fn refresh(&mut self) -> Result<(), NativeError> {
        for source in self.sources(1, false)? {
            if self.added.contains(&source.id) {
                continue;
            }
            check("PacketMonitorAddSingleDataSourceToSession", unsafe {
                (self.api.add_source)(self.session, &source)
            })?;
            self.added.insert(source.id);
        }
        let mut interfaces = HashMap::new();
        let mut parents = HashMap::new();
        let adapters = adapter_rows();
        let all_sources = self.sources(0, true).or_else(|_| self.sources(1, true))?;
        for source in &all_sources {
            if source.parent != 0 && source.parent != source.id {
                parents.insert(source.id, source.parent);
                if source.secondary != 0 && source.secondary != source.parent {
                    parents.entry(source.secondary).or_insert(source.parent);
                }
            }
            let mut info = Interface {
                component_id: source.id,
                name: utf16(&source.description),
                ..Default::default()
            };
            if info.name.is_empty() {
                info.name = utf16(&source.name);
            }
            if source.present & 1 != 0 {
                let guid =
                    unsafe { std::ptr::read_unaligned(source.detail.as_ptr().cast::<GUID>()) };
                let mut row = MIB_IF_ROW2::default();
                if unsafe { ConvertInterfaceGuidToLuid(&guid, &mut row.InterfaceLuid) } == 0
                    && unsafe { GetIfEntry2(&mut row) } == 0
                {
                    info.name = utf16(&row.Alias);
                    info.index = Some(row.InterfaceIndex);
                    info.hardware = Some(is_hardware_adapter(&row));
                }
            }
            if info.index.is_none() {
                // 部分物理组件只提供 MAC/描述；匹配系统网卡时避免歧义。 / Match OS adapter by MAC/description when GUID is absent.
                if let Some(row) = matching_adapter(source, &adapters) {
                    info.name = utf16(&row.Alias);
                    info.index = Some(row.InterfaceIndex);
                    info.hardware = Some(is_hardware_adapter(row));
                }
            }
            interfaces.insert(source.id, info.clone());
            if source.secondary != 0 {
                info.component_id = source.secondary;
                interfaces.entry(source.secondary).or_insert(info);
            }
        }
        // 子组件沿 ParentId 链继承父网卡的物理/虚拟属性。 / Inherit adapter metadata along ParentId chain.
        let ids: Vec<u32> = interfaces.keys().copied().collect();
        for id in ids {
            if interfaces.get(&id).and_then(|i| i.hardware).is_some() {
                continue;
            }
            let mut cursor = id;
            let mut resolved = None;
            for _ in 0..8 {
                let Some(&parent_id) = parents.get(&cursor) else {
                    break;
                };
                if let Some(parent) = interfaces.get(&parent_id)
                    && parent.hardware.is_some()
                {
                    resolved = Some(parent.clone());
                    break;
                }
                cursor = parent_id;
            }
            if let Some(mut parent) = resolved {
                parent.component_id = id;
                interfaces.insert(id, parent);
            }
        }
        self.interfaces = interfaces;
        Ok(())
    }
}
pub(super) fn is_hardware_adapter(row: &MIB_IF_ROW2) -> bool {
    // 回环 (24) 与隧道 (131) 始终按非物理接口处理。 / Loopback and tunnel interfaces are never physical NICs.
    if matches!(row.Type, 24 | 131) {
        return false;
    }
    row.InterfaceAndOperStatusFlags._bitfield & 1 != 0
}
pub(super) fn adapter_rows() -> Vec<MIB_IF_ROW2> {
    let mut table = null_mut();
    if unsafe { GetIfTable2(&mut table) } != 0 || table.is_null() {
        return vec![];
    }
    let rows = unsafe {
        std::slice::from_raw_parts((*table).Table.as_ptr(), (*table).NumEntries as usize).to_vec()
    };
    unsafe {
        FreeMibTable(table.cast());
    }
    rows
}
fn unique_or_single_up(candidates: Vec<&MIB_IF_ROW2>) -> Option<&MIB_IF_ROW2> {
    if candidates.len() == 1 {
        return Some(candidates[0]);
    }
    let up: Vec<_> = candidates
        .into_iter()
        .filter(|row| row.OperStatus == 1)
        .collect();
    (up.len() == 1).then(|| up[0])
}
fn matching_adapter<'a>(source: &Source, rows: &'a [MIB_IF_ROW2]) -> Option<&'a MIB_IF_ROW2> {
    let description = utf16(&source.description);
    let mac = source.detail[0].to_ne_bytes();
    let has_mac = source.present & 8 != 0;
    let mac_matches = |row: &&MIB_IF_ROW2| {
        row.PhysicalAddressLength == 6
            && (row.PhysicalAddress[..6] == mac[..6]
                || row.PermanentPhysicalAddress[..6] == mac[..6])
    };
    let desc_matches =
        |row: &&MIB_IF_ROW2| !description.is_empty() && utf16(&row.Description) == description;
    if has_mac && !description.is_empty() {
        let both: Vec<_> = rows
            .iter()
            .filter(|row| mac_matches(row) && desc_matches(row))
            .collect();
        if let Some(row) = unique_or_single_up(both) {
            return Some(row);
        }
    }
    if has_mac {
        let by_mac: Vec<_> = rows.iter().filter(mac_matches).collect();
        if let Some(row) = unique_or_single_up(by_mac) {
            return Some(row);
        }
    }
    let by_desc: Vec<_> = rows.iter().filter(desc_matches).collect();
    unique_or_single_up(by_desc)
}
impl Drop for Session {
    fn drop(&mut self) {
        unsafe {
            if !self.session.is_null() {
                let result = (self.api.active)(self.session, 0);
                if result < 0 {
                    self.context.send(Input::Error {
                        source: "packet",
                        message: native_error("PacketMonitor stop", result as u32).message,
                    });
                }
            }
            if !self.stream.is_null() {
                (self.api.close_stream)(self.stream);
            }
            if !self.session.is_null() {
                (self.api.close_session)(self.session);
            }
            (self.api.uninitialize)(self.monitor);
        }
    }
}
fn dns_constraints() -> Vec<Constraint> {
    // PktMon Port1 默认双向匹配源/目的端口，避免孤立 Port2 导致过滤退化。 / PktMon Port1 matches either endpoint; avoid orphan Port2 rules.
    let mut constraints = Vec::with_capacity(4);
    for port in [53, 853] {
        for protocol in [6, 17] {
            let mut c: Constraint = unsafe { std::mem::zeroed() };
            let name = wide(&format!("dns-{protocol}-{port}"));
            c.name[..name.len()].copy_from_slice(&name);
            c.present = (1 << 5) | (1 << 11);
            c.protocol = protocol;
            c.port1 = port;
            constraints.push(c);
        }
    }
    constraints
}
fn advance_monotonic(counter: &AtomicU64, current: u64) -> u64 {
    let mut prev = counter.load(Ordering::Relaxed);
    while current > prev {
        match counter.compare_exchange_weak(prev, current, Ordering::Relaxed, Ordering::Relaxed) {
            Ok(_) => return current - prev,
            Err(actual) => prev = actual,
        }
    }
    0
}
fn consume_credit(credit: &AtomicU64, amount: u64) -> u64 {
    let mut current = credit.load(Ordering::Relaxed);
    while current > 0 {
        let take = current.min(amount);
        match credit.compare_exchange_weak(
            current,
            current - take,
            Ordering::Relaxed,
            Ordering::Relaxed,
        ) {
            Ok(_) => return take,
            Err(actual) => current = actual,
        }
    }
    0
}
unsafe extern "system" fn packet(context: Handle, descriptor: *const Descriptor) {
    if context.is_null() || descriptor.is_null() {
        return;
    }
    let sink = unsafe { &*context.cast::<Sink>() };
    let d = unsafe { &*descriptor };
    let read_delta = advance_monotonic(&sink.packet_read_count, d.missed_read as u64);
    // 扣除已由 ERROR_INSUFFICIENT_BUFFER(122) 归因的超长包，保留真实读/写丢包。 / Exclude 122-attributed oversized skips from read-loss while keeping real drops.
    let accounted_oversized = consume_credit(&sink.oversized_read_credit, read_delta);
    let unexplained_read_loss = read_delta - accounted_oversized;
    sink.packet_loss
        .fetch_add(d.missed_write as u64 + unexplained_read_loss, Ordering::Relaxed);
    if d.packet_length > 9000 {
        sink.oversized_warnings.fetch_add(1, Ordering::Relaxed);
        return;
    }
    if d.data.is_null()
        || d.metadata_offset as usize + 40 > d.size as usize
        || d.packet_offset as usize + d.packet_length as usize > d.size as usize
    {
        sink.packet_loss.fetch_add(1, Ordering::Relaxed);
        return;
    }
    let raw = unsafe { std::slice::from_raw_parts(d.data, d.size as usize) };
    let Some(m) = decode_metadata(raw, d.metadata_offset as usize, d.packet_offset as usize) else {
        sink.packet_loss.fetch_add(1, Ordering::Relaxed);
        return;
    };
    let bytes = unsafe {
        std::slice::from_raw_parts(
            d.data.add(d.packet_offset as usize),
            d.packet_length as usize,
        )
    }
    .to_vec();
    sink.send(Input::Packet {
        at: filetime(m.timestamp),
        group: m.group,
        component: m.component as u32,
        kind: m.kind,
        direction: m.direction,
        bytes,
        dropped: m.drop_reason != 0,
    });
}
fn decode_metadata(bytes: &[u8], start: usize, packet: usize) -> Option<Metadata> {
    // 驱动流为紧凑 40 字节，公开 C 结构为对齐 48 字节。 / Accept packed driver records and aligned public records.
    let packed = packet.checked_sub(start)? == 40;
    let m = bytes.get(start..start.checked_add(if packed { 40 } else { 48 })?)?;
    let word = |offset| u16::from_le_bytes([m[offset], m[offset + 1]]);
    let dword = |offset| u32::from_le_bytes(m[offset..offset + 4].try_into().unwrap());
    let timestamp = if packed { 32 } else { 40 };
    Some(Metadata {
        group: u64::from_le_bytes(m[..8].try_into().ok()?),
        count: word(8),
        appearance: word(10),
        direction: word(12),
        kind: word(14),
        component: word(16),
        edge: word(18),
        reserved: word(20),
        drop_reason: dword(if packed { 22 } else { 24 }),
        drop_location: dword(if packed { 26 } else { 28 }),
        processor: word(if packed { 30 } else { 32 }),
        timestamp: i64::from_le_bytes(m[timestamp..timestamp + 8].try_into().ok()?),
    })
}
#[repr(C)]
struct StopInfo {
    fatal: u8,
    reason: u32,
}
#[repr(C)]
struct ProcessInfo {
    warning: u8,
    reason: u32,
    packet_length: u64,
}
unsafe extern "system" fn stream_event(context: Handle, info: *const c_void, kind: u32) {
    if context.is_null() {
        return;
    }
    let sink = unsafe { &*context.cast::<Sink>() };
    if info.is_null() {
        if kind != 0 {
            sink.send(Input::Error {
                source: "packet",
                message: format!("Packet Monitor 流事件 {kind} 缺少状态信息"),
            });
        }
        return;
    }
    match kind {
        1 | 2 => {
            let state = unsafe { &*info.cast::<StopInfo>() };
            if kind == 2 || state.fatal != 0 || state.reason != 0 {
                sink.send(Input::Error {
                    source: "packet",
                    message: format!(
                        "Packet Monitor 已停止：系统错误 {} (0x{:08X})",
                        state.reason, state.reason
                    ),
                });
            }
        }
        3 => {
            // 超长非 DNS 大包（ERROR_INSUFFICIENT_BUFFER=122）只记为忽略计数，并抵扣其随后带入 missed_read 的 1 条计数。 / Ignore oversized 122 skips and credit their matching missed_read tick.
            let state = unsafe { &*info.cast::<ProcessInfo>() };
            if state.reason == 122 || state.reason == 0x8007007A {
                sink.oversized_warnings.fetch_add(1, Ordering::Relaxed);
                sink.oversized_read_credit.fetch_add(1, Ordering::Relaxed);
                return;
            }
            sink.packet_loss.fetch_add(1, Ordering::Relaxed);
            sink.send(Input::Warning(format!(
                "Packet Monitor {}：错误码 {} (0x{:08X})，报文 {} 字节",
                if state.warning != 0 {
                    "警告"
                } else {
                    "处理错误"
                },
                state.reason,
                state.reason,
                state.packet_length
            )));
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn compact_and_aligned_metadata_keep_timestamp_and_drop_fields() {
        for (length, drop_offset, time_offset) in [(40, 22, 32), (48, 24, 40)] {
            let mut record = vec![0u8; length + 16];
            record[12..14].copy_from_slice(&4u16.to_le_bytes());
            record[14..16].copy_from_slice(&3u16.to_le_bytes());
            record[16..18].copy_from_slice(&16u16.to_le_bytes());
            record[drop_offset..drop_offset + 4].copy_from_slice(&17u32.to_le_bytes());
            record[time_offset..time_offset + 8]
                .copy_from_slice(&134381793155641244i64.to_le_bytes());
            let metadata = decode_metadata(&record, 0, length).unwrap();
            assert_eq!(metadata.timestamp, 134381793155641244);
            assert_eq!(metadata.drop_reason, 17);
            assert_eq!(metadata.component, 16);
            assert_eq!(metadata.direction, 4);
            assert!(decode_metadata(&record[..30], 0, length).is_none());
        }
    }
    #[test]
    fn adapter_fallback_requires_unique_exact_identity() {
        let mut source: Source = unsafe { std::mem::zeroed() };
        let description = wide("test network adapter");
        source.description[..description.len()].copy_from_slice(&description);
        let mut row = MIB_IF_ROW2 {
            InterfaceIndex: 7,
            ..Default::default()
        };
        row.Description[..description.len()].copy_from_slice(&description);
        assert_eq!(matching_adapter(&source, &[row]).unwrap().InterfaceIndex, 7);
        assert!(matching_adapter(&source, &[row, row]).is_none());
        source.present = 8;
        source.detail[0] = u64::from_ne_bytes([1, 2, 3, 4, 5, 6, 0, 0]);
        assert_eq!(matching_adapter(&source, &[row]).unwrap().InterfaceIndex, 7);
        assert!(matching_adapter(&source, &[row, row]).is_none());
        row.Description.fill(0);
        assert!(matching_adapter(&source, &[row]).is_none());
        row.PhysicalAddressLength = 6;
        row.PhysicalAddress[..6].copy_from_slice(&[1, 2, 3, 4, 5, 6]);
        assert!(matching_adapter(&source, &[row]).is_some());
    }
    #[test]
    fn stream_warning_retains_collection_and_reports_actual_reason() {
        use std::sync::{Arc, atomic::AtomicU64, mpsc};
        let (tx, rx) = mpsc::sync_channel(8);
        let mut sink = Sink {
            tx,
            queue_drops: Arc::new(AtomicU64::new(0)),
            packet_loss: Arc::new(AtomicU64::new(0)),
            packet_read_count: Arc::new(AtomicU64::new(0)),
            oversized_read_credit: Arc::new(AtomicU64::new(0)),
            event_loss: Arc::new(AtomicU64::new(0)),
            oversized_warnings: Arc::new(AtomicU64::new(0)),
        };
        let info = ProcessInfo {
            warning: 1,
            reason: 122,
            packet_length: 9100,
        };
        unsafe {
            stream_event(
                (&mut sink as *mut Sink).cast(),
                (&info as *const ProcessInfo).cast(),
                3,
            );
        }
        assert_eq!(sink.oversized_warnings.load(Ordering::Relaxed), 1);
        assert_eq!(sink.packet_loss.load(Ordering::Relaxed), 0);
        assert!(rx.try_recv().is_err());
        let non_oversize = ProcessInfo {
            warning: 0,
            reason: 87,
            packet_length: 128,
        };
        unsafe {
            stream_event(
                (&mut sink as *mut Sink).cast(),
                (&non_oversize as *const ProcessInfo).cast(),
                3,
            );
        }
        assert_eq!(sink.packet_loss.load(Ordering::Relaxed), 1);
        assert!(
            matches!(rx.try_recv().unwrap(), Input::Warning(message) if message.contains("87") && message.contains("128"))
        );
        let stop = StopInfo {
            fatal: 0,
            reason: 0,
        };
        unsafe {
            stream_event(
                (&mut sink as *mut Sink).cast(),
                (&stop as *const StopInfo).cast(),
                1,
            );
        }
        assert!(rx.try_recv().is_err());
        let fatal = StopInfo {
            fatal: 1,
            reason: 13,
        };
        unsafe {
            stream_event(
                (&mut sink as *mut Sink).cast(),
                (&fatal as *const StopInfo).cast(),
                2,
            );
        }
        assert!(matches!(
            rx.try_recv().unwrap(),
            Input::Error {
                source: "packet",
                ..
            }
        ));
    }
    #[test]
    fn abi_layout_matches_windows_x64() {
        #[cfg(target_pointer_width = "64")]
        {
            assert_eq!(size_of::<Source>(), 424);
            assert_eq!(size_of::<Constraint>(), 216);
            assert_eq!(size_of::<Metadata>(), 48);
            assert_eq!(size_of::<Descriptor>(), 32);
            assert_eq!(size_of::<StreamConfig>(), 32);
        }
    }
    #[test]
    fn dns_constraints_use_bidirectional_port1_without_orphan_port2() {
        let constraints = dns_constraints();
        assert_eq!(constraints.len(), 4);
        for c in &constraints {
            assert_eq!(c.present, (1 << 5) | (1 << 11));
            assert!(matches!(c.protocol, 6 | 17));
            assert!(matches!(c.port1, 53 | 853));
            assert_eq!(c.port2, 0);
        }
    }
    #[test]
    fn oversized_122_skips_do_not_inflate_packet_loss_while_real_drops_are_kept() {
        use std::sync::{Arc, atomic::AtomicU64, mpsc};
        let (tx, _rx) = mpsc::sync_channel(8);
        let mut sink = Sink {
            tx,
            queue_drops: Arc::new(AtomicU64::new(0)),
            packet_loss: Arc::new(AtomicU64::new(0)),
            packet_read_count: Arc::new(AtomicU64::new(0)),
            oversized_read_credit: Arc::new(AtomicU64::new(0)),
            event_loss: Arc::new(AtomicU64::new(0)),
            oversized_warnings: Arc::new(AtomicU64::new(0)),
        };
        let mut raw = vec![0u8; 56];
        raw[14..16].copy_from_slice(&3u16.to_le_bytes());
        let oversized = ProcessInfo {
            warning: 1,
            reason: 122,
            packet_length: 9064,
        };
        // 连续 3 次 122 警告后，下一条正常包的 missed_read 累计到 3，应全部抵扣。 / 3 oversized 122 warnings followed by missed_read=3 should yield 0 packet_loss.
        for _ in 0..3 {
            unsafe {
                stream_event(
                    (&mut sink as *mut Sink).cast(),
                    (&oversized as *const ProcessInfo).cast(),
                    3,
                );
            }
        }
        let desc1 = Descriptor {
            data: raw.as_ptr(),
            size: raw.len() as u32,
            metadata_offset: 0,
            packet_offset: 40,
            packet_length: 16,
            missed_write: 0,
            missed_read: 3,
        };
        unsafe { packet((&mut sink as *mut Sink).cast(), &desc1) };
        assert_eq!(sink.oversized_warnings.load(Ordering::Relaxed), 3);
        assert_eq!(sink.packet_loss.load(Ordering::Relaxed), 0);
        // 后续真实读丢包 (+2) 与写丢包 (+4) 必须如实计入。 / Real read/write drops beyond 122 credit must still be counted.
        let desc2 = Descriptor {
            missed_write: 4,
            missed_read: 5,
            ..desc1
        };
        unsafe { packet((&mut sink as *mut Sink).cast(), &desc2) };
        assert_eq!(sink.packet_loss.load(Ordering::Relaxed), 6);
        // 乱序回退的 missed_read 不应发生无符号下溢。 / Out-of-order lower missed_read must not underflow.
        let desc3 = Descriptor {
            missed_write: 0,
            missed_read: 2,
            ..desc1
        };
        unsafe { packet((&mut sink as *mut Sink).cast(), &desc3) };
        assert_eq!(sink.packet_loss.load(Ordering::Relaxed), 6);
    }
    #[test]
    fn api_exports_are_available() {
        assert!(Api::load().is_ok());
    }
}
