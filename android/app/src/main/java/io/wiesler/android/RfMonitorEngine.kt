package io.wiesler.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class RfRat(val label: String) {
    NR("5G NR"),
    LTE("4G LTE"),
    NO_SIGNAL("失锁 / 无信号"),
    OTHER("其他制式")
}

enum class RfSampleSource(val label: String) {
    ACTIVE_CELL_INFO("主动 CellInfo"),
    SIGNAL_CALLBACK("信号回调"),
    SERVICE_STATE("服务状态")
}

enum class RfAnomalyKind(val code: String, val label: String) {
    RF_INTERFERENCE("RF_INTERFERENCE", "强场强剪刀差干扰"),
    RF_SYNC_LOSS("RF_SYNC_LOSS", "3GPP 底板失步 / 失锁"),
    CELL_PINGPONG("CELL_PINGPONG", "ARFCN / 制式高频乒乓切换"),
    DUAL_SIM_POLLUTION("DUAL_SIM_POLLUTION", "双卡跨运营商并发污染"),
    CACHE_FREEZE("CACHE_FREEZE", "基带缓存未刷新")
}

enum class RfSeverity(val label: String) {
    IDLE("未开启"),
    CLEAR("射频平稳"),
    WATCH("轻度波动 / 衰落"),
    INTERFERENCE("单卡射频干扰或失步"),
    SEVERE_POLLUTION("环境级宽带通信污染")
}

enum class RfFaultCause(val code: String, val label: String) {
    INSUFFICIENT_EVIDENCE("INSUFFICIENT_EVIDENCE", "证据待补齐"),
    HEALTHY("HEALTHY", "空口与链路均平稳"),
    RF_AIR_INTERFACE("RF_AIR_INTERFACE", "射频空口污染致断流"),
    PROXY_OR_EGRESS("PROXY_OR_EGRESS", "代理节点 / 出口链路故障"),
    UPSTREAM_LINK("UPSTREAM_LINK", "运营商骨干或接入网抖动"),
    COMPOUND_FAULT("COMPOUND_FAULT", "空口污染与代理故障并发")
}

data class RfCellSample(
    val observedAtMs: Long,
    val slotIndex: Int,
    val subId: Int,
    val operatorName: String,
    val plmn: String,
    val rat: RfRat,
    val arfcn: Int? = null,
    val bandLabel: String = arfcnToBandLabel(arfcn, rat),
    val carrierAggregation: Boolean = false,
    val rsrpDbm: Int? = null,
    val rsrqDb: Int? = null,
    val sinrDb: Int? = null,
    val cellTimestampMs: Long? = null,
    val inService: Boolean = rat != RfRat.NO_SIGNAL,
    val source: RfSampleSource = RfSampleSource.ACTIVE_CELL_INFO
)

data class RfTrendPoint(
    val observedAtMs: Long,
    val rsrpDbm: Int?,
    val sinrDb: Int?,
    val rsrqDb: Int?,
    val flagged: Boolean
)

data class RfEvent(
    val observedAtMs: Long,
    val slotIndex: Int,
    val kind: RfAnomalyKind,
    val title: String,
    val detail: String
)

data class RfSlotSummary(
    val slotIndex: Int,
    val subId: Int,
    val operatorName: String,
    val plmn: String,
    val rat: RfRat,
    val arfcn: Int?,
    val bandLabel: String,
    val carrierAggregation: Boolean,
    val inService: Boolean,
    val rsrpDbm: Int?,
    val rsrqDb: Int?,
    val sinrDb: Int?,
    val freshFrames: Int,
    val cachedDuplicates: Int,
    val cacheFrozen: Boolean,
    val consecutiveScissors: Int,
    val scissorsCount30s: Int,
    val syncLossCount30s: Int,
    val pingPongTransitions60s: Int,
    val rsrpSinrCorrelation: Double?,
    val activeFlags: Set<RfAnomalyKind>,
    val lastFreshAtMs: Long?,
    val trend: List<RfTrendPoint>
)

data class RfCaptureHealth(
    val active: Boolean = false,
    val permissionGranted: Boolean = false,
    val freshFrames: Int = 0,
    val cachedDuplicates: Int = 0,
    val frozenSlotCount: Int = 0,
    val lastFreshAtMs: Long? = null,
    val statusText: String = "未开启 · 点击开始主动双卡射频检测"
)

data class RfPollutionAssessment(
    val score: Int = 0,
    val severity: RfSeverity = RfSeverity.IDLE,
    val title: String = "射频监测待机",
    val detail: String = "开启后按 3 秒节拍主动刷新双卡基带并按硬件时间戳去重。",
    val dualSimCoincidence: Boolean = false,
    val distinctAlertPlmns: Int = 0,
    val scissorsAlerts: Int = 0,
    val syncLossAlerts: Int = 0,
    val pingPongAlerts: Int = 0,
    val slots: List<RfSlotSummary> = emptyList(),
    val recentEvents: List<RfEvent> = emptyList()
)

data class TimedPingEvidence(
    val observedAtMs: Long,
    val outcome: String,
    val rttMs: Double?
)

data class RfLinkEvidence(
    val nowMs: Long,
    val recentPings: List<TimedPingEvidence> = emptyList(),
    val controllerEnabled: Boolean = false,
    val controllerHealthy: Boolean = false,
    val lastControllerErrorAtMs: Long? = null,
    val lastControllerError: String? = null,
    val cellularValidated: Boolean? = null,
    val cellularLostAtMs: Long? = null,
    val systemEgressOk: Boolean? = null,
    val proxyEgressOk: Boolean? = null
)

