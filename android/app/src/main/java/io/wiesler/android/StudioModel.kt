package io.wiesler.android

data class StudioFlow(
    val id: String, val start: String, val app: String, val host: String,
    val source: String, val destination: String, val protocol: String, val route: String,
    val rule: String, val download: Long, val upload: Long, val raw: String,
    val rx: Double? = null, val tx: Double? = null, val event: String? = null
)

data class StudioFrame(
    val time: Long, val wallTime: Long, val flows: List<StudioFlow>, val error: String? = null
)

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

    fun switchSource(value: Boolean) {
        if (live == value) return
        live = value; frames.clear(); held = null; baseline = null; selectedId = null
    }

    fun resetBaseline() { baseline = null }

    fun accept(flows: List<StudioFlow>, time: Long, wallTime: Long, error: String? = null) {
        val previous = baseline
        val prior = previous?.flows?.associateBy { it.id }.orEmpty()
        val elapsed = previous?.let { time - it.time } ?: 0L
        val sampled = if (error != null) emptyList() else flows.map { flow ->
            val old = prior[flow.id]
            // 新会话和采样断点只建立基线。 / Rebaseline new sessions and sampling gaps.
            val valid = elapsed in 1..5000 && old != null && old.start == flow.start &&
                old.route == flow.route && old.download >= 0 && old.upload >= 0 &&
                flow.download >= old.download && flow.upload >= old.upload
            flow.copy(rx = if (valid) (flow.download - old!!.download) * 1000.0 / elapsed else null,
                tx = if (valid) (flow.upload - old!!.upload) * 1000.0 / elapsed else null)
        }
        val frame = StudioFrame(time, wallTime, sampled, error)
        baseline = if (error == null) frame else null
        frames.add(frame)
        if (frames.size > 60) frames.removeAt(0)
    }

    fun freeze() { held = current }
    fun replay(index: Int) { held = frames.getOrNull(index) }
    fun now() { held = null }
    fun filtered(): List<StudioFlow> = current?.flows.orEmpty().filter {
        (protocol == "全部" || it.protocol == protocol) &&
            listOf(it.app, it.host, it.source, it.destination, it.route, it.rule).any { value -> value.contains(query, true) }
    }
    fun graphFlows(): List<StudioFlow> {
        val matches = filtered()
        val selected = matches.firstOrNull { it.id == selectedId }
        return (listOfNotNull(selected) + matches.filter { it.id != selected?.id }).take(6)
    }
}

fun studioEndpoint(host: String, port: String): String {
    val address = host.ifBlank { "未知" }
    val formatted = if (address.contains(':') && !address.startsWith('[')) "[$address]" else address
    return "$formatted:${port.toIntOrNull()?.takeIf { it in 0..65535 } ?: "?"}"
}

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
        StudioFlow("demo-$i", "demo-start", apps[i], hosts[i], "192.0.2.2:${51000 + i}",
            "${ips[i]}:$port", network, route, "Demo", down, up, raw,
            event = if (i == 1 && tick >= 18) "演示事件：TLS 握手超时\n场景脚本 · 请求 #18 · 超时阈值 3 秒" else null)
    }
}
