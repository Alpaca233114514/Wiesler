package io.wiesler.android

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class NetworkQualityGrade(val badge: String, val label: String) {
    EXCELLENT("S", "极佳"),
    GOOD("A", "良好"),
    FAIR("B", "一般"),
    POOR("C", "较差"),
    CRITICAL("D", "严重异常"),
    OFFLINE("—", "已离线")
}

enum class QualityDimensionId(val label: String, val weight: Double) {
    ACCESS_SIGNAL("接入与信号", 0.30),
    LATENCY_STABILITY("延迟与稳定", 0.35),
    REACHABILITY_DNS("解析与连通", 0.20),
    PROXY_HEALTH("代理与链路", 0.15)
}

enum class ScenarioStatus(val label: String) {
    OPTIMAL("流畅"),
    USABLE("可用"),
    DEGRADED("受限"),
    UNKNOWN("待测")
}

data class SystemLinkSnapshot(
    val connected: Boolean = false,
    val transportLabel: String = "读取中",
    val isWifi: Boolean = false,
    val isCellular: Boolean = false,
    val isEthernet: Boolean = false,
    val isVpn: Boolean = false,
    val validated: Boolean = false,
    val notMetered: Boolean = false,
    val wifiRssiDbm: Int? = null,
    val downstreamKbps: Int? = null,
    val upstreamKbps: Int? = null,
    val interfaceName: String? = null,
    val mtu: Int? = null,
    val privateDnsActive: Boolean = false,
    val privateDnsServer: String? = null,
    val dnsServers: List<String> = emptyList()
)

data class HttpsProbeSnapshot(
    val observedAtMs: Long? = null,
    val statusCode: Int? = null,
    val latencyMs: Long? = null,
    val ok: Boolean = false,
    val error: String? = null
)

data class NetworkQualityInput(
    val nowMs: Long,
    val link: SystemLinkSnapshot = SystemLinkSnapshot(),
    val pingTarget: String = "1.1.1.1",
    val pingRunning: Boolean = false,
    val recentPings: List<TimedPingEvidence> = emptyList(),
    val lossPercent: Double? = null,
    val averageRttMs: Double? = null,
    val pingSent: Int = 0,
    val pingReceived: Int = 0,
    val pingErrors: Int = 0,
    val dnsInspection: ActiveDnsInspection? = null,
    val httpsProbe: HttpsProbeSnapshot? = null,
    val systemEgress: EgressAddress? = null,
    val systemEgressError: String? = null,
    val proxyEgress: EgressAddress? = null,
    val proxyEgressError: String? = null,
    val rfDiagnostics: RfDiagnosticsSnapshot = RfDiagnosticsSnapshot(),
    val controllerEnabled: Boolean = false,
    val controllerHealthy: Boolean = false,
    val activeFlowCount: Int? = null,
    val directFlowCount: Int = 0,
    val proxyFlowCount: Int = 0,
    val globalRate: Rate? = null,
    val recentRequestLogs: List<RequestLog> = emptyList()
)

data class QualityDimensionScore(
    val id: QualityDimensionId,
    val score: Int?,
    val available: Boolean,
    val statusText: String,
    val primaryMetric: String,
    val detail: String
)

data class ScenarioAssessment(
    val id: String,
    val title: String,
    val status: ScenarioStatus,
    val detail: String
)

data class NetworkQualityHistoryPoint(
    val observedAtMs: Long,
    val score: Int,
    val rttMs: Double? = null,
    val lossPercent: Double? = null
)

data class NetworkQualitySnapshot(
    val evaluatedAtMs: Long = 0L,
    val overallScore: Int? = null,
    val grade: NetworkQualityGrade = NetworkQualityGrade.OFFLINE,
    val headline: String = "等待采集网络质量",
    val summary: String = "点击一键体检或开启持续监测，复用本地多维探针计算环境评分。",
    val advice: String = "建议先执行一次综合体检获取延迟、DNS 与出口基线。",
    val measuredDimensions: Int = 0,
    val totalApplicableDimensions: Int = 3,
    val jitterMs: Double? = null,
    val dimensions: List<QualityDimensionScore> = emptyList(),
    val scenarios: List<ScenarioAssessment> = emptyList(),
    val history: List<NetworkQualityHistoryPoint> = emptyList()
)

