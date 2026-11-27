package io.wiesler.android

import org.json.JSONObject
import java.util.Locale

enum class FlowRouting(val label: String) {
    DIRECT("DIRECT 直连"),
    PROXY("代理转发"),
    UNKNOWN("路由待确认")
}

enum class StudioSourceFilter(val label: String) {
    ALL("全部来源"),
    DIRECT("DIRECT 直连"),
    PROXY("代理转发"),
    OVERSEAS("海外出口"),
    OTHER("其他")
}

enum class RouteNodeKind {
    DEVICE,
    ROUTE,
    UNKNOWN,
    SERVER
}

data class RouteNode(
    val kind: RouteNodeKind,
    val label: String,
    val detail: String
)

data class StudioHostGroup(
    val key: String,
    val label: String,
    val flows: List<StudioFlow>
)

data class StudioFlow(
    val id: String,
    val start: String,
    val app: String,
    val host: String,
    val source: String,
    val destination: String,
    val protocol: String,
    val route: String,
    val rule: String,
    val download: Long,
    val upload: Long,
    val raw: String,
    val rx: Double? = null,
    val tx: Double? = null,
    val event: String? = null
) {
    // 缓存高频衍生字段，避免每秒筛选/计数阶段重复 split 与字符串分配。
    // Cache hot derived properties to avoid repeated split/list allocations per tick.
    val hostKey: String = host.lowercase(Locale.ROOT).trimEnd('.')
    val flowKey: String = "$id\u0000$start"
    val destinationPort: String = run {
        val idx = destination.lastIndexOf(':')
        if (idx >= 0 && idx + 1 < destination.length) destination.substring(idx + 1) else "?"
    }
    val exitNode: String? = run {
        val sep = route.indexOf(" → ")
        val first = (if (sep >= 0) route.substring(0, sep) else route).trim()
        first.takeIf { it.isNotEmpty() && it != "未知" }
    }
    val routing: FlowRouting = when {
        exitNode == null -> FlowRouting.UNKNOWN
        exitNode.equals("DIRECT", ignoreCase = true) -> FlowRouting.DIRECT
        else -> FlowRouting.PROXY
    }
    val chainList: List<String>
        get() = route.split(" → ").map { it.trim() }.filter { it.isNotEmpty() && it != "未知" }

    fun formattedRawJson(): String {
        if (raw.isNotBlank()) {
            return runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw)
        }
        return JSONObject()
            .put("id", id)
            .put("start", start)
            .put("app", app)
            .put("host", host)
            .put("source", source)
            .put("destination", destination)
            .put("protocol", protocol)
            .put("chains", chainList)
            .put("rule", rule)
            .put("download", download)
            .put("upload", upload)
            .apply {
                rx?.let { put("rxBytesPerSec", it) }
                tx?.let { put("txBytesPerSec", it) }
                event?.let { put("event", it) }
            }
            .toString(2)
    }
}

data class StudioFrame(
    val time: Long,
    val wallTime: Long,
    val flows: List<StudioFlow>,
    val error: String? = null,
    val downloadTotal: Long? = null,
    val uploadTotal: Long? = null,
    val rxTotal: Double? = null,
    val txTotal: Double? = null
) {
    // 构造帧时单遍统计直连/代理连接数，避免 UI 与发布循环重复遍历。
    // Precompute routing counts once per frame instead of rescanning flows on every UI publish.
    val directCount: Int
    val proxyCount: Int

    init {
        var d = 0
        var p = 0
        for (flow in flows) {
            when (flow.routing) {
                FlowRouting.DIRECT -> d++
                FlowRouting.PROXY -> p++
                FlowRouting.UNKNOWN -> Unit
            }
        }
        directCount = d
        proxyCount = p
    }
}

class StudioSession {
    var live = false
        private set
    val frames = ArrayList<StudioFrame>()
    var held: StudioFrame? = null
        private set
    private var baseline: StudioFrame? = null
    val current get() = held ?: frames.lastOrNull()
    var selectedId: String? = null
    var query = ""
    var protocol = "全部"
    var sourceFilter = StudioSourceFilter.ALL
    var overseasExits: Set<String> = emptySet()

    fun switchSource(value: Boolean) {
        if (live == value) return
        live = value
        frames.clear()
        held = null
        baseline = null
        selectedId = null
    }

    fun resetBaseline() {
        baseline = null
    }