data class RfAttributionVerdict(
    val cause: RfFaultCause = RfFaultCause.INSUFFICIENT_EVIDENCE,
    val confidence: String = "待采样",
    val title: String = "等待射频与链路对照样本",
    val detail: String = "同时运行射频检测并开启 ICMP 探测或连接 Mihomo 控制器后，自动按时间窗对齐归因。",
    val suggestion: String = "建议先开启射频检测与 ICMP 丢包探测（如 223.5.5.5 或 1.1.1.1）。",
    val rfScore: Int = 0,
    val icmpSampleCount: Int = 0,
    val icmpLossPercent: Double? = null,
    val icmpAvgRttMs: Double? = null,
    val proxyHealthy: Boolean? = null,
    val cellularValidated: Boolean? = null,
    val alignedWindowSec: Int = 60
)

data class RfDiagnosticsSnapshot(
    val captureHealth: RfCaptureHealth = RfCaptureHealth(),
    val assessment: RfPollutionAssessment = RfPollutionAssessment(),
    val attribution: RfAttributionVerdict = RfAttributionVerdict()
)

fun cleanRfMetric(raw: Int?, minAllowed: Int, maxAllowed: Int): Int? {
    if (raw == null) return null
    if (abs(raw) >= 2_147_483_600 || raw == Int.MAX_VALUE || raw == Int.MIN_VALUE || raw == -1000) return null
    if (raw < minAllowed || raw > maxAllowed) return null
    return raw
}

fun cleanArfcn(raw: Int?): Int? {
    if (raw == null || raw <= 0 || raw >= 2_147_483_600) return null
    return raw
}

fun arfcnToBandLabel(arfcn: Int?, rat: RfRat): String {
    val ch = cleanArfcn(arfcn) ?: return if (rat == RfRat.NO_SIGNAL) "失锁" else rat.label
    return when (rat) {
        RfRat.NR -> when (ch) {
            in 151_600..160_600 -> "n28 (700MHz)"
            in 361_000..376_000 -> "n3 (1.8GHz)"
            in 422_000..434_000 -> "n1 (2.1GHz)"
            in 499_200..537_999 -> "n41 (2.6GHz)"
            in 620_000..653_333 -> "n78 (3.5GHz)"
            in 693_334..733_333 -> "n79 (4.9GHz)"
            else -> "NR#$ch"
        }
        RfRat.LTE -> when (ch) {
            in 0..599 -> "B1 (2.1GHz)"
            in 1_200..1_949 -> "B3 (1.8GHz)"
            in 2_400..2_649 -> "B5 (850MHz)"
            in 3_450..3_799 -> "B8 (900MHz)"
            in 37_750..38_249 -> "B38 (2.6GHz)"
            in 38_250..38_649 -> "B39 (1.9GHz)"
            in 38_650..39_649 -> "B40 (2.3GHz)"
            in 39_650..41_589 -> "B41 (2.6GHz)"
            else -> "LTE#$ch"
        }
        RfRat.NO_SIGNAL -> "失锁"
        RfRat.OTHER -> "CH#$ch"
    }
}

/**
 * 纯 Kotlin 蜂窝射频污染检测与联合归因引擎。
 * 规则与阈值锚定 data/rf-monitoring-20261127/README.md 实测取证结论：
 * 1) 硬件时间戳去重与缓存冻结识别；
 * 2) 剪刀差 / 3GPP 底板失步 / 60s 乒乓切换 / 30s 双卡跨 PLMN 并发评分；
 * 3) 与 ICMP 丢包/RTT、Mihomo 连接健康度、蜂窝链路校验的滑窗联合归因。
 *
 * Pure Kotlin RF contamination scorer and cross-layer fault attribution engine.
 */
class RfMonitorEngine {
    companion object {
        const val WINDOW_COINCIDENCE_MS = 30_000L
        const val WINDOW_PINGPONG_MS = 60_000L
        const val WINDOW_ATTRIBUTION_MS = 60_000L
        const val SCISSORS_RSRP_MIN_DBM = -95
        const val SCISSORS_SINR_MAX_DB = 0
        const val SCISSORS_RSRQ_MAX_DB = -15
        const val SYNC_FLOOR_RSRP_DBM = -140
        const val SYNC_FLOOR_RSRQ_DB = -30
        const val PINGPONG_MIN_TRANSITIONS = 4
        const val STALE_FREEZE_THRESHOLD = 3
        private const val MAX_SAMPLES_PER_SLOT = 120
        private const val MAX_EVENTS = 60
    }

    private data class ChannelTransition(val observedAtMs: Long, val key: String)

    private class SlotState(val slotIndex: Int) {
        var subId: Int = -1
        var operatorName: String = "卡 ${slotIndex + 1}"
        var plmn: String = ""
        var latestSample: RfCellSample? = null
        var freshFrames: Int = 0
        var cachedDuplicates: Int = 0
        var consecutiveStalePolls: Int = 0
        var lastCellTsKey: Triple<RfRat, Long, String>? = null
        var lastFreshAtMs: Long? = null
        var consecutiveScissors: Int = 0
        var lastChannelKey: String? = null
        val samples = ArrayDeque<RfCellSample>()
        val transitions = ArrayDeque<ChannelTransition>()
        var lastScissorsAlertAtMs: Long? = null
        var lastSyncLossAlertAtMs: Long? = null
        var lastPingPongAlertAtMs: Long? = null
        var freezeEventEmitted: Boolean = false
    }

    private val slots = LinkedHashMap<Int, SlotState>()
    private val events = ArrayDeque<RfEvent>()
    private var totalFreshFrames = 0
    private var totalCachedDuplicates = 0
    private var lastDualSimEventAtMs: Long? = null
    var version: Long = 0L
        private set

    fun reset() {
        slots.clear()
        events.clear()
        totalFreshFrames = 0
        totalCachedDuplicates = 0
        lastDualSimEventAtMs = null
        version++
    }