/**
 * 纯 Kotlin 网络质量综合评分引擎：
 * 复用系统 ConnectivityManager 链路能力、ICMP 丢包/RTT/抖动、DNS/HTTPS/公网出口探测、
 * 蜂窝双卡射频污染评分与 Mihomo 控制器健康度，按可用维度动态归一化计分并应用短板封顶保护。
 *
 * Pure Kotlin composite network quality scorer reusing existing Wiesler telemetry interfaces
 * with dynamic weight normalization across measured dimensions and hard bottleneck caps.
 */
object NetworkQualityEngine {

    fun computeJitterMs(pings: List<TimedPingEvidence>): Double? {
        val rtts = pings
            .filter { it.outcome == "reply" }
            .mapNotNull { it.rttMs?.takeIf { v -> v.isFinite() && v >= 0.0 } }
        if (rtts.size < 2) return null
        var diffSum = 0.0
        for (i in 1 until rtts.size) {
            diffSum += abs(rtts[i] - rtts[i - 1])
        }
        return diffSum / (rtts.size - 1)
    }

    fun evaluate(
        input: NetworkQualityInput,
        history: List<NetworkQualityHistoryPoint> = emptyList()
    ): NetworkQualitySnapshot {
        val jitterMs = computeJitterMs(input.recentPings)
        val accessDim = scoreAccessAndSignal(input)
        val latencyDim = scoreLatencyAndStability(input, jitterMs)
        val reachDim = scoreReachabilityAndDns(input)
        val proxyDim = scoreProxyHealth(input)

        val dimensions = listOf(accessDim, latencyDim, reachDim, proxyDim)
        val proxyApplicable = input.controllerEnabled || input.proxyEgress != null || input.proxyEgressError != null
        val totalApplicable = if (proxyApplicable) 4 else 3
        val measured = dimensions.filter { it.available && it.score != null }

        if (!input.link.connected) {
            return NetworkQualitySnapshot(
                evaluatedAtMs = input.nowMs,
                overallScore = 0,
                grade = NetworkQualityGrade.OFFLINE,
                headline = "设备当前离线",
                summary = "系统未检测到活跃 Wi-Fi、蜂窝或以太网连接。",
                advice = "请检查飞行模式、Wi-Fi 或移动数据开关。",
                measuredDimensions = measured.size,
                totalApplicableDimensions = totalApplicable,
                jitterMs = jitterMs,
                dimensions = dimensions,
                scenarios = buildScenarios(input, 0, jitterMs),
                history = history
            )
        }

        // 按实际已测维度权重归一化，避免用假分数冒充未探测项。
        // Normalize weights strictly over measured dimensions so unmeasured probes never fabricate scores.
        val weightSum = measured.sumOf { it.id.weight }.coerceAtLeast(1e-6)
        var weightedScore = measured.sumOf { (it.score ?: 0) * it.id.weight } / weightSum

        // 关键瓶颈硬封顶：高丢包、严重双卡射频污染或系统未通过联网校验时防止平均分虚高。
        // Hard bottleneck caps prevent misleading high averages when a critical layer is broken.
        val lossPct = input.lossPercent
        val rfSeverity = input.rfDiagnostics.assessment.severity
        if (lossPct != null && lossPct >= 30.0) {
            weightedScore = minOf(weightedScore, 39.0)
        } else if (lossPct != null && lossPct >= 15.0) {
            weightedScore = minOf(weightedScore, 54.0)
        }
        if (rfSeverity == RfSeverity.SEVERE_POLLUTION) {
            weightedScore = minOf(weightedScore, 42.0)
        }
        if (!input.link.validated) {
            weightedScore = minOf(weightedScore, 45.0)
        }

        val overall = weightedScore.roundToInt().coerceIn(1, 100)
        val grade = gradeFor(overall, connected = true)
        val (headline, summary, advice) = buildDiagnosisText(
            input = input,
            overall = overall,
            grade = grade,
            jitterMs = jitterMs,
            dimensions = dimensions,
            measuredCount = measured.size,
            totalApplicable = totalApplicable
        )

        return NetworkQualitySnapshot(
            evaluatedAtMs = input.nowMs,
            overallScore = overall,
            grade = grade,
            headline = headline,
            summary = summary,
            advice = advice,
            measuredDimensions = measured.size,
            totalApplicableDimensions = totalApplicable,
            jitterMs = jitterMs,
            dimensions = dimensions,
            scenarios = buildScenarios(input, overall, jitterMs),
            history = history
        )
    }

