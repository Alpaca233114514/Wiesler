package io.wiesler.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class DiscoveredController(
    val address: String,
    val version: String?,
    val ready: Boolean,
    val requiresAuth: Boolean = false
)

data class ControllerDiscoveryReport(
    val controllers: List<DiscoveredController>,
    val scannedPorts: Int
)

object ControllerDiscovery {
    val DEFAULT_PORTS = listOf(9090, 9097, 9091, 9099, 9092, 9098, 9999)

    data class VersionProbe(val isMihomo: Boolean, val version: String?, val authRequired: Boolean)
    data class ConnectionsProbe(val isMihomo: Boolean, val ready: Boolean, val authRequired: Boolean)

    fun parseVersionResponse(code: Int, body: String): VersionProbe {
        if (code == 401 || code == 403) return VersionProbe(true, null, true)
        if (code != 200) return VersionProbe(false, null, false)
        val version = runCatching {
            JSONObject(body).optString("version").trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
        return VersionProbe(version != null, version, false)
    }

    fun parseConnectionsResponse(code: Int, body: String): ConnectionsProbe {
        if (code == 401 || code == 403) return ConnectionsProbe(true, false, true)
        if (code != 200) return ConnectionsProbe(false, false, false)
        val hasConnections = runCatching {
            val json = JSONObject(body)
            json.has("connections") && (json.isNull("connections") || json.optJSONArray("connections") != null)
        }.getOrDefault(false)
        return ConnectionsProbe(hasConnections, hasConnections, false)
    }

    fun isLoopbackHttp(address: String): Boolean {
        val uri = runCatching { URI(address) }.getOrNull() ?: return false
        return uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]")
    }

    fun scanLocal(extraAddresses: List<String> = emptyList()): ControllerDiscoveryReport {
        val candidates = LinkedHashSet<String>()
        extraAddresses.map { it.trim().trimEnd('/') }.filter(::isLoopbackHttp).forEach { candidates.add(it) }
        DEFAULT_PORTS.forEach { candidates.add("http://127.0.0.1:$it") }
        val pool = Executors.newFixedThreadPool(4)
        return try {
            val tasks = candidates.map { addr -> Callable { inspect(addr) } }
            val results = pool.invokeAll(tasks, 4, TimeUnit.SECONDS)
                .mapNotNull { future -> runCatching { if (future.isDone && !future.isCancelled) future.get() else null }.getOrNull() }
                .sortedWith(compareBy<DiscoveredController>({ !it.ready }, { it.address }))
            ControllerDiscoveryReport(results, candidates.size)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun inspect(address: String): DiscoveredController? {
        // 扫描未知回环端口绝不携带凭证，并同时验证 /version 和 /connections。
        // Never send secrets during discovery; verify both /version and /connections.
        val ver = probe("$address/version") ?: return null
        val versionResult = parseVersionResponse(ver.first, ver.second)
        if (!versionResult.isMihomo) return null
        if (versionResult.authRequired) {
            return DiscoveredController(address, null, ready = false, requiresAuth = true)
        }
        val conn = probe("$address/connections") ?: return null
        val connResult = parseConnectionsResponse(conn.first, conn.second)
        if (!connResult.isMihomo) return null
        return DiscoveredController(
            address = address,
            version = versionResult.version,
            ready = connResult.ready,
            requiresAuth = connResult.authRequired
        )
    }

    private fun probe(url: String): Pair<Int, String>? = runCatching {
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 800
            connection.readTimeout = 800
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            val code = connection.responseCode
            val body = if (code == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(2048)
                    while (sb.length < 262_144) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                    }
                    sb.toString()
                }
            } else ""
            code to body
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
