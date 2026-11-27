package io.wiesler.android

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class PingSample(val outcome: String, val rttMs: Double? = null, val message: String)

private val IPV4_OCTET_REGEX = Regex("0|[1-9][0-9]{0,2}")
private val PING_SUMMARY_REGEX = Regex("(?m)^([0-9]+) packets transmitted, ([0-9]+) (?:packets )?received(?:,|$)")
private val PING_RTT_REGEX = Regex("time[=<]([0-9]+(?:\\.[0-9]+)?)\\s*ms")

fun validPingTarget(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { IPV4_OCTET_REGEX.matches(it) && it.toInt() <= 255 } &&
        parts.first().toInt() < 224 && value != "0.0.0.0"
}

fun parsePing(exit: Int, output: String): PingSample {
    val summary = PING_SUMMARY_REGEX.find(output)
    // 本地错误与 ICMP 错误不伪装成超时丢包。 / Exclude execution and routing errors from timeout loss.
    if (exit !in 0..1 || summary == null || output.contains("unreachable", true) ||
        output.contains("exceeded", true) || output.contains("error", true) || output.contains("sendmsg", true)) {
        return PingSample("error", message = "ICMP 不可用或网络错误（不计入丢包率）")
    }
    val sent = summary.groupValues[1].toIntOrNull()
    val received = summary.groupValues[2].toIntOrNull()
    if (sent != 1 || received !in 0..1) return PingSample("error", message = "无法确认 Ping 收发数量")
    if (received == 0) return PingSample("lost", message = "2 秒内未收到回包")
    val rtt = PING_RTT_REGEX.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
    return PingSample("reply", rtt, "收到回包")
}

/**
 * 构造 RFC 792 IPv4 ICMP Echo Request 报文并计算 RFC 1071 校验和。
 * Build an RFC 792 IPv4 ICMP Echo Request packet with RFC 1071 one's-complement checksum.
 */
internal fun buildIcmpEchoRequest(sequence: Int, payloadSize: Int = 16): ByteArray {
    val packet = ByteArray(8 + payloadSize)
    packet[0] = 8 // ICMP_ECHO
    packet[1] = 0 // code
    // identifier [4..5] left 0; Linux SOCK_DGRAM IPPROTO_ICMP assigns the socket port automatically.
    packet[6] = ((sequence ushr 8) and 0xFF).toByte()
    packet[7] = (sequence and 0xFF).toByte()
    for (i in 0 until payloadSize) {
        packet[8 + i] = ((i + 0x57) and 0xFF).toByte()
    }
    val checksum = icmpChecksum(packet)
    packet[2] = ((checksum ushr 8) and 0xFF).toByte()
    packet[3] = (checksum and 0xFF).toByte()
    return packet
}