    fun isScissorsFrame(sample: RfCellSample): Boolean {
        val rsrp = sample.rsrpDbm ?: return false
        if (rsrp < SCISSORS_RSRP_MIN_DBM) return false
        val badSinr = sample.sinrDb != null && sample.sinrDb <= SCISSORS_SINR_MAX_DB
        val badRsrq = sample.rsrqDb != null && sample.rsrqDb <= SCISSORS_RSRQ_MAX_DB
        return badSinr || badRsrq
    }

    fun isSyncLossFrame(sample: RfCellSample): Boolean {
        if (!sample.inService || sample.rat == RfRat.NO_SIGNAL) return true
        if (sample.rsrpDbm != null && sample.rsrpDbm <= SYNC_FLOOR_RSRP_DBM) return true
        if (sample.rsrqDb != null && sample.rsrqDb <= SYNC_FLOOR_RSRQ_DB) return true
        return false
    }

    /**
     * 接收一条蜂窝样本；返回 true 表示属于新鲜基带样本，false 表示命中硬件时间戳去重缓存。
     * Accepts a cellular RF sample; returns true if fresh, false if deduplicated as stale cache.
     */
    fun ingest(raw: RfCellSample): Boolean {
        val sample = raw.copy(
            rsrpDbm = cleanRfMetric(raw.rsrpDbm, -160, -30),
            rsrqDb = cleanRfMetric(raw.rsrqDb, -50, 0),
            sinrDb = cleanRfMetric(raw.sinrDb, -30, 50),
            arfcn = cleanArfcn(raw.arfcn),
            bandLabel = arfcnToBandLabel(raw.arfcn, raw.rat)
        )
        val slot = slots.getOrPut(sample.slotIndex) { SlotState(sample.slotIndex) }
        if (sample.subId >= 0) slot.subId = sample.subId
        if (sample.operatorName.isNotBlank()) slot.operatorName = sample.operatorName
        if (sample.plmn.isNotBlank()) slot.plmn = sample.plmn

        // 主动 CellInfo 轮询按 (rat, cellTimestampMs, 指标指纹) 严格去重，防止息屏缓存冒充新鲜帧。
        // Deduplicate active CellInfo polls by hardware timestamp so cached frames never inflate sample counts.
        if (sample.source == RfSampleSource.ACTIVE_CELL_INFO && sample.cellTimestampMs != null) {
            val fingerprint = "${sample.rsrpDbm}/${sample.rsrqDb}/${sample.sinrDb}/${sample.arfcn}"
            val tsKey = Triple(sample.rat, sample.cellTimestampMs, fingerprint)
            if (slot.lastCellTsKey == tsKey) {
                slot.cachedDuplicates++
                slot.consecutiveStalePolls++
                totalCachedDuplicates++
                version++
                if (slot.consecutiveStalePolls >= STALE_FREEZE_THRESHOLD && !slot.freezeEventEmitted) {
                    slot.freezeEventEmitted = true
                    pushEvent(
                        RfEvent(
                            observedAtMs = sample.observedAtMs,
                            slotIndex = slot.slotIndex,
                            kind = RfAnomalyKind.CACHE_FREEZE,
                            title = "${slot.operatorName} 基带缓存未刷新",
                            detail = "连续 ${slot.consecutiveStalePolls} 次主动轮询返回相同硬件时间戳，已过滤重复帧。"
                        )
                    )
                }
                return false
            }
            slot.lastCellTsKey = tsKey
            slot.consecutiveStalePolls = 0
            slot.freezeEventEmitted = false
        }

        slot.freshFrames++
        slot.lastFreshAtMs = sample.observedAtMs
        slot.latestSample = sample
        totalFreshFrames++
        version++

        slot.samples.addLast(sample)
        while (slot.samples.size > MAX_SAMPLES_PER_SLOT) slot.samples.removeFirst()

        // 追踪 ARFCN / RAT 乒乓切换。 / Track ARFCN & RAT ping-pong transitions within 60s.
        val channelKey = buildChannelKey(sample)
        if (channelKey != null) {
            val prev = slot.lastChannelKey
            if (prev != null && prev != channelKey) {
                slot.transitions.addLast(ChannelTransition(sample.observedAtMs, "$prev -> $channelKey"))
            }
            slot.lastChannelKey = channelKey
        }
        pruneTransitions(slot, sample.observedAtMs)

        // 规则 1：强场强剪刀差（连续 >= 2 帧确认告警）。 / Rule 1: Interference scissors (>= 2 consecutive frames).
        if (isScissorsFrame(sample)) {
            slot.consecutiveScissors++
            if (slot.consecutiveScissors >= 2) {
                val shouldEmit = slot.lastScissorsAlertAtMs == null ||
                    sample.observedAtMs - slot.lastScissorsAlertAtMs!! >= 8_000L
                slot.lastScissorsAlertAtMs = sample.observedAtMs
                if (shouldEmit) {
                    pushEvent(
                        RfEvent(
                            observedAtMs = sample.observedAtMs,
                            slotIndex = slot.slotIndex,
                            kind = RfAnomalyKind.RF_INTERFERENCE,
                            title = "${slot.operatorName} 强信号剪刀差干扰",
                            detail = "${sample.bandLabel} · RSRP ${sample.rsrpDbm} dBm / RSRQ ${sample.rsrqDb ?: "—"} dB / SINR ${sample.sinrDb ?: "—"} dB（连续 ${slot.consecutiveScissors} 帧）"
                        )
                    )
                }
            }
        } else {
            slot.consecutiveScissors = 0
        }

        // 规则 2：3GPP 底板失步或 NO_SIGNAL 失锁。 / Rule 2: 3GPP sync floor or NO_SIGNAL blackout.
        if (isSyncLossFrame(sample)) {
            val shouldEmit = slot.lastSyncLossAlertAtMs == null ||
                sample.observedAtMs - slot.lastSyncLossAlertAtMs!! >= 8_000L
            slot.lastSyncLossAlertAtMs = sample.observedAtMs
            if (shouldEmit) {
                pushEvent(
                    RfEvent(
                        observedAtMs = sample.observedAtMs,
                        slotIndex = slot.slotIndex,
                        kind = RfAnomalyKind.RF_SYNC_LOSS,
                        title = "${slot.operatorName} 接收机失步 / 失锁",
                        detail = if (sample.rat == RfRat.NO_SIGNAL || !sample.inService) {
                            "进入 NO_SIGNAL / OUT_OF_SERVICE 失锁状态"
                        } else {
                            "${sample.bandLabel} 跌穿 3GPP 底板（RSRP ${sample.rsrpDbm ?: "—"} dBm, RSRQ ${sample.rsrqDb ?: "—"} dB）"
                        }
                    )
                )
            }
        }

        // 规则 3：60 秒乒乓切换率 >= 4 次。 / Rule 3: >= 4 ARFCN/RAT switches in 60s.
        if (slot.transitions.size >= PINGPONG_MIN_TRANSITIONS) {
            val shouldEmit = slot.lastPingPongAlertAtMs == null ||
                sample.observedAtMs - slot.lastPingPongAlertAtMs!! >= 15_000L
            slot.lastPingPongAlertAtMs = sample.observedAtMs
            if (shouldEmit) {
                pushEvent(
                    RfEvent(
                        observedAtMs = sample.observedAtMs,
                        slotIndex = slot.slotIndex,
                        kind = RfAnomalyKind.CELL_PINGPONG,
                        title = "${slot.operatorName} 高频乒乓切换",
                        detail = "最近 60 秒内频点/制式切换 ${slot.transitions.size} 次（最新 ${sample.bandLabel}）"
                    )
                )
            }
        }

        // 规则 4：跨卡 30 秒并发乘数事件记录。 / Rule 4: Dual-SIM 30s coincidence alert.
        val concurrentSlots = slots.values.filter { s ->
            val sc = s.lastScissorsAlertAtMs?.let { sample.observedAtMs - it <= WINDOW_COINCIDENCE_MS } == true
            val sl = s.lastSyncLossAlertAtMs?.let { sample.observedAtMs - it <= WINDOW_COINCIDENCE_MS } == true
            sc || sl
        }
        if (concurrentSlots.size >= 2) {
            val shouldEmit = lastDualSimEventAtMs == null || sample.observedAtMs - lastDualSimEventAtMs!! >= 15_000L
            if (shouldEmit) {
                lastDualSimEventAtMs = sample.observedAtMs
                val plmnSet = concurrentSlots.mapNotNull { it.plmn.takeIf(String::isNotBlank) }.toSet()
                val plmnDesc = if (plmnSet.size >= 2) "跨运营商 PLMN(${plmnSet.joinToString("/")})" else "双卡同步"
                pushEvent(
                    RfEvent(
                        observedAtMs = sample.observedAtMs,
                        slotIndex = -1,
                        kind = RfAnomalyKind.DUAL_SIM_POLLUTION,
                        title = "环境级宽带通信污染告警",
                        detail = "30 秒滑窗内 $plmnDesc 同时触发射频干扰/失步，排除单一运营商基站维护。"
                    )
                )
            }
        }
        return true
    }

