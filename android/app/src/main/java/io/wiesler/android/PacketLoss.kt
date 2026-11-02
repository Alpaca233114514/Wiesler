package io.wiesler.android

import java.util.Locale
import java.util.concurrent.TimeUnit

data class PingSample(val outcome: String, val rttMs: Double? = null, val message: String)

fun validPingTarget(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255 } &&
        parts.first().toInt() < 224 && value != "0.0.0.0"
}

fun parsePing(exit: Int, output: String): PingSample {
    val summary = Regex("(?m)^([0-9]+) packets transmitted, ([0-9]+) (?:packets )?received(?:,|$)").find(output)
    // 本地错误与 ICMP 错误不伪装成超时丢包。 / Exclude execution and routing errors from timeout loss.
    if (exit !in 0..1 || summary == null || output.contains("unreachable", true) ||
        output.contains("exceeded", true) || output.contains("error", true) || output.contains("sendmsg", true)) {
        return PingSample("error", message = "ICMP 不可用或网络错误（不计入丢包率）")
    }
    val sent = summary.groupValues[1].toIntOrNull()
    val received = summary.groupValues[2].toIntOrNull()
    if (sent != 1 || received !in 0..1) return PingSample("error", message = "无法确认 Ping 收发数量")
    if (received == 0) return PingSample("lost", message = "2 秒内未收到回包")
    val rtt = Regex("time[=<]([0-9]+(?:\\.[0-9]+)?)\\s*ms").find(output)?.groupValues?.get(1)?.toDoubleOrNull()
    return PingSample("reply", rtt, "收到回包")
}

class PacketLossWindow {
    private val samples = ArrayDeque<PingSample>()
    fun add(sample: PingSample) { samples.addLast(sample); if (samples.size > 60) samples.removeFirst() }
    fun clear() = samples.clear()
    val sent get() = samples.count { it.outcome == "reply" || it.outcome == "lost" }
    val received get() = samples.count { it.outcome == "reply" }
    val errors get() = samples.size - sent
    val lossPercent get() = if (sent == 0) null else 100.0 * (sent - received) / sent
    val averageRtt get() = samples.filter { it.outcome == "reply" }.mapNotNull { it.rttMs?.takeIf { n -> n.isFinite() && n >= 0 } }
        .takeIf { it.isNotEmpty() }?.average()
    fun text(): String {
        val loss = lossPercent?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "—"
        val rtt = averageRtt?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "—"
        return "$loss · 平均延迟 $rtt\n发送 $sent · 收到 $received · 未回包 ${sent - received} · 探测错误 $errors"
    }
}

class IcmpProbe {
    private var process: Process? = null
    private var cancelled = false
    @Synchronized fun cancel() { cancelled = true; process?.destroyForcibly() }
    fun run(target: String): PingSample {
        if (!validPingTarget(target)) return PingSample("error", message = "请输入单播 IPv4 地址")
        var child: Process? = null
        return try {
            child = synchronized(this) {
                check(!cancelled)
                ProcessBuilder("/system/bin/ping", "-n", "-c", "1", "-W", "2", "-w", "3", target)
                    .apply { redirectErrorStream(true); environment()["LC_ALL"] = "C" }
                    .start().also { process = it }
            }
            if (!child.waitFor(4, TimeUnit.SECONDS)) PingSample("error", message = "Ping 进程超时（不计入丢包率）")
            else parsePing(child.exitValue(), child.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            PingSample("error", message = "系统 Ping 无法执行（不计入丢包率）")
        } finally {
            child?.destroyForcibly()
            synchronized(this) { process = null }
        }
    }
}