    private fun scoreAccessAndSignal(input: NetworkQualityInput): QualityDimensionScore {
        val link = input.link
        if (!link.connected) {
            return QualityDimensionScore(
                id = QualityDimensionId.ACCESS_SIGNAL,
                score = 0,
                available = true,
                statusText = "未连接",
                primaryMetric = "无活跃接口",
                detail = "系统未上报可用默认网络。"
            )
        }

        val bwText = link.downstreamKbps?.takeIf { it > 0 }?.let { formatBandwidth(it) }
        val validatedNote = if (link.validated) "联网校验通过" else "联网校验未通过"

        if (link.isWifi) {
            val rssi = link.wifiRssiDbm?.takeIf { it in -120..-20 }
            var score = if (rssi != null) scoreWifiRssi(rssi) else if (link.validated) 82.0 else 36.0
            link.downstreamKbps?.let { kbps ->
                when {
                    kbps >= 150_000 -> score += 3.0
                    kbps < 2_000 -> score -= 24.0
                    kbps < 10_000 -> score -= 12.0
                }
            }
            if (!link.validated) score = minOf(score, 40.0)
            val finalScore = score.roundToInt().coerceIn(5, 100)
            val metric = buildList {
                if (rssi != null) add("$rssi dBm")
                if (bwText != null) add("↓$bwText")
            }.joinToString(" · ").ifBlank { "Wi-Fi" }
            val detail = buildString {
                append(if (link.isVpn) "Wi-Fi + VPN" else "Wi-Fi")
                append(" · ")
                append(validatedNote)
                link.mtu?.let { append(" · MTU $it") }
            }
            return QualityDimensionScore(
                id = QualityDimensionId.ACCESS_SIGNAL,
                score = finalScore,
                available = true,
                statusText = if (rssi != null) wifiRssiGradeLabel(rssi) else validatedNote,
                primaryMetric = metric,
                detail = detail
            )
        }

        if (link.isCellular) {
            val rfAssess = input.rfDiagnostics.assessment
            val slots = rfAssess.slots
            if (slots.isNotEmpty()) {
                val rfClean = (100 - rfAssess.score).coerceIn(0, 100).toDouble()
                val bestSlot = slots.maxByOrNull { it.freshFrames } ?: slots.first()
                val radioLevel = scoreCellularSlot(bestSlot)
                var score = rfClean * 0.65 + radioLevel * 0.35
                if (rfAssess.severity == RfSeverity.SEVERE_POLLUTION) score = minOf(score, 22.0)
                if (!link.validated) score = minOf(score, 40.0)
                val finalScore = score.roundToInt().coerceIn(0, 100)
                val shortBand = bestSlot.bandLabel.substringBefore(" (")
                val rsrpText = bestSlot.rsrpDbm?.let { "$it dBm" } ?: ""
                val sinrText = bestSlot.sinrDb?.let { "SINR ${if (it > 0) "+" else ""}$it" } ?: ""
                val metric = listOf(shortBand, rsrpText).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { bestSlot.bandLabel }
                return QualityDimensionScore(
                    id = QualityDimensionId.ACCESS_SIGNAL,
                    score = finalScore,
                    available = true,
                    statusText = rfAssess.severity.label,
                    primaryMetric = metric,
                    detail = buildString {
                        append("净度 ${100 - rfAssess.score}/100")
                        if (sinrText.isNotBlank()) append(" · $sinrText dB")
                        append(" · $validatedNote")
                    }
                )
            }
            var score = if (link.validated) 76.0 else 35.0
            link.downstreamKbps?.let { kbps ->
                when {
                    kbps >= 80_000 -> score += 8.0
                    kbps >= 25_000 -> score += 4.0
                    kbps < 2_000 -> score -= 22.0
                    kbps < 8_000 -> score -= 10.0
                }
            }
            val finalScore = score.roundToInt().coerceIn(5, 95)
            return QualityDimensionScore(
                id = QualityDimensionId.ACCESS_SIGNAL,
                score = finalScore,
                available = true,
                statusText = if (link.validated) "移动网络已连" else "校验未通过",
                primaryMetric = bwText?.let { "移动网 · ↓$it" } ?: "移动网络",
                detail = "$validatedNote · 开启射频监测可采集 RSRP / SINR"
            )
        }

        val score = if (link.validated) 90 else 40
        return QualityDimensionScore(
            id = QualityDimensionId.ACCESS_SIGNAL,
            score = score,
            available = true,
            statusText = validatedNote,
            primaryMetric = bwText?.let { "${link.transportLabel} · ↓$it" } ?: link.transportLabel,
            detail = "${link.interfaceName ?: "系统接口"} · $validatedNote"
        )
    }