    fun evaluate(
        nowMs: Long,
        active: Boolean,
        permissionGranted: Boolean,
        statusOverride: String? = null,
        linkEvidence: RfLinkEvidence = RfLinkEvidence(nowMs)
    ): RfDiagnosticsSnapshot {
        val slotSummaries = slots.values.sortedBy { it.slotIndex }.map { slot ->
            pruneTransitions(slot, nowMs)
            // 单遍遍历样本窗统计 30s 告警计数并累加 Pearson 相关系数，避免每轮分配多重临时列表与 Pair。
            // Single-pass scan over slot samples for 30s counts and 60s Pearson stats without temp Pair lists.
            var scissors30 = 0
            var syncLoss30 = 0
            var corrN = 0
            var sumX = 0.0
            var sumY = 0.0
            var sumXX = 0.0
            var sumYY = 0.0
            var sumXY = 0.0
            for (s in slot.samples) {
                val age = nowMs - s.observedAtMs
                if (age <= WINDOW_COINCIDENCE_MS) {
                    if (isScissorsFrame(s)) scissors30++
                    if (isSyncLossFrame(s)) syncLoss30++
                }
                if (age <= WINDOW_PINGPONG_MS && s.rsrpDbm != null && s.sinrDb != null) {
                    val x = s.rsrpDbm.toDouble()
                    val y = s.sinrDb.toDouble()
                    corrN++
                    sumX += x
                    sumY += y
                    sumXX += x * x
                    sumYY += y * y
                    sumXY += x * y
                }
            }
            val corr: Double? = if (corrN >= 4) {
                val num = corrN * sumXY - sumX * sumY
                val varX = corrN * sumXX - sumX * sumX
                val varY = corrN * sumYY - sumY * sumY
                val denom = sqrt(varX.coerceAtLeast(0.0) * varY.coerceAtLeast(0.0))
                if (denom <= 1e-9) null else (num / denom).coerceIn(-1.0, 1.0)
            } else {
                null
            }
            val pingPong60 = slot.transitions.size
            val flags = LinkedHashSet<RfAnomalyKind>()
            val hasConfirmedScissors = slot.lastScissorsAlertAtMs != null &&
                nowMs - slot.lastScissorsAlertAtMs!! <= WINDOW_COINCIDENCE_MS
            val hasRecentSyncLoss = syncLoss30 > 0 || (
                slot.lastSyncLossAlertAtMs != null &&
                    nowMs - slot.lastSyncLossAlertAtMs!! <= WINDOW_COINCIDENCE_MS
                )
            if (hasConfirmedScissors) flags.add(RfAnomalyKind.RF_INTERFERENCE)
            if (hasRecentSyncLoss) flags.add(RfAnomalyKind.RF_SYNC_LOSS)
            if (pingPong60 >= PINGPONG_MIN_TRANSITIONS) flags.add(RfAnomalyKind.CELL_PINGPONG)
            val frozen = slot.consecutiveStalePolls >= STALE_FREEZE_THRESHOLD
            if (frozen) flags.add(RfAnomalyKind.CACHE_FREEZE)

            val latest = slot.latestSample
            val sampleCount = slot.samples.size
            val trendStart = (sampleCount - 40).coerceAtLeast(0)
            val trendList = ArrayList<RfTrendPoint>(sampleCount - trendStart)
            for (i in trendStart until sampleCount) {
                val s = slot.samples[i]
                trendList.add(
                    RfTrendPoint(
                        observedAtMs = s.observedAtMs,
                        rsrpDbm = s.rsrpDbm,
                        sinrDb = s.sinrDb,
                        rsrqDb = s.rsrqDb,
                        flagged = isScissorsFrame(s) || isSyncLossFrame(s)
                    )
                )
            }
            RfSlotSummary(
                slotIndex = slot.slotIndex,
                subId = slot.subId,
                operatorName = slot.operatorName,
                plmn = slot.plmn,
                rat = latest?.rat ?: RfRat.OTHER,
                arfcn = latest?.arfcn,
                bandLabel = latest?.bandLabel ?: "—",
                carrierAggregation = latest?.carrierAggregation == true,
                inService = latest?.inService ?: true,
                rsrpDbm = latest?.rsrpDbm,
                rsrqDb = latest?.rsrqDb,
                sinrDb = latest?.sinrDb,
                freshFrames = slot.freshFrames,
                cachedDuplicates = slot.cachedDuplicates,
                cacheFrozen = frozen,
                consecutiveScissors = slot.consecutiveScissors,
                scissorsCount30s = scissors30,
                syncLossCount30s = syncLoss30,
                pingPongTransitions60s = pingPong60,
                rsrpSinrCorrelation = corr,
                activeFlags = flags,
                lastFreshAtMs = slot.lastFreshAtMs,
                trend = trendList
            )
        }

        val frozenCount = slotSummaries.count { it.cacheFrozen }
        val lastFresh = slotSummaries.mapNotNull { it.lastFreshAtMs }.maxOrNull()
        val captureStatus = statusOverride ?: when {
            !permissionGranted -> "需授予精确定位与读取手机状态权限以主动读取蜂窝基带"
            !active -> "未开启 · 点击开始主动双卡射频检测"
            totalFreshFrames == 0 -> "主动轮询中 · 等待基带回传首帧 CellInfo"
            frozenCount > 0 -> "采集中 · 检测到 $frozenCount 张卡基带缓存未刷新（已自动去重）"
            else -> "采集中 · 已采集 $totalFreshFrames 帧新鲜基带样本（过滤 $totalCachedDuplicates 帧缓存）"
        }
        val captureHealth = RfCaptureHealth(
            active = active,
            permissionGranted = permissionGranted,
            freshFrames = totalFreshFrames,
            cachedDuplicates = totalCachedDuplicates,
            frozenSlotCount = frozenCount,
            lastFreshAtMs = lastFresh,
            statusText = captureStatus
        )

        val assessment = buildAssessment(active, slotSummaries)
        val attribution = buildAttribution(nowMs, assessment, linkEvidence)
        return RfDiagnosticsSnapshot(captureHealth, assessment, attribution)
    }

