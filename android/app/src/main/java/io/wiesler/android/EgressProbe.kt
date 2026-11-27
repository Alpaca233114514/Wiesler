package io.wiesler.android

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

data class EgressLocation(
    val country: String? = null,
    val countryCode: String? = null,
    val region: String? = null,
    val city: String? = null,
    val isp: String? = null,
    val timezone: String? = null
) {
    fun summary(): String = listOfNotNull(country, city, isp).joinToString(" · ")
        .ifBlank { "地理位置未知" }
}

data class EgressAddress(
    val ip: String,
    val observedAt: Long,
    val provider: String,
    val proxy: String?,
    val location: EgressLocation? = null,
    val locationError: String? = null
)

object EgressProbe {
    private const val GEO_PROVIDER = "https://ipwho.is"
    private val IP_PROVIDERS = listOf("https://api64.ipify.org", "https://api.ipify.org")
    private const val MAX_BODY_BYTES = 8192

    private val IPV4_REGEX = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

    fun parseIpAddress(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 64 || text.any { it.isWhitespace() || it == '<' || it == '>' }) {
            return null
        }
        if (IPV4_REGEX.matches(text)) return text
        if (!text.contains(':')) return null
        // 仅允许规范 IPv6 字面量字符，避免触发 DNS 解析。 / Restrict to literal IPv6 characters to avoid DNS lookup.
        if (!text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return null
        }
        return runCatching { InetAddress.getByName(text).hostAddress }.getOrNull()
    }

    fun parseLocation(body: String, expectedIp: String): Result<EgressLocation> {
        val expected = parseIpAddress(expectedIp) ?: return Result.failure(IllegalArgumentException("出口 IP 无效"))
        return runCatching {
            val json = JSONObject(body)
            val returnedIp = parseIpAddress(json.optString("ip"))
            // 归属地必须与实测 IP 精确匹配，防止跨路由串台。 / Require geo payload IP to match measured egress IP.
            if (!json.optBoolean("success", false) || returnedIp != expected) {
                error("地理信息与出口 IP 不匹配")
            }
            fun clean(obj: JSONObject?, key: String): String? =
                obj?.optString(key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            val connection = json.optJSONObject("connection")
            val timezone = json.optJSONObject("timezone")
            EgressLocation(
                country = clean(json, "country"),
                countryCode = clean(json, "country_code"),
                region = clean(json, "region"),
                city = clean(json, "city"),
                isp = clean(connection, "isp"),
                timezone = clean(timezone, "id")
            )
        }.recoverCatching { err ->
            if (err is IllegalStateException) throw err
            error("地理信息不可用")
        }
    }

    fun parseMihomoHttpProxyPort(configsJson: String): Int? = runCatching {
        val json = JSONObject(configsJson)
        val mixed = json.optInt("mixed-port", 0)
        if (mixed in 1..65535) return@runCatching mixed
        val http = json.optInt("port", 0)
        if (http in 1..65535) return@runCatching http
        null
    }.getOrNull()

    fun fetchMihomoProxyPort(controllerAddress: String, secret: String): Result<Int> = runCatching {
        val connection = URL("$controllerAddress/configs").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (secret.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $secret")
            val code = connection.responseCode
            if (code == 401 || code == 403) error("控制器鉴权失败，无法读取代理端口")
            if (code != 200) error("读取代理配置失败（HTTP $code）")
            val payload = readBounded(connection, 262_144)
            parseMihomoHttpProxyPort(payload) ?: error("控制器未启用 HTTP / Mixed 代理端口")
        } finally {
            connection.disconnect()
        }
    }

    fun measure(proxyPort: Int? = null): Result<EgressAddress> {
        if (proxyPort != null && proxyPort !in 1..65535) {
            return Result.failure(IllegalArgumentException("出口探测代理端口无效"))
        }
        val proxy = if (proxyPort != null) {
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
        } else {
            Proxy.NO_PROXY
        }
        val proxyLabel = proxyPort?.let { "http://127.0.0.1:$it" }
        var lastError: Throwable? = null
        for (provider in IP_PROVIDERS) {
            val attempt = runCatching {
                val rawIp = fetchHttps(provider, proxy)
                val ip = parseIpAddress(rawIp) ?: error("出口服务未返回有效 IP 地址")
                val observedAt = System.currentTimeMillis()
                val geo = runCatching { fetchHttps("$GEO_PROVIDER/$ip", proxy) }
                    .fold(
                        onSuccess = { parseLocation(it, ip) },
                        onFailure = { Result.failure(it) }
                    )
                EgressAddress(
                    ip = ip,
                    observedAt = observedAt,
                    provider = provider,
                    proxy = proxyLabel,
                    location = geo.getOrNull(),
                    locationError = geo.exceptionOrNull()?.message
                )
            }
            if (attempt.isSuccess) return attempt
            lastError = attempt.exceptionOrNull()
        }
        return Result.failure(lastError ?: IllegalStateException("出口探测失败"))
    }

    private fun fetchHttps(url: String, proxy: Proxy): String {
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            val code = connection.responseCode
            if (code != 200) error("出口服务或代理返回 HTTP $code")
            return readBounded(connection, MAX_BODY_BYTES)
        } catch (err: java.net.SocketTimeoutException) {
            throw IllegalStateException("出口探测超时", err)
        } catch (err: javax.net.ssl.SSLException) {
            throw IllegalStateException("出口探测 TLS 验证失败", err)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(connection: HttpURLConnection, limit: Int): String {
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(2048)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > limit) error("响应体过大")
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