    private fun scoreLatencyAndStability(
        input: NetworkQualityInput,
        jitterMs: Double?
    ): QualityDimensionScore {
        val sent = input.pingSent
        val lossPct = input.lossPercent
        val avgRtt = input.averageRttMs
        if (sent <= 0 || lossPct == null) {
            return QualityDimensionScore(
                id = QualityDimensionId.LATENCY_STABILITY,
                score = null,
                available = false,
                statusText = if (input.pingRunning) "探测中" else "待体检",
                primaryMetric = "—",
                detail = "目标 ${input.pingTarget} · 点击一键体检或开启持续监测采样延迟与丢包"
            )
        }

        if (lossPct >= 99.9 || avgRtt == null) {
            return QualityDimensionScore(
                id = QualityDimensionId.LATENCY_STABILITY,
                score = 0,
                available = true,
                statusText = "全部丢包",
                primaryMetric = String.format(Locale.ROOT, "丢包 %.0f%%", lossPct),
                detail = "目标 ${input.pingTarget} · 发送 $sent 收到 ${input.pingReceived}，ICMP 无回包"
            )
        }

        val rttScore = when {
            avgRtt <= 25.0 -> 100.0
            avgRtt <= 60.0 -> 100.0 - (avgRtt - 25.0) * (12.0 / 35.0)
            avgRtt <= 120.0 -> 88.0 - (avgRtt - 60.0) * (18.0 / 60.0)
            avgRtt <= 220.0 -> 70.0 - (avgRtt - 120.0) * (25.0 / 100.0)
            avgRtt <= 400.0 -> 45.0 - (avgRtt - 220.0) * (25.0 / 180.0)
            else -> (20.0 - (avgRtt - 400.0) * 0.04).coerceAtLeast(5.0)
        }

        val lossPenalty = when {
            lossPct <= 0.0 -> 0.0
            lossPct <= 2.0 -> lossPct * 8.0
            lossPct <= 10.0 -> 16.0 + (lossPct - 2.0) * 4.5
            else -> 52.0 + (lossPct - 10.0) * 1.6
        }

        val jitterPenalty = when {
            jitterMs == null || jitterMs <= 5.0 -> 0.0
            jitterMs <= 25.0 -> (jitterMs - 5.0) * 0.6
            jitterMs <= 80.0 -> 12.0 + (jitterMs - 25.0) * 0.35
            else -> (31.25 + (jitterMs - 80.0) * 0.1).coerceAtMost(36.0)
        }

        val score = (rttScore - lossPenalty - jitterPenalty).roundToInt().coerceIn(0, 100)
        val jitterText = jitterMs?.let { String.format(Locale.ROOT, "抖动 %.1fms", it) } ?: "抖动待补齐"
        val metric = String.format(Locale.ROOT, "%.0f ms · 丢包 %.1f%%", avgRtt, lossPct)
        val status = when {
            lossPct >= 10.0 -> "高丢包"
            avgRtt >= 220.0 -> "高延迟"
            (jitterMs ?: 0.0) >= 35.0 -> "抖动偏大"
            score >= 85 -> "低延迟平稳"
            else -> "轻度波动"
        }
        return QualityDimensionScore(
            id = QualityDimensionId.LATENCY_STABILITY,
            score = score,
            available = true,
            statusText = status,
            primaryMetric = metric,
            detail = "${input.pingTarget} · $jitterText · 收发 ${input.pingReceived}/$sent"
        )
    }