    private fun buildAssessment(active: Boolean, summaries: List<RfSlotSummary>): RfPollutionAssessment {
        if (!active && summaries.isEmpty()) {
            return RfPollutionAssessment()
        }
        if (summaries.isEmpty()) {
            return RfPollutionAssessment(
                score = 0,
                severity = RfSeverity.CLEAR,
                title = "等待蜂窝基带样本",
                detail = "尚未收到有效 5G NR / 4G LTE 基带指标，请确认设备已插卡且非飞行模式。",
                recentEvents = events.toList()
            )
        }

        val interferenceSlots = summaries.filter { RfAnomalyKind.RF_INTERFERENCE in it.activeFlags }
        val syncLossSlots = summaries.filter { RfAnomalyKind.RF_SYNC_LOSS in it.activeFlags }
        val pingPongSlots = summaries.filter { RfAnomalyKind.CELL_PINGPONG in it.activeFlags }
        val severeAlertSlots = summaries.filter {
            RfAnomalyKind.RF_INTERFERENCE in it.activeFlags || RfAnomalyKind.RF_SYNC_LOSS in it.activeFlags
        }
        val distinctPlmns = severeAlertSlots.mapNotNull { it.plmn.takeIf(String::isNotBlank) }.toSet().size
        val dualSimCoincidence = severeAlertSlots.size >= 2

        val singleWatchScissors = summaries.any { it.scissorsCount30s == 1 }
        val weakFadeOnly = severeAlertSlots.isEmpty() && summaries.any {
            val rsrp = it.rsrpDbm ?: return@any false
            rsrp < -110 && (it.rsrpSinrCorrelation == null || it.rsrpSinrCorrelation >= 0.0)
        }
        val negativeCorr = summaries.any { (it.rsrpSinrCorrelation ?: 0.0) <= -0.35 }

        var score = 0
        if (interferenceSlots.isNotEmpty()) score += 35 + (interferenceSlots.size - 1) * 15
        else if (singleWatchScissors) score += 14
        if (syncLossSlots.isNotEmpty()) score += 45 + (syncLossSlots.size - 1) * 15
        if (pingPongSlots.isNotEmpty()) score += 20
        if (negativeCorr) score += 12
        if (dualSimCoincidence) {
            val floor = if (distinctPlmns >= 2) 85 else 78
            score = maxOf(score + 25, floor)
        } else if (weakFadeOnly) {
            score = maxOf(score, 18)
        }
        score = score.coerceIn(0, 100)

        val severity = when {
            dualSimCoincidence -> RfSeverity.SEVERE_POLLUTION
            severeAlertSlots.isNotEmpty() || score >= 45 -> RfSeverity.INTERFERENCE
            pingPongSlots.isNotEmpty() || singleWatchScissors || weakFadeOnly || score >= 15 -> RfSeverity.WATCH
            else -> RfSeverity.CLEAR
        }

        val title = when (severity) {
            RfSeverity.SEVERE_POLLUTION -> if (distinctPlmns >= 2) {
                "环境级宽带通信污染（跨运营商双卡并发）"
            } else {
                "环境级宽带通信污染（双卡同步异常）"
            }
            RfSeverity.INTERFERENCE -> when {
                syncLossSlots.isNotEmpty() -> "检测到接收机失步 / 3GPP 底板失锁"
                else -> "检测到强场强剪刀差射频干扰"
            }
            RfSeverity.WATCH -> when {
                pingPongSlots.isNotEmpty() -> "频点 / 制式高频乒乓切换"
                weakFadeOnly -> "弱覆盖衰落（未见强场强干扰指纹）"
                else -> "发现单帧射频质量波动，持续观察"
            }
            RfSeverity.CLEAR -> "双卡空口射频平稳"
            RfSeverity.IDLE -> "射频监测待机"
        }

        val detail = when (severity) {
            RfSeverity.SEVERE_POLLUTION -> {
                val names = severeAlertSlots.joinToString(" + ") { "${it.operatorName}(${it.bandLabel})" }
                "30 秒内 $names 同步触发干扰/失步（污染分 $score/100），已排除单一运营商基站维护。"
            }
            RfSeverity.INTERFERENCE -> {
                val top = severeAlertSlots.first()
                "${top.operatorName} 在 ${top.bandLabel} 触发 ${top.activeFlags.joinToString("、") { it.label }}（RSRP ${top.rsrpDbm ?: "—"} dBm / SINR ${top.sinrDb ?: "—"} dB）。"
            }
            RfSeverity.WATCH -> if (weakFadeOnly) {
                "场强偏弱（RSRP < -110 dBm）但未出现“强场强低信噪比”剪刀差，倾向物理遮挡或远距离衰落。"
            } else {
                "最近 60 秒存在轻度波动或频点重选，暂未形成跨卡并发污染。"
            }
            RfSeverity.CLEAR -> {
                summaries.joinToString(" · ") { s ->
                    "${s.operatorName} ${s.bandLabel} (${s.rsrpDbm ?: "—"} / ${s.sinrDb ?: "—"} dB)"
                }
            }
            RfSeverity.IDLE -> "未开启监测"
        }

        return RfPollutionAssessment(
            score = score,
            severity = severity,
            title = title,
            detail = detail,
            dualSimCoincidence = dualSimCoincidence,
            distinctAlertPlmns = distinctPlmns,
            scissorsAlerts = summaries.sumOf { it.scissorsCount30s },
            syncLossAlerts = summaries.sumOf { it.syncLossCount30s },
            pingPongAlerts = summaries.sumOf { it.pingPongTransitions60s },
            slots = summaries,
            recentEvents = events.toList()
        )
    }