    fun accept(
        flows: List<StudioFlow>,
        time: Long,
        wallTime: Long,
        error: String? = null,
        downloadTotal: Long? = null,
        uploadTotal: Long? = null
    ) {
        val previous = baseline
        val prior = previous?.flows?.associateBy { it.id }.orEmpty()
        val elapsed = previous?.let { time - it.time } ?: 0L
        val sampled = if (error != null) emptyList() else flows.map { flow ->
            val old = prior[flow.id]
            // 新会话和采样断点只建立基线。 / Rebaseline new sessions and sampling gaps.
            val valid = elapsed in 1..5000 && old != null && old.start == flow.start &&
                old.route == flow.route && old.download >= 0 && old.upload >= 0 &&
                flow.download >= old.download && flow.upload >= old.upload
            flow.copy(
                rx = if (valid) (flow.download - old!!.download) * 1000.0 / elapsed else null,
                tx = if (valid) (flow.upload - old!!.upload) * 1000.0 / elapsed else null
            )
        }
        val prevDown = previous?.downloadTotal
        val prevUp = previous?.uploadTotal
        val validTotals = error == null && elapsed in 1..5000 &&
            prevDown != null && prevUp != null &&
            downloadTotal != null && uploadTotal != null &&
            downloadTotal >= prevDown && uploadTotal >= prevUp
        val frame = StudioFrame(
            time = time,
            wallTime = wallTime,
            flows = sampled,
            error = error,
            downloadTotal = if (error == null) downloadTotal else null,
            uploadTotal = if (error == null) uploadTotal else null,
            rxTotal = if (validTotals) (downloadTotal!! - prevDown!!) * 1000.0 / elapsed else null,
            txTotal = if (validTotals) (uploadTotal!! - prevUp!!) * 1000.0 / elapsed else null
        )
        baseline = if (error == null) frame else null
        frames.add(frame)
        if (frames.size > 60) frames.removeAt(0)
    }

    fun freeze() {
        held = current
    }

    fun replay(index: Int) {
        held = frames.getOrNull(index)
    }

    fun now() {
        held = null
    }

    fun filtered(): List<StudioFlow> = current?.flows.orEmpty().filter { flow ->
        val matchesProtocol = protocol == "全部" || flow.protocol.equals(protocol, ignoreCase = true)
        val matchesSource = when (sourceFilter) {
            StudioSourceFilter.ALL -> true
            StudioSourceFilter.DIRECT -> flow.routing == FlowRouting.DIRECT
            StudioSourceFilter.PROXY -> flow.routing == FlowRouting.PROXY
            StudioSourceFilter.OVERSEAS -> trafficRoute(flow.exitNode, overseasExits) == TrafficRoute.OVERSEAS
            StudioSourceFilter.OTHER -> trafficRoute(flow.exitNode, overseasExits) == TrafficRoute.OTHER
        }
        val matchesQuery = query.isBlank() || listOf(
            flow.app, flow.host, flow.source, flow.destination, flow.route, flow.rule
        ).any { value -> value.contains(query, ignoreCase = true) }
        matchesProtocol && matchesSource && matchesQuery
    }

    fun graphFlows(): List<StudioFlow> {
        val matches = filtered()
        val selected = matches.firstOrNull { it.id == selectedId }
        return (listOfNotNull(selected) + matches.filter { it.id != selected?.id }).take(6)
    }

    fun graphHostGroups(maxGroups: Int = 6): List<StudioHostGroup> =
        graphHostGroups(filtered(), maxGroups)

    fun graphHostGroups(matches: List<StudioFlow>, maxGroups: Int = 6): List<StudioHostGroup> {
        val groups = groupByHost(matches)
        if (groups.size <= maxGroups) return groups
        val displayed = groups.take(maxGroups).toMutableList()
        val selectedGroup = groups.firstOrNull { group -> group.flows.any { it.id == selectedId } }
        if (selectedGroup != null && displayed.none { it.key == selectedGroup.key }) {
            displayed[maxGroups - 1] = selectedGroup
        }
        return displayed
    }

    fun rateHistory(upToCurrent: Boolean = true, filterKeys: Set<String>? = null): Map<String, List<Rate?>> {
        val slice = if (upToCurrent && held != null) {
            val idx = frames.indexOf(held)
            if (idx >= 0) frames.subList(0, idx + 1) else listOfNotNull(held)
        } else {
            frames
        }
        return computeRateHistory(slice, filterKeys)
    }
}

fun groupByHost(flows: List<StudioFlow>): List<StudioHostGroup> {
    val groups = LinkedHashMap<String, MutableList<StudioFlow>>()
    val labels = LinkedHashMap<String, String>()
    for (flow in flows) {
        val key = flow.hostKey
        groups.getOrPut(key) { ArrayList() }.add(flow)
        labels.putIfAbsent(key, flow.host)
    }
    return groups.map { (key, members) ->
        StudioHostGroup(key = key, label = labels.getValue(key), flows = members)
    }
}

fun routePath(flow: StudioFlow): List<RouteNode> {
    val nodes = ArrayList<RouteNode>()
    nodes.add(RouteNode(RouteNodeKind.DEVICE, "手机", "${flow.app} · ${flow.source}"))
    when (flow.routing) {
        FlowRouting.PROXY -> {
            // 控制器从出口向外追加策略组，展示时反向展开。 / Reverse leaf-first logical chain for display.
            for (name in flow.chainList.reversed()) {
                nodes.add(RouteNode(RouteNodeKind.ROUTE, name, "逻辑路由节点 · $name"))
            }
        }
        FlowRouting.UNKNOWN -> {
            nodes.add(RouteNode(RouteNodeKind.UNKNOWN, "路由待确认", "连接快照未提供代理路径"))
        }
        FlowRouting.DIRECT -> Unit
    }
    nodes.add(RouteNode(RouteNodeKind.SERVER, "服务器", "${flow.host} · ${flow.destination}"))
    return nodes
}