    private fun scoreReachabilityAndDns(input: NetworkQualityInput): QualityDimensionScore {
        val subScores = ArrayList<Double>(3)
        val parts = ArrayList<String>(3)

        val dns = input.dnsInspection
        if (dns != null) {
            val dnsScore = if (dns.probeError != null || dns.resolvedAddresses.isEmpty()) {
                parts.add("DNS 失败")
                15.0
            } else {
                val ms = (dns.probeElapsedMs ?: 0L).toDouble()
                parts.add("DNS ${ms.roundToInt()}ms")
                val base = when {
                    ms <= 30.0 -> 98.0
                    ms <= 80.0 -> 98.0 - (ms - 30.0) * (12.0 / 50.0)
                    ms <= 200.0 -> 86.0 - (ms - 80.0) * (21.0 / 120.0)
                    ms <= 500.0 -> 65.0 - (ms - 200.0) * (27.0 / 300.0)
                    else -> 22.0
                }
                (base + if (dns.privateDnsActive) 2.0 else 0.0).coerceAtMost(100.0)
            }
            subScores.add(dnsScore)
        }

        val https = input.httpsProbe
        if (https != null && (https.statusCode != null || https.error != null)) {
            val httpsScore = when {
                https.error != null -> {
                    parts.add("HTTPS ${https.error}")
                    18.0
                }
                https.ok -> {
                    val ms = (https.latencyMs ?: 0L).toDouble()
                    val latencyLabel = if (ms >= 1000.0) {
                        String.format(Locale.ROOT, "%.1fs", ms / 1000.0)
                    } else {
                        "${ms.roundToInt()}ms"
                    }
                    parts.add("204 $latencyLabel")
                    when {
                        ms <= 150.0 -> 98.0
                        ms <= 350.0 -> 98.0 - (ms - 150.0) * (14.0 / 200.0)
                        ms <= 800.0 -> 84.0 - (ms - 350.0) * (26.0 / 450.0)
                        ms <= 1600.0 -> 58.0 - (ms - 800.0) * (24.0 / 800.0)
                        else -> 24.0
                    }
                }
                else -> {
                    parts.add("HTTP ${https.statusCode}")
                    55.0
                }
            }
            subScores.add(httpsScore)
        }

        if (input.systemEgress != null) {
            subScores.add(if (input.systemEgress.locationError == null) 95.0 else 86.0)
            parts.add("出口 ${input.systemEgress.ip}")
        } else if (input.systemEgressError != null) {
            subScores.add(20.0)
            parts.add("出口不可达")
        }

        if (subScores.isEmpty()) {
            val privateDnsHint = if (input.link.privateDnsActive) "Private DNS 已启用" else "Private DNS 未启用"
            return QualityDimensionScore(
                id = QualityDimensionId.REACHABILITY_DNS,
                score = null,
                available = false,
                statusText = "待体检",
                primaryMetric = "—",
                detail = "$privateDnsHint · 点击一键体检测试 DNS 解析、HTTPS 与公网出口"
            )
        }

        val score = subScores.average().roundToInt().coerceIn(0, 100)
        val status = when {
            score >= 85 -> "解析通畅"
            score >= 60 -> "可用但偏慢"
            else -> "连通受阻"
        }
        val dnsDetail = if (input.link.privateDnsActive) {
            "DoT ${input.link.privateDnsServer ?: "已开启"}"
        } else {
            "系统 DNS ${input.link.dnsServers.firstOrNull() ?: "默认"}"
        }
        return QualityDimensionScore(
            id = QualityDimensionId.REACHABILITY_DNS,
            score = score,
            available = true,
            statusText = status,
            primaryMetric = parts.take(2).joinToString(" · "),
            detail = "$dnsDetail · ${parts.joinToString(" / ")}"
        )
    }