    private fun buildAttribution(
        nowMs: Long,
        rf: RfPollutionAssessment,
        link: RfLinkEvidence
    ): RfAttributionVerdict {
        val recentPings = link.recentPings.filter { nowMs - it.observedAtMs <= WINDOW_ATTRIBUTION_MS }
        val validPings = recentPings.filter { it.outcome == "reply" || it.outcome == "lost" }
        val replies = validPings.filter { it.outcome == "reply" }
        val lossPct = if (validPings.isEmpty()) null else 100.0 * (validPings.size - replies.size) / validPings.size
        val avgRtt = replies.mapNotNull { it.rttMs?.takeIf { n -> n.isFinite() && n >= 0.0 } }
            .takeIf { it.isNotEmpty() }?.average()

        val rfBad = rf.severity == RfSeverity.SEVERE_POLLUTION || rf.severity == RfSeverity.INTERFERENCE
        val rfObserved = rf.slots.isNotEmpty()
        val cellularBroken = link.cellularValidated == false ||
            (link.cellularLostAtMs != null && nowMs - link.cellularLostAtMs <= WINDOW_ATTRIBUTION_MS)
        val icmpBad = (lossPct != null && lossPct >= 10.0) || (avgRtt != null && avgRtt >= 250.0)
        val icmpGood = validPings.size >= 3 && (lossPct ?: 100.0) < 5.0 && (avgRtt ?: 999.0) < 200.0
        val proxyFailedRecent = (link.controllerEnabled && !link.controllerHealthy) ||
            (link.lastControllerErrorAtMs != null && nowMs - link.lastControllerErrorAtMs <= WINDOW_ATTRIBUTION_MS) ||
            (link.systemEgressOk == true && link.proxyEgressOk == false)
        val proxyHealthy: Boolean? = when {
            !link.controllerEnabled && link.proxyEgressOk == null -> null
            proxyFailedRecent -> false
            else -> true
        }

        if (!rfObserved && validPings.isEmpty() && proxyHealthy == null) {
            return RfAttributionVerdict(
                cause = RfFaultCause.INSUFFICIENT_EVIDENCE,
                confidence = "待采样",
                title = "等待射频与链路对照样本",
                detail = "请先开启射频检测，并配合 ICMP 丢包探测或 Mihomo 控制器连接，才能在同时间窗内区分空口污染与代理故障。",
                suggestion = "点击「开始射频检测」并启动 ICMP 探测（推荐 223.5.5.5 或 1.1.1.1）。",
                rfScore = rf.score,
                cellularValidated = link.cellularValidated
            )
        }

        val (cause, confidence, title, detail, suggestion) = when {
            rfBad && proxyFailedRecent && link.systemEgressOk == true -> {
                Quintuple(
                    RfFaultCause.COMPOUND_FAULT,
                    "高",
                    "空口射频异常与代理链路故障并发",
                    "蜂窝射频已触发「${rf.severity.label}」（${rf.score} 分），同时代理控制器/代理出口异常而系统直连仍通，存在双层叠加故障。",
                    "先规避强干扰区域或切至稳定频段，再核查 Mihomo 节点与控制器状态。"
                )
            }
            rfBad && (icmpBad || cellularBroken || proxyFailedRecent || !validPings.isEmpty()) -> {
                val linkDesc = buildList {
                    if (lossPct != null) add(String.format(Locale.ROOT, "ICMP 丢包 %.1f%%", lossPct))
                    if (avgRtt != null) add(String.format(Locale.ROOT, "均延迟 %.0f ms", avgRtt))
                    if (cellularBroken) add("系统蜂窝联网校验失效/断链")
                    if (proxyFailedRecent) add("代理快照中断")
                }.joinToString("，").ifBlank { "射频告警持续中" }
                Quintuple(
                    RfFaultCause.RF_AIR_INTERFACE,
                    if (rf.dualSimCoincidence || icmpBad || cellularBroken) "高" else "中",
                    "优先归因为蜂窝射频空口污染",
                    "最近 60 秒射频评分 ${rf.score}/100（${rf.title}），同期链路观测：$linkDesc。根因位于无线空口而非代理节点本身。",
                    "无需盲目更换代理节点；建议离开当前干扰源、改连有线/纯净 Wi-Fi 或等待空口恢复。"
                )
            }
            !rfBad && rfObserved && proxyFailedRecent && (icmpGood || link.systemEgressOk == true || !icmpBad) -> {
                val errHint = link.lastControllerError?.let { "（$it）" } ?: ""
                Quintuple(
                    RfFaultCause.PROXY_OR_EGRESS,
                    if (icmpGood || link.systemEgressOk == true) "高" else "中",
                    "射频空口平稳 · 归因为代理节点 / 出口故障",
                    "蜂窝射频质量正常（评分 ${rf.score}/100），但代理控制器或代理出口探测失败$errHint。",
                    "重点排查 Mihomo 节点延迟、机场线路或控制器鉴权配置，空口本身无明显干扰。"
                )
            }
            !rfBad && rfObserved && icmpBad -> {
                Quintuple(
                    RfFaultCause.UPSTREAM_LINK,
                    "中",
                    "空口射频平稳 · 链路存在丢包或高延迟",
                    String.format(
                        Locale.ROOT,
                        "双卡射频指标平稳（评分 %d/100），但近 60 秒 ICMP 丢包 %.1f%%、均延迟 %s，倾向运营商核心网拥塞或回程路由波动。",
                        rf.score,
                        lossPct ?: 0.0,
                        avgRtt?.let { String.format(Locale.ROOT, "%.0f ms", it) } ?: "—"
                    ),
                    "可切换测试不同目标 IP（如 223.5.5.5 vs 1.1.1.1）确认是本地运营商回程还是跨境链路拥塞。"
                )
            }
            rfObserved && !rfBad && (icmpGood || proxyHealthy == true) -> {
                Quintuple(
                    RfFaultCause.HEALTHY,
                    "高",
                    "蜂窝射频与业务链路均平稳",
                    "近 60 秒内射频空口无剪刀差/失步告警（${rf.score}/100），且对照 ICMP/代理链路观测正常。",
                    "当前环境适合正常通信，可保持监测观察长时波动。"
                )
            }
            rfBad -> {
                Quintuple(
                    RfFaultCause.RF_AIR_INTERFACE,
                    "中",
                    "空口已触发射频污染告警（待补齐链路对照）",
                    "射频评分 ${rf.score}/100（${rf.title}），当前尚未开启 ICMP 探测或代理连接。",
                    "建议同时开启 ICMP 丢包探测以量化空口干扰对端到端延迟与丢包的影响。"
                )
            }
            else -> {
                Quintuple(
                    RfFaultCause.INSUFFICIENT_EVIDENCE,
                    "中",
                    if (rfObserved) "空口平稳 · 缺少链路对照样本" else "链路已观测 · 尚未开启射频监测",
                    if (rfObserved) {
                        "已确认空口射频平稳（${rf.score}/100），尚未收到最近 60 秒 ICMP 或代理健康对照数据。"
                    } else {
                        "已有链路观测，但尚未采集手机基带射频数据，无法排除空口干扰。"
                    },
                    if (rfObserved) "可开启下方 ICMP 探测（推荐 223.5.5.5）形成完整闭环归因。" else "请先开启蜂窝射频检测。"
                )
            }
        }

        return RfAttributionVerdict(
            cause = cause,
            confidence = confidence,
            title = title,
            detail = detail,
            suggestion = suggestion,
            rfScore = rf.score,
            icmpSampleCount = validPings.size,
            icmpLossPercent = lossPct,
            icmpAvgRttMs = avgRtt,
            proxyHealthy = proxyHealthy,
            cellularValidated = link.cellularValidated,
            alignedWindowSec = (WINDOW_ATTRIBUTION_MS / 1000L).toInt()
        )
    }

