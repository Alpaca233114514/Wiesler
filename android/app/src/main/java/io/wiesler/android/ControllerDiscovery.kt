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

data class ProbeDiagnostic(
    val address: String,
    val reason: String,
    val kind: ProbeFailureKind
)

enum class ProbeFailureKind {
    REFUSED,
    TIMEOUT,
    NON_MIHOMO,
    ERROR
}

data class ControllerDiscoveryReport(
    val controllers: List<DiscoveredController>,
    val scannedPorts: Int,
    val scannedAddresses: List<String> = emptyList(),
    val diagnostics: List<ProbeDiagnostic> = emptyList()
) {
    fun failureSummary(): String {
        if (controllers.isNotEmpty()) return ""
        val refused = diagnostics.count { it.kind == ProbeFailureKind.REFUSED }
        val timeout = diagnostics.count { it.kind == ProbeFailureKind.TIMEOUT }
        val nonMihomo = diagnostics.count { it.kind == ProbeFailureKind.NON_MIHOMO }
        val other = diagnostics.count { it.kind == ProbeFailureKind.ERROR }
        val parts = buildList {
            if (refused > 0) add("$refused 个未监听/拒绝")
            if (timeout > 0) add("$timeout 个超时")
            if (nonMihomo > 0) add("$nonMihomo 个非控制器响应")
            if (other > 0) add("$other 个网络错误")
        }
        val detail = if (parts.isEmpty()) "无可用响应" else parts.joinToString("，")
        return "已扫描 $scannedPorts 个常用端口（$detail），请确认代理已开启 External Controller 或手动填写端口"
    }
}

object ControllerDiscovery {
    // 覆盖常见 Clash / Mihomo / Clash Verge / FlClash / sing-box / Hiddify / HikariVPN 控制器默认端口。
    // Common Clash/Mihomo/sing-box external-controller ports (Android sandbox blocks /proc/net/tcp enumeration).
    val DEFAULT_PORTS = listOf(
        9090, 9097, 9091, 9092, 9093, 9095, 9096, 9098, 9099, 9999,
        12440, 16756, 19090, 6170, 20122
    )

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")
    private val BARE_LOOPBACK_REGEX = Regex("""^(127\.0\.0\.1|localhost|\[::1\]|0\.0\.0\.0)(:\d+)?$""", RegexOption.IGNORE_CASE)

    data class VersionProbe(val isMihomo: Boolean, val version: String?, val authRequired: Boolean)
    data class ConnectionsProbe(val isMihomo: Boolean, val ready: Boolean, val authRequired: Boolean)

