package io.wiesler.android.ui

import io.wiesler.android.ActivePrivacyRun
import io.wiesler.android.AppUiPreferences
import io.wiesler.android.DeviceEnvironmentInfo
import io.wiesler.android.DiscoveredController
import io.wiesler.android.EgressAddress
import io.wiesler.android.ImportedDatabaseInfo
import io.wiesler.android.PrivacyReport
import io.wiesler.android.PrivacySummary
import io.wiesler.android.Rate
import io.wiesler.android.RequestLog
import io.wiesler.android.StoredSession
import io.wiesler.android.StudioFlow
import io.wiesler.android.StudioFrame
import io.wiesler.android.StudioHostGroup
import io.wiesler.android.StudioSourceFilter

enum class WieslerTab(val index: Int, val label: String) {
    GRAPH(0, "图谱"),
    MONITOR(1, "监测"),
    PRIVACY(2, "隐私"),
    HISTORY(3, "历史"),
    SETTINGS(4, "设置")
}

data class AppUsageEntry(
    val title: String,
    val uid: Int,
    val rxBytes: Long,
    val txBytes: Long
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

data class PacketLossUiState(
    val target: String = "1.1.1.1",
    val running: Boolean = false,
    val status: String = "未开启 · 间隔 1 秒，超时 2 秒",
    val lossPercent: Double? = null,
    val averageRtt: Double? = null,
    val sent: Int = 0,
    val received: Int = 0,
    val errors: Int = 0
)

data class EgressUiState(
    val busy: Boolean = false,
    val system: EgressAddress? = null,
    val systemError: String? = null,
    val proxy: EgressAddress? = null,
    val proxyError: String? = null
)

data class WieslerUiState(
    val activeTab: WieslerTab = WieslerTab.GRAPH,
    val preferences: AppUiPreferences = AppUiPreferences(),
    // Controller & Capture
    val controllerAddress: String = "http://127.0.0.1:9090",
    val controllerEnabled: Boolean = false,
    val hasSecret: Boolean = false,
    val connectionState: String = "尚未连接手机控制器",
    val capturePaused: Boolean = false,
    val discoveringControllers: Boolean = false,
    val discoveredControllers: List<DiscoveredController> = emptyList(),
    val discoveryStatus: String = "点击扫描本机回环 Mihomo 控制器",
    // Graph & Studio
    val studioLive: Boolean = true,
    val studioFrozen: Boolean = false,
    val studioFrameCount: Int = 0,
    val studioFrameIndex: Int = 0,
    val studioCurrentFrame: StudioFrame? = null,
    val studioFilteredFlows: List<StudioFlow> = emptyList(),
    val studioHostGroups: List<StudioHostGroup> = emptyList(),
    val studioSelectedId: String? = null,
    val studioQuery: String = "",
    val studioProtocol: String = "全部",
    val studioSourceFilter: StudioSourceFilter = StudioSourceFilter.ALL,
    val studioRateHistory: Map<String, List<Rate?>> = emptyMap(),
    val mihomoTrend: List<Rate?> = emptyList(),
    // Egress
    val egress: EgressUiState = EgressUiState(),
    // Global & Forward traffic
    val globalEnabled: Boolean = true,
    val globalRate: Rate? = null,
    val globalTrend: List<Rate?> = emptyList(),
    val globalSampledAt: String = "尚未采样",
    val bootRxBytes: Long = -1L,
    val bootTxBytes: Long = -1L,
    val networkSummary: String = "读取中",
    val forwardEnabled: Boolean = false,
    val overseasExits: Set<String> = emptySet(),
    val availableExits: List<String> = emptyList(),
    val forwardRate: Rate? = null,
    val forwardTrend: List<Rate?> = emptyList(),
    val forwardState: String = "监控未开启",
    val forwardObservedRx: Double = 0.0,
    val forwardObservedTx: Double = 0.0,
    // ICMP
    val packetLoss: PacketLossUiState = PacketLossUiState(),
    // Apps usage
    val usageAllowed: Boolean = false,
    val usageBusy: Boolean = false,
    val usageError: String? = null,
    val appUsage: List<AppUsageEntry> = emptyList(),
    // Diagnostics & Privacy
    val systemNetworkSnapshot: String = "",
    val httpsProbeBusy: Boolean = false,
    val httpsProbeResult: String = "按需检查 HTTPS 连通性",
    val requestLogs: List<RequestLog> = emptyList(),
    val privacyReport: PrivacyReport? = null,
    val privacySummary: PrivacySummary? = null,
    val activePrivacyBusy: Boolean = false,
    val activePrivacyRun: ActivePrivacyRun? = null,
    val deviceEnvironment: DeviceEnvironmentInfo? = null,
    val exportedPrivacyJson: String = "",
    // SQLite History
    val importedDb: ImportedDatabaseInfo? = null,
    val selectedHistorySession: StoredSession? = null,
    val historyBusy: Boolean = false,
    val historyStatus: String = "选择 PC/CLI 导出的 Wiesler .sqlite3 文件以在手机端只读生成诊断报告",
    val historyReportMarkdown: String = "",
    // Dialog triggers
    val showControllerDialog: Boolean = false,
    val showExitsDialog: Boolean = false,
    val showScopeDialog: Boolean = false
)