    private fun buildChannelKey(sample: RfCellSample): String? {
        if (sample.rat == RfRat.NO_SIGNAL) return "NO_SIGNAL"
        val ch = sample.arfcn
        return if (ch != null) "${sample.rat.name}:$ch" else sample.rat.name
    }

    private fun pruneTransitions(slot: SlotState, nowMs: Long) {
        while (slot.transitions.isNotEmpty() && nowMs - slot.transitions.first().observedAtMs > WINDOW_PINGPONG_MS) {
            slot.transitions.removeFirst()
        }
    }

    private fun pushEvent(event: RfEvent) {
        events.addFirst(event)
        while (events.size > MAX_EVENTS) events.removeLast()
    }

    private data class Quintuple<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
}

fun pearsonCorrelation(pairs: List<Pair<Double, Double>>): Double? {
    if (pairs.size < 4) return null
    val meanX = pairs.sumOf { it.first } / pairs.size
    val meanY = pairs.sumOf { it.second } / pairs.size
    var num = 0.0
    var denX = 0.0
    var denY = 0.0
    for ((x, y) in pairs) {
        val dx = x - meanX
        val dy = y - meanY
        num += dx * dy
        denX += dx * dx
        denY += dy * dy
    }
    if (denX <= 1e-9 || denY <= 1e-9) return null
    return (num / sqrt(denX * denY)).coerceIn(-1.0, 1.0)
}