    private fun scoreProxyHealth(input: NetworkQualityInput): QualityDimensionScore {
        val hasProxySignal = input.controllerEnabled || input.proxyEgress != null || input.proxyEgressError != null
        if (!hasProxySignal) {
            return QualityDimensionScore(
                id = QualityDimensionId.PROXY_HEALTH,
                score = null,
                available = false,
                statusText = "未连接",
                primaryMetric = "直连模式",
                detail = "未连接 Mihomo 控制器，该项不计扣分；连接后自动纳入综合评分"
            )
        }

        var score = if (input.controllerHealthy) 88.0 else 28.0
        if (input.proxyEgress != null) {
            score += 10.0
        } else if (input.proxyEgressError != null) {
            score -= 32.0
        }

        val recentControllerLogs = input.recentRequestLogs
            .filter { it.source.contains("Mihomo", ignoreCase = true) }
            .take(20)
        if (recentControllerLogs.isNotEmpty()) {
            val failRate = recentControllerLogs.count { it.failed }.toDouble() / recentControllerLogs.size
            score -= failRate * 35.0
        }

        val finalScore = score.roundToInt().coerceIn(0, 100)
        val splitText = when {
            input.systemEgress != null && input.proxyEgress != null && input.systemEgress.ip == input.proxyEgress.ip -> "同出口"
            input.systemEgress != null && input.proxyEgress != null -> "已分流"
            input.proxyEgress != null -> "出口可达"
            input.proxyEgressError != null -> "代理出口异常"
            else -> "未测出口"
        }
        val flowText = input.activeFlowCount?.let { "$it 连接（代理 ${input.proxyFlowCount}）" } ?: "等待快照"
        return QualityDimensionScore(
            id = QualityDimensionId.PROXY_HEALTH,
            score = finalScore,
            available = true,
            statusText = if (input.controllerHealthy) splitText else "控制器异常",
            primaryMetric = if (input.controllerHealthy) flowText else "连接断开",
            detail = input.proxyEgress?.let { "代理出口 ${it.ip} · ${it.location?.summary() ?: "位置未知"}" }
                ?: input.proxyEgressError
                ?: if (input.controllerHealthy) "Mihomo 控制器心跳正常" else "无法读取本机 Mihomo 快照"
        )
    }

    private fun scoreWifiRssi(rssiDbm: Int): Double = when {
        rssiDbm >= -55 -> 98.0
        rssiDbm >= -65 -> 98.0 - (-55 - rssiDbm) * 1.0
        rssiDbm >= -75 -> 88.0 - (-65 - rssiDbm) * 1.6
        rssiDbm >= -85 -> 72.0 - (-75 - rssiDbm) * 2.4
        else -> (48.0 - (-85 - rssiDbm) * 1.8).coerceAtLeast(12.0)
    }

    private fun wifiRssiGradeLabel(rssiDbm: Int): String = when {
        rssiDbm >= -55 -> "信号满格"
        rssiDbm >= -67 -> "信号良好"
        rssiDbm >= -76 -> "信号一般"
        else -> "信号偏弱"
    }

    private fun scoreCellularSlot(slot: RfSlotSummary): Double {
        if (!slot.inService || slot.rat == RfRat.NO_SIGNAL) return 5.0
        val rsrpScore = slot.rsrpDbm?.let { rsrp ->
            when {
                rsrp >= -85 -> 98.0
                rsrp >= -100 -> 98.0 - (-85 - rsrp) * 1.1
                rsrp >= -115 -> 81.5 - (-100 - rsrp) * 2.3
                else -> (47.0 - (-115 - rsrp) * 1.5).coerceAtLeast(10.0)
            }
        }
        val sinrScore = slot.sinrDb?.let { sinr ->
            when {
                sinr >= 15 -> 98.0
                sinr >= 5 -> 78.0 + (sinr - 5) * 2.0
                sinr >= 0 -> 55.0 + sinr * 4.6
                else -> (55.0 + sinr * 3.5).coerceAtLeast(8.0)
            }
        }
        return when {
            rsrpScore != null && sinrScore != null -> rsrpScore * 0.45 + sinrScore * 0.55
            sinrScore != null -> sinrScore
            rsrpScore != null -> rsrpScore
            else -> 70.0
        }
    }

    private fun gradeFor(score: Int, connected: Boolean): NetworkQualityGrade = when {
        !connected -> NetworkQualityGrade.OFFLINE
        score >= 90 -> NetworkQualityGrade.EXCELLENT
        score >= 75 -> NetworkQualityGrade.GOOD
        score >= 55 -> NetworkQualityGrade.FAIR
        score >= 30 -> NetworkQualityGrade.POOR
        else -> NetworkQualityGrade.CRITICAL
    }