internal fun icmpChecksum(data: ByteArray, length: Int = data.size): Int {
    var sum = 0
    var i = 0
    while (i + 1 < length) {
        val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
        sum += word
        i += 2
    }
    if (i < length) {
        sum += (data[i].toInt() and 0xFF) shl 8
    }
    while ((sum ushr 16) != 0) {
        sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    return sum.inv() and 0xFFFF
}

internal fun isMatchingIcmpEchoReply(buffer: ByteArray, length: Int, expectedSequence: Int): Boolean {
    if (length < 8) return false
    val type = buffer[0].toInt() and 0xFF
    val code = buffer[1].toInt() and 0xFF
    if (type != 0 || code != 0) return false
    val seq = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
    return seq == (expectedSequence and 0xFFFF)
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

/**
 * 轻量 ICMP 探针：优先使用 Android 进程内无特权 ICMP Datagram Socket（零 fork/exec 开销），
 * 仅在内核或策略不支持时回退到 `/system/bin/ping` 子进程。
 *
 * Lightweight ICMP probe preferring in-process unprivileged SOCK_DGRAM/IPPROTO_ICMP sockets
 * (zero fork/exec overhead), falling back to /system/bin/ping only when blocked by the kernel.
 */
class IcmpProbe {
    companion object {
        private const val TIMEOUT_MS = 2_000L
        private const val POLL_SLICE_MS = 200
        private val nextSequence = AtomicInteger(1)
    }

    private var process: Process? = null
    @Volatile private var cancelled = false
    private var reusableFd: FileDescriptor? = null
    private var socketUnavailable = false
    private val recvBuffer = ByteArray(256)

    @Synchronized
    fun cancel() {
        cancelled = true
        process?.destroyForcibly()
        closeSocketLocked()
    }

    @Synchronized
    fun close() {
        closeSocketLocked()
    }

    private fun closeSocketLocked() {
        val fd = reusableFd ?: return
        reusableFd = null
        runCatching { Os.close(fd) }
    }

    fun run(target: String): PingSample {
        if (!validPingTarget(target)) return PingSample("error", message = "请输入单播 IPv4 地址")
        if (cancelled) return PingSample("error", message = "探测已取消")
        if (!socketUnavailable) {
            val socketSample = runViaNativeIcmpSocket(target)
            if (socketSample != null) return socketSample
        }
        return runViaSubprocess(target)
    }

    private fun runViaNativeIcmpSocket(target: String): PingSample? {
        val fd = synchronized(this) {
            if (cancelled) return PingSample("error", message = "探测已取消")
            reusableFd ?: try {
                val created = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
                reusableFd = created
                created
            } catch (err: ErrnoException) {
                if (err.errno == OsConstants.EACCES ||
                    err.errno == OsConstants.EPERM ||
                    err.errno == OsConstants.EPROTONOSUPPORT ||
                    err.errno == OsConstants.EAFNOSUPPORT
                ) {
                    socketUnavailable = true
                }
                return null
            } catch (_: Throwable) {
                socketUnavailable = true
                return null
            }
        }

        val addr = runCatching { InetAddress.getByName(target) }.getOrNull()
            ?: return PingSample("error", message = "无效 IPv4 目标")
        val seq = nextSequence.getAndIncrement() and 0xFFFF
        val packet = buildIcmpEchoRequest(seq)
        val pollfd = StructPollfd().apply {
            this.fd = fd
            this.events = OsConstants.POLLIN.toShort()
        }
        val pollArray = arrayOf(pollfd)
        val srcAddress = InetSocketAddress(0)

        return try {
            val sentNs = System.nanoTime()
            val written = Os.sendto(fd, packet, 0, packet.size, 0, addr, 0)
            if (written != packet.size) {
                return PingSample("error", message = "ICMP 发送不完整（不计入丢包率）")
            }
            val deadlineNs = sentNs + TIMEOUT_MS * 1_000_000L
            while (!cancelled) {
                val remainMs = ((deadlineNs - System.nanoTime()) / 1_000_000L).toInt()
                if (remainMs <= 0) {
                    return PingSample("lost", message = "2 秒内未收到回包")
                }
                pollfd.revents = 0
                val ready = Os.poll(pollArray, minOf(remainMs, POLL_SLICE_MS))
                if (ready < 0) {
                    return PingSample("error", message = "ICMP 轮询异常（不计入丢包率）")
                }
                if (ready == 0) continue
                val revents = pollfd.revents.toInt()
                if ((revents and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)) != 0) {
                    synchronized(this) { closeSocketLocked() }
                    return PingSample("error", message = "ICMP 不可用或网络错误（不计入丢包率）")
                }
                if ((revents and OsConstants.POLLIN) != 0) {
                    val readLen = Os.recvfrom(fd, recvBuffer, 0, recvBuffer.size, 0, srcAddress)
                    val rttMs = (System.nanoTime() - sentNs) / 1_000_000.0
                    if (isMatchingIcmpEchoReply(recvBuffer, readLen, seq)) {
                        return PingSample("reply", rttMs, "收到回包")
                    }
                }
            }
            PingSample("error", message = "探测已取消")
        } catch (err: ErrnoException) {
            if (err.errno == OsConstants.EAGAIN) {
                PingSample("lost", message = "2 秒内未收到回包")
            } else {
                synchronized(this) { closeSocketLocked() }
                PingSample("error", message = "ICMP 不可用或网络错误（不计入丢包率）")
            }
        } catch (_: Throwable) {
            synchronized(this) { closeSocketLocked() }
            null
        }
    }

    private fun runViaSubprocess(target: String): PingSample {
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