fun RfDiagnosticsSnapshot.toSummaryText(): String {
    val sb = StringBuilder()
    sb.appendLine("【蜂窝射频检测与联合归因】")
    sb.appendLine("采集健康：${captureHealth.statusText}")
    sb.appendLine("污染评级：${assessment.severity.label}（评分 ${assessment.score}/100）· ${assessment.title}")
    sb.appendLine("判定依据：${assessment.detail}")
    for (slot in assessment.slots) {
        val flags = if (slot.activeFlags.isEmpty()) "正常" else slot.activeFlags.joinToString(",") { it.code }
        val corr = slot.rsrpSinrCorrelation?.let { String.format(Locale.ROOT, "r=%.2f", it) } ?: "r=—"
        sb.appendLine(
            "  卡${slot.slotIndex} ${slot.operatorName}(${slot.plmn.ifBlank { "未知PLMN" }}) " +
                "${slot.bandLabel}${if (slot.carrierAggregation) " CA" else ""} | " +
                "RSRP=${slot.rsrpDbm ?: "—"} dBm RSRQ=${slot.rsrqDb ?: "—"} dB SINR=${slot.sinrDb ?: "—"} dB | " +
                "新鲜=${slot.freshFrames} 缓存=${slot.cachedDuplicates} 切换60s=${slot.pingPongTransitions60s} $corr [$flags]"
        )
    }
    sb.appendLine("联合归因：${attribution.cause.label}（置信度：${attribution.confidence}）· ${attribution.title}")
    sb.appendLine("归因详情：${attribution.detail}")
    return sb.toString().trimEnd()
}

fun RfDiagnosticsSnapshot.toJsonObject(): JSONObject = JSONObject().apply {
    put("captureHealth", JSONObject().apply {
        put("active", captureHealth.active)
        put("permissionGranted", captureHealth.permissionGranted)
        put("freshFrames", captureHealth.freshFrames)
        put("cachedDuplicates", captureHealth.cachedDuplicates)
        put("frozenSlotCount", captureHealth.frozenSlotCount)
        put("lastFreshAtMs", captureHealth.lastFreshAtMs)
        put("statusText", captureHealth.statusText)
    })
    put("assessment", JSONObject().apply {
        put("score", assessment.score)
        put("severity", assessment.severity.name)
        put("title", assessment.title)
        put("detail", assessment.detail)
        put("dualSimCoincidence", assessment.dualSimCoincidence)
        put("distinctAlertPlmns", assessment.distinctAlertPlmns)
        put("scissorsAlerts", assessment.scissorsAlerts)
        put("syncLossAlerts", assessment.syncLossAlerts)
        put("pingPongAlerts", assessment.pingPongAlerts)
        put("slots", JSONArray().apply {
            assessment.slots.forEach { slot ->
                put(JSONObject().apply {
                    put("slotIndex", slot.slotIndex)
                    put("subId", slot.subId)
                    put("operatorName", slot.operatorName)
                    put("plmn", slot.plmn)
                    put("rat", slot.rat.name)
                    put("arfcn", slot.arfcn)
                    put("bandLabel", slot.bandLabel)
                    put("carrierAggregation", slot.carrierAggregation)
                    put("inService", slot.inService)
                    put("rsrpDbm", slot.rsrpDbm)
                    put("rsrqDb", slot.rsrqDb)
                    put("sinrDb", slot.sinrDb)
                    put("freshFrames", slot.freshFrames)
                    put("cachedDuplicates", slot.cachedDuplicates)
                    put("cacheFrozen", slot.cacheFrozen)
                    put("pingPongTransitions60s", slot.pingPongTransitions60s)
                    put("rsrpSinrCorrelation", slot.rsrpSinrCorrelation)
                    put("activeFlags", JSONArray(slot.activeFlags.map { it.code }))
                })
            }
        })
    })
    put("attribution", JSONObject().apply {
        put("cause", attribution.cause.code)
        put("confidence", attribution.confidence)
        put("title", attribution.title)
        put("detail", attribution.detail)
        put("suggestion", attribution.suggestion)
        put("rfScore", attribution.rfScore)
        put("icmpSampleCount", attribution.icmpSampleCount)
        put("icmpLossPercent", attribution.icmpLossPercent)
        put("icmpAvgRttMs", attribution.icmpAvgRttMs)
        put("proxyHealthy", attribution.proxyHealthy)
        put("cellularValidated", attribution.cellularValidated)
        put("alignedWindowSec", attribution.alignedWindowSec)
    })
}