    private fun buildScenarios(
        input: NetworkQualityInput,
        overall: Int,
        jitterMs: Double?
    ): List<ScenarioAssessment> {
        if (!input.link.connected) {
            return listOf(
                ScenarioAssessment("voice", "语音 / 会议", ScenarioStatus.DEGRADED, "离线不可用"),
                ScenarioAssessment("game", "低延迟游戏", ScenarioStatus.DEGRADED, "离线不可用"),
                ScenarioAssessment("stream", "高清流媒体", ScenarioStatus.DEGRADED, "离线不可用"),
                ScenarioAssessment("proxy", "代理 / 跨境", ScenarioStatus.DEGRADED, "离线不可用")
            )
        }
        val rtt = input.averageRttMs
        val loss = input.lossPercent
        val validated = input.link.validated

        val voice = when {
            !validated -> ScenarioStatus.DEGRADED to "联网校验未通过"
            rtt == null || loss == null -> ScenarioStatus.UNKNOWN to "待测延迟与丢包"
            loss < 1.5 && rtt < 120.0 && (jitterMs ?: 0.0) < 30.0 -> ScenarioStatus.OPTIMAL to String.format(Locale.ROOT, "%.0fms 稳定", rtt)
            loss < 4.0 && rtt < 220.0 -> ScenarioStatus.USABLE to "偶发波动"
            else -> ScenarioStatus.DEGRADED to "易卡顿或断音"
        }

        val game = when {
            !validated -> ScenarioStatus.DEGRADED to "联网校验未通过"
            rtt == null || loss == null -> ScenarioStatus.UNKNOWN to "待测延迟与抖动"
            loss < 1.0 && rtt < 65.0 && (jitterMs ?: 0.0) < 18.0 -> ScenarioStatus.OPTIMAL to String.format(Locale.ROOT, "%.0fms 低抖动", rtt)
            loss < 2.5 && rtt < 120.0 -> ScenarioStatus.USABLE to "中度延迟"
            else -> ScenarioStatus.DEGRADED to "延迟或丢包偏高"
        }

        val bw = input.link.downstreamKbps
        val stream = when {
            !validated -> ScenarioStatus.DEGRADED to "联网校验未通过"
            (loss ?: 0.0) >= 8.0 || overall < 50 -> ScenarioStatus.DEGRADED to "易缓冲"
            bw != null && bw >= 25_000 && overall >= 75 -> ScenarioStatus.OPTIMAL to "带宽充裕"
            overall >= 65 -> ScenarioStatus.USABLE to "可流畅播放"
            else -> ScenarioStatus.DEGRADED to "带宽或链路受限"
        }

        val proxy = when {
            !input.controllerEnabled && input.httpsProbe == null -> ScenarioStatus.UNKNOWN to "未接入控制器"
            input.controllerEnabled && !input.controllerHealthy -> ScenarioStatus.DEGRADED to "控制器断开"
            input.proxyEgressError != null -> ScenarioStatus.DEGRADED to "代理出口失败"
            input.proxyEgress != null && (input.httpsProbe?.ok != false) -> ScenarioStatus.OPTIMAL to "代理链路正常"
            input.httpsProbe?.ok == true -> ScenarioStatus.OPTIMAL to "204 连通正常"
            input.httpsProbe?.error != null -> ScenarioStatus.DEGRADED to "204 超时或阻断"
            else -> ScenarioStatus.USABLE to "控制器已连"
        }

        return listOf(
            ScenarioAssessment("voice", "语音 / 会议", voice.first, voice.second),
            ScenarioAssessment("game", "低延迟游戏", game.first, game.second),
            ScenarioAssessment("stream", "高清流媒体", stream.first, stream.second),
            ScenarioAssessment("proxy", "代理 / 跨境", proxy.first, proxy.second)
        )
    }