fun computeRateHistory(frames: List<StudioFrame>, filterKeys: Set<String>? = null): Map<String, List<Rate?>> {
    if (frames.isEmpty()) return emptyMap()
    if (filterKeys != null && filterKeys.isEmpty()) return emptyMap()
    val history = LinkedHashMap<String, Array<Rate?>>()
    frames.forEachIndexed { index, frame ->
        for (flow in frame.flows) {
            if (flow.rx == null && flow.tx == null) continue
            val key = flow.flowKey
            if (filterKeys != null && key !in filterKeys) continue
            val series = history.getOrPut(key) { arrayOfNulls(frames.size) }
            series[index] = Rate(flow.rx ?: 0.0, flow.tx ?: 0.0)
        }
    }
    return history.mapValues { (_, arr) -> arr.toList() }
}

fun parseStudioFlow(item: JSONObject): StudioFlow {
    val metadata = item.optJSONObject("metadata") ?: JSONObject()
    fun value(camel: String, snake: String = camel) =
        metadata.optString(camel).ifBlank { metadata.optString(snake) }
    val host = value("host").ifBlank { value("destinationIP", "destination_ip").ifBlank { "未知目标" } }
    val chains = item.optJSONArray("chains")
    val chainText = (0 until (chains?.length() ?: 0))
        .map { chains!!.optString(it) }
        .filter { it.isNotBlank() }
        .joinToString(" → ")
        .ifBlank { "未知" }
    val process = value("process").ifBlank { value("type").ifBlank { "未知应用" } }
    // 不在 60 帧环缓冲中逐连接保存原始 JSON 字符串，避免数百连接下每秒制造数十 MB 堆垃圾。
    // Avoid storing raw JSON strings per flow across 60 frames; synthesize on demand in the inspector.
    return StudioFlow(
        id = item.optString("id"),
        start = item.optString("start"),
        app = process,
        host = host,
        source = studioEndpoint(value("sourceIP", "source_ip"), value("sourcePort", "source_port")),
        destination = studioEndpoint(
            value("destinationIP", "destination_ip").ifBlank { host },
            value("destinationPort", "destination_port")
        ),
        protocol = value("network").uppercase(Locale.ROOT).ifBlank { "未知" },
        route = chainText,
        rule = listOf(item.optString("rule"), item.optString("rulePayload"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "未知" },
        download = item.optLong("download", -1),
        upload = item.optLong("upload", -1),
        raw = ""
    )
}

fun studioEndpoint(host: String, port: String): String {
    val address = host.ifBlank { "未知" }
    val formatted = if (address.contains(':') && !address.startsWith('[')) "[$address]" else address
    return "$formatted:${port.toIntOrNull()?.takeIf { it in 0..65535 } ?: "?"}"
}

fun formatBytes(value: Double): String {
    if (!value.isFinite() || value < 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var n = value
    var i = 0
    while (n >= 1024 && i < units.lastIndex) {
        n /= 1024
        i++
    }
    return if (i == 0) "${n.toLong()} ${units[i]}" else String.format(Locale.ROOT, "%.1f %s", n, units[i])
}

fun formatRate(value: Double?): String = value?.let { "${formatBytes(it)}/s" } ?: "—"

fun studioDemo(tick: Int): List<StudioFlow> {
    val apps = listOf("Browser", "Browser", "Cloud Sync", "System", "System")
    val hosts = listOf("www.example.com", "api.example.com", "sync.example.net", "dns.example.net", "updates.example.org")
    val ips = listOf("203.0.113.10", "203.0.113.20", "198.51.100.42", "192.0.2.53", "192.0.2.80")
    val downloads = listOf(650000L, 82000L, 12000L, 1200L, 175000L)
    val uploads = listOf(24000L, 12000L, 430000L, 700L, 5000L)
    return hosts.indices.map { i ->
        val port = if (i == 3) 53 else 443
        val route = if (i >= 3) "DIRECT" else "WireGuard-demo"
        val network = if (i == 3) "UDP" else "TCP"
        val down = 800000 + i * 250000 + tick * downloads[i]
        val up = 40000 + tick * uploads[i]
        val raw = """{"id":"demo-$i","start":"demo-start","metadata":{"process":"${apps[i]}","host":"${hosts[i]}","sourceIP":"192.0.2.2","sourcePort":${51000 + i},"destinationIP":"${ips[i]}","destinationPort":$port,"network":"$network"},"chains":["$route"],"rule":"Demo","download":$down,"upload":$up}"""
        StudioFlow(
            "demo-$i", "demo-start", apps[i], hosts[i], "192.0.2.2:${51000 + i}",
            "${ips[i]}:$port", network, route, "Demo", down, up, raw,
            event = if (i == 1 && tick >= 18) "演示事件：TLS 握手超时\n场景脚本 · 请求 #18 · 超时阈值 3 秒" else null
        )
    }
}