    /**
     * 规范化并校验控制器根地址：本机回环地址省略协议头时自动补全 http://，并给出具体失败原因。
     * Normalize and validate controller root URL; auto-prepend http:// for bare loopback addresses.
     */
    fun normalizeControllerAddress(raw: String): Result<String> {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入控制器地址，例如 127.0.0.1:9090"))
        }
        val candidate = if (!trimmed.contains("://") && BARE_LOOPBACK_REGEX.matches(trimmed)) {
            "http://$trimmed"
        } else {
            trimmed
        }
        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("地址格式无效，请填写如 http://127.0.0.1:9090"))
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme == null || host == null) {
            return Result.failure(IllegalArgumentException("地址缺少协议头或主机名，请填写如 http://127.0.0.1:9090"))
        }
        if (host == "0.0.0.0" || host == "[::]") {
            return Result.failure(IllegalArgumentException("$host 是服务端监听地址，连接本机请改为 127.0.0.1"))
        }
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) {
            return Result.failure(IllegalArgumentException("控制器地址不能包含账号、查询参数或锚点，密钥请填在下方 Secret 栏"))
        }
        if (!uri.path.isNullOrEmpty()) {
            return Result.failure(IllegalArgumentException("请仅填写控制器根地址，不要包含路径（如 ${uri.path}）"))
        }
        val rawAuthority = uri.rawAuthority ?: ""
        val hasExplicitPort = if (host.startsWith("[")) {
            rawAuthority.substringAfter("]", "").startsWith(":")
        } else {
            rawAuthority.contains(":")
        }
        if (hasExplicitPort && uri.port !in 1..65535) {
            return Result.failure(IllegalArgumentException("端口号无效，需在 1–65535 之间"))
        }
        val loopback = host in LOOPBACK_HOSTS
        if (scheme != "https" && !(scheme == "http" && loopback)) {
            return Result.failure(IllegalArgumentException("明文 HTTP 仅允许本机回环地址（127.0.0.1 / localhost），远程控制器请使用 HTTPS"))
        }
        val portSuffix = if (uri.port > 0) ":${uri.port}" else ""
        return Result.success("$scheme://$host$portSuffix")
    }

    fun parseVersionResponse(code: Int, body: String): VersionProbe {
        if (code == 401 || code == 403) return VersionProbe(true, null, true)
        if (code != 200) return VersionProbe(false, null, false)
        val version = runCatching {
            JSONObject(body).optString("version").trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
        return VersionProbe(version != null, version, false)
    }

    data class ProbeResponse(val code: Int, val body: String, val truncated: Boolean)

    private val truncatedConnectionsKey = Regex(""""connections"\s*:\s*(\[|null)""")
    private val truncatedTotalsKey = Regex(""""(downloadTotal|uploadTotal)"\s*:\s*\d+""")
    private val truncatedFlowFieldsKey = Regex(""""metadata"\s*:\s*\{""")
    private val truncatedChainOrRuleKey = Regex(""""(chains|rule|sourceIP|destinationIP)"\s*:""")

    fun parseConnectionsResponse(code: Int, body: String, truncated: Boolean = false): ConnectionsProbe {
        if (code == 401 || code == 403) return ConnectionsProbe(true, false, true)
        if (code != 200) return ConnectionsProbe(false, false, false)
        val hasConnections = runCatching {
            val json = JSONObject(body)
            json.has("connections") && (json.isNull("connections") || json.optJSONArray("connections") != null)
        }.getOrDefault(false)
        if (hasConnections) return ConnectionsProbe(true, true, false)
        // 连接数很多时 /connections 正文会超过探测缓冲上限；Mihomo 把 total 放前面，sing-box 按字母序把 connections 放前面。
        // Large /connections payloads exceed the read cap; accept either Mihomo totals or sing-box connection metadata prefixes.
        if (truncated) {
            val trimmed = body.trimStart()
            val hasArray = trimmed.startsWith("{") && truncatedConnectionsKey.containsMatchIn(trimmed)
            val prefixLooksMihomo = hasArray && (
                truncatedTotalsKey.containsMatchIn(trimmed) ||
                    (truncatedFlowFieldsKey.containsMatchIn(trimmed) && truncatedChainOrRuleKey.containsMatchIn(trimmed))
                )
            return ConnectionsProbe(prefixLooksMihomo, prefixLooksMihomo, false)
        }
        return ConnectionsProbe(false, false, false)
    }

    fun isLoopbackHttp(address: String): Boolean {
        val uri = runCatching { URI(address) }.getOrNull() ?: return false
        return uri.scheme.equals("http", ignoreCase = true) && uri.host?.lowercase() in LOOPBACK_HOSTS
    }

    private sealed class InspectOutcome {
        data class Found(val controller: DiscoveredController) : InspectOutcome()
        data class Failed(val diagnostic: ProbeDiagnostic) : InspectOutcome()
    }

    fun scanLocal(extraAddresses: List<String> = emptyList()): ControllerDiscoveryReport {
        val candidates = LinkedHashSet<String>()
        extraAddresses
            .mapNotNull { normalizeControllerAddress(it).getOrNull() }
            .filter(::isLoopbackHttp)
            .forEach { candidates.add(it) }
        DEFAULT_PORTS.forEach { candidates.add("http://127.0.0.1:$it") }
        val candidateList = candidates.toList()
        val pool = Executors.newFixedThreadPool(8)
        return try {
            val tasks = candidateList.map { addr -> Callable { inspect(addr) } }
            val futures = pool.invokeAll(tasks, 5, TimeUnit.SECONDS)
            val found = ArrayList<DiscoveredController>()
            val diagnostics = ArrayList<ProbeDiagnostic>()
            futures.forEachIndexed { index, future ->
                val addr = candidateList[index]
                val outcome = runCatching {
                    if (future.isDone && !future.isCancelled) future.get()
                    else InspectOutcome.Failed(ProbeDiagnostic(addr, "扫描总超时", ProbeFailureKind.TIMEOUT))
                }.getOrElse { err ->
                    InspectOutcome.Failed(ProbeDiagnostic(addr, err.message ?: "探测异常", ProbeFailureKind.ERROR))
                }
                when (outcome) {
                    is InspectOutcome.Found -> found.add(outcome.controller)
                    is InspectOutcome.Failed -> diagnostics.add(outcome.diagnostic)
                }
            }
            val sorted = found.sortedWith(compareBy<DiscoveredController>({ !it.ready }, { it.address }))
            ControllerDiscoveryReport(
                controllers = sorted,
                scannedPorts = candidateList.size,
                scannedAddresses = candidateList,
                diagnostics = diagnostics
            )
        } finally {
            pool.shutdownNow()
        }
    }

    private fun inspect(address: String): InspectOutcome {
        // 扫描未知回环端口绝不携带凭证，并同时验证 /version 和 /connections。
        // Never send secrets during discovery; verify both /version and /connections.
        val verResult = probe("$address/version")
        val ver = verResult.getOrElse { err ->
            return InspectOutcome.Failed(classifyFailure(address, err))
        }
        val versionResult = parseVersionResponse(ver.code, ver.body)
        if (!versionResult.isMihomo) {
            return InspectOutcome.Failed(
                ProbeDiagnostic(address, "HTTP ${ver.code} 非 Mihomo /version 响应", ProbeFailureKind.NON_MIHOMO)
            )
        }
        if (versionResult.authRequired) {
            return InspectOutcome.Found(DiscoveredController(address, null, ready = false, requiresAuth = true))
        }
        val connResultProbe = probe("$address/connections")
        val conn = connResultProbe.getOrElse { err ->
            return InspectOutcome.Failed(classifyFailure(address, err))
        }
        val connResult = parseConnectionsResponse(conn.code, conn.body, conn.truncated)
        if (!connResult.isMihomo) {
            return InspectOutcome.Failed(
                ProbeDiagnostic(address, "HTTP ${conn.code} 非 Mihomo /connections 响应", ProbeFailureKind.NON_MIHOMO)
            )
        }
        return InspectOutcome.Found(
            DiscoveredController(
                address = address,
                version = versionResult.version,
                ready = connResult.ready,
                requiresAuth = connResult.authRequired
            )
        )
    }

    private fun classifyFailure(address: String, err: Throwable): ProbeDiagnostic = when (err) {
        is java.net.ConnectException -> ProbeDiagnostic(address, "连接被拒绝", ProbeFailureKind.REFUSED)
        is java.net.SocketTimeoutException -> ProbeDiagnostic(address, "连接或读取超时", ProbeFailureKind.TIMEOUT)
        else -> ProbeDiagnostic(address, err.message ?: err.javaClass.simpleName, ProbeFailureKind.ERROR)
    }

    private fun probe(url: String, maxChars: Int = 262_144): Result<ProbeResponse> = runCatching {
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 900
            connection.readTimeout = 1500
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            val code = connection.responseCode
            var truncated = false
            val body = if (code == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(2048)
                    while (sb.length < maxChars) {
                        val remaining = maxChars - sb.length
                        val n = reader.read(buf, 0, minOf(buf.size, remaining))
                        if (n < 0) break
                        sb.append(buf, 0, n)
                    }
                    if (sb.length >= maxChars && reader.read() >= 0) {
                        truncated = true
                    }
                    sb.toString()
                }
            } else ""
            ProbeResponse(code, body, truncated)
        } finally {
            connection.disconnect()
        }
    }
}