    private fun buildDiagnosisText(
        input: NetworkQualityInput,
        overall: Int,
        grade: NetworkQualityGrade,
        jitterMs: Double?,
        dimensions: List<QualityDimensionScore>,
        measuredCount: Int,
        totalApplicable: Int
    ): Triple<String, String, String> {
        val rfAttr = input.rfDiagnostics.attribution
        val weakest = dimensions.filter { it.available && it.score != null }.minByOrNull { it.score!! }

        // 若双卡射频归因已有高置信结论，直接透传到首页总览，避免两套诊断口径冲突。
        // Surface high-confidence RF cross-layer attribution directly when present.
        if (rfAttr.cause == RfFaultCause.RF_AIR_INTERFACE || rfAttr.cause == RfFaultCause.COMPOUND_FAULT) {
            return Triple(
                rfAttr.title,
                rfAttr.detail,
                rfAttr.suggestion
            )
        }

        val linkName = input.link.transportLabel
        val envPrefix = if (linkName.endsWith("网络")) linkName else "$linkName 网络"
        val headline = when {
            !input.link.validated -> "已连接 $linkName，但系统联网校验未通过"
            (input.lossPercent ?: 0.0) >= 15.0 -> "链路存在明显丢包（${String.format(Locale.ROOT, "%.1f%%", input.lossPercent)}）"
            grade == NetworkQualityGrade.EXCELLENT -> "${envPrefix}环境极佳"
            grade == NetworkQualityGrade.GOOD -> "${envPrefix}环境良好"
            grade == NetworkQualityGrade.FAIR -> "${envPrefix}存在轻度瓶颈"
            else -> "${envPrefix}质量较差"
        }

        val metricsSummary = buildList {
            input.averageRttMs?.let { add(String.format(Locale.ROOT, "RTT %.0fms", it)) }
            input.lossPercent?.let { add(String.format(Locale.ROOT, "丢包 %.1f%%", it)) }
            jitterMs?.let { add(String.format(Locale.ROOT, "抖动 %.1fms", it)) }
            input.dnsInspection?.probeElapsedMs?.let { add("DNS ${it}ms") }
            if (input.link.isWifi && input.link.wifiRssiDbm != null) add("Wi-Fi ${input.link.wifiRssiDbm}dBm")
        }.joinToString(" · ").ifBlank { "已测 $measuredCount/$totalApplicable 维 · 等待探针回包" }

        val advice = when {
            !input.link.validated -> "检查认证页（Captive Portal）、路由网关或 VPN 隧道状态。"
            measuredCount < totalApplicable -> "当前仅完成 $measuredCount/$totalApplicable 项采样，点击「一键综合体检」补齐延迟、DNS 与出口维度。"
            weakest != null && (weakest.score ?: 100) < 60 -> "主要短板在「${weakest.id.label}」（${weakest.score} 分）：${weakest.detail}。"
            overall >= 85 -> "各项已测指标均衡平稳，可开启持续监测追踪长时抖动。"
            else -> "整体可用，建议关注 ${weakest?.id?.label ?: "链路延迟"} 的连续波动。"
        }

        return Triple(headline, metricsSummary, advice)
    }

    fun formatBandwidth(kbps: Int): String = when {
        kbps >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Gbps", kbps / 1_000_000.0)
        kbps >= 1_000 -> String.format(Locale.ROOT, "%.0f Mbps", kbps / 1_000.0)
        else -> "$kbps Kbps"
    }
}

/**
 * 首页质量评分滑窗记录器，保留最近 60 个观测点用于实时趋势图展示。
 * Sliding-window history recorder (up to 60 samples) for the Home quality trend chart.
 */
class NetworkQualityHistoryBuffer(private val maxPoints: Int = 60) {
    private val points = ArrayDeque<NetworkQualityHistoryPoint>()

    fun record(snapshot: NetworkQualitySnapshot, rttMs: Double?, lossPercent: Double?) {
        val score = snapshot.overallScore ?: return
        val last = points.lastOrNull()
        if (last != null && snapshot.evaluatedAtMs - last.observedAtMs < 900L) return
        points.addLast(
            NetworkQualityHistoryPoint(
                observedAtMs = snapshot.evaluatedAtMs,
                score = score,
                rttMs = rttMs,
                lossPercent = lossPercent
            )
        )
        while (points.size > maxPoints) points.removeFirst()
    }

    fun clear() = points.clear()

    fun snapshot(): List<NetworkQualityHistoryPoint> = points.toList()
}
