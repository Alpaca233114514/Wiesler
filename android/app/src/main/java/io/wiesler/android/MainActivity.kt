package io.wiesler.android

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.wiesler.android.ui.AppUsageEntry
import io.wiesler.android.ui.BannerTone
import io.wiesler.android.ui.EgressUiState
import io.wiesler.android.ui.PacketLossUiState
import io.wiesler.android.ui.TopBannerMessage
import io.wiesler.android.ui.WieslerActions
import io.wiesler.android.ui.WieslerAppShell
import io.wiesler.android.ui.WieslerExpressiveTheme
import io.wiesler.android.ui.WieslerTab
import io.wiesler.android.ui.WieslerUiState
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(4)
    private val lossWorker = Executors.newSingleThreadExecutor()

    private val studio = StudioSession().apply { switchSource(true) }
    private var demoTick = 0
    private val rates = RateTracker()
    private val trend = ArrayList<Rate?>()
    private val forwardRates = ForwardTrafficTracker()
    private val forwardTrend = ArrayList<Rate?>()
    private val mihomoTrend = ArrayList<Rate?>()
    private val requestLogs = ArrayDeque<RequestLog>()
    private val lossWindow = PacketLossWindow()
    private val timedPings = ArrayDeque<TimedPingEvidence>()
    private val qualityHistory = NetworkQualityHistoryBuffer()
    private val rfEngine = RfMonitorEngine()
    private var rfCollector: RfCellularCollector? = null
    private var rfEnabled = false
    private var rfStatusOverride: String? = null
    private var lastControllerErrorAtMs: Long? = null
    private var lastControllerErrorMessage: String? = null
    private var cellularValidated: Boolean? = null
    private var cellularLostAtMs: Long? = null
    private var lastSevereRfBannerAtMs = 0L
    private var cachedSystemLink = SystemLinkSnapshot()
    private var lastDnsInspection: ActiveDnsInspection? = null
    private var lastHttpsProbe: HttpsProbeSnapshot? = null
    private var qualityCheckBusy = false
    private var qualityCheckGeneration = 0
    private var hasAutoCheckedQuality = false

    private var foreground = false
    private var paused = false
    private var capturePaused = false
    private var forwardEnabled = false
    private var overseasExits = emptySet<String>()
    private var controller = "http://127.0.0.1:9090"
    private var secret = ""
    private var controllerEnabled = false
    private var controllerHealthy = false
    private var inFlightControllerGeneration: Int? = null
    private val controllerBusy: Boolean get() = inFlightControllerGeneration == controllerGeneration
    private var controllerGeneration = 0
    private var notifyNextControllerSuccess = false
    private var notifyNextControllerError = false
    private var bannerSeq = 0L
    private var connectionState = "尚未连接手机控制器"
    private var availableExitsCache = emptyList<String>()
    private var lastRate: Rate? = null
    private var lastRx = -1L
    private var lastTx = -1L
    private var sampledAt = "尚未采样"
    private var forwardRate: Rate? = null
    private var forwardState = "监控未开启"
    private var forwardSampledAt = "尚未采样"
    private var lossTarget = "1.1.1.1"
    private var lossRunning = false
    private var lossGeneration = 0
    private var lossProbe: IcmpProbe? = null
    private var lossStatus = "未开启 · 间隔 1 秒，超时 2 秒"
    private var usageBusy = false
    private var probeBusy = false
    private var probeResult = "按需检查 HTTPS 连通性"
    private var egressGeneration = 0
    private var privacyRunGeneration = 0
    private var lastPersistedAt = 0L
    private var lastSystemProbeAt = 0L
    private var cachedNetworkSummary = "读取中"
    private var cachedDiagnosticText = ""
    private var cachedUsageAllowed = false
    private var cachedPrivacyFrame: StudioFrame? = null
    private var cachedPrivacyPolicy: PrivacyPolicy? = null
    private var cachedPrivacyReport: PrivacyReport? = null
    private var cachedPrivacySummary: PrivacySummary? = null
    private var uiPrefs = AppUiPreferences()
    private val sampleDateFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
    private val lossTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    private data class ParsedConnectionsSnapshot(
        val time: Long,
        val flows: List<StudioFlow>,
        val counters: List<RoutedCounter>,
        val exits: List<String>,
        val downTotal: Long?,
        val upTotal: Long?
    )

    private var uiState by mutableStateOf(WieslerUiState())
    private val connectivity get() = getSystemService(ConnectivityManager::class.java)
    private val lossTick = Runnable { sampleLoss() }

    private val openDbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importDatabase(uri)
    }

    private val rfPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = RfCellularCollector.REQUIRED_PERMISSIONS.all { grants[it] == true } ||
            RfCellularCollector.hasPermissions(this)
        if (granted) {
            rfStatusOverride = null
            setRfMonitoringEnabled(true)
            showTopBanner("射频监测权限已就绪", "已启动主动双卡蜂窝射频检测", BannerTone.SUCCESS, durationMs = 3200L)
        } else {
            rfEnabled = false
            rfStatusOverride = "权限未授予，无法主动读取双卡基带 RSRP/RSRQ/SINR"
            showTopBanner("未获得定位或手机状态权限", "请在系统设置中授权后开启射频检测", BannerTone.WARNING, durationMs = 4500L)
            publishUiState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getPreferences(0)
        uiPrefs = AppUiPreferencesStore.read(prefs)
        val savedTabIndex = savedInstanceState?.getInt("tab") ?: prefs.getInt("ui_tab", 0)
        val initialTab = WieslerTab.entries.firstOrNull { it.index == savedTabIndex } ?: WieslerTab.HOME
        paused = savedInstanceState?.getBoolean("paused") ?: prefs.getBoolean("paused", false)
        lossTarget = prefs.getString("lossTarget", "1.1.1.1") ?: "1.1.1.1"
        controller = prefs.getString("controller", controller) ?: controller
        overseasExits = prefs.getStringSet("overseasExits", emptySet())?.toSet() ?: emptySet()
        studio.overseasExits = overseasExits
        forwardEnabled = prefs.getBoolean("forwardEnabled", false)
        rfEnabled = prefs.getBoolean("rfEnabled", false)
        controllerEnabled = prefs.getBoolean("controllerEnabled", prefs.contains("controller"))
        rfCollector = RfCellularCollector(
            context = applicationContext,
            executor = worker,
            onSample = { sample ->
                handler.post {
                    if (isDestroyed) return@post
                    rfEngine.ingest(sample)
                    rfStatusOverride = null
                    val snap = evaluateRfSnapshot()
                    if (snap.assessment.severity == RfSeverity.SEVERE_POLLUTION) {
                        val now = System.currentTimeMillis()
                        if (now - lastSevereRfBannerAtMs >= 30_000L) {
                            lastSevereRfBannerAtMs = now
                            showTopBanner(
                                title = "检测到环境级宽带通信污染",
                                detail = snap.assessment.detail,
                                tone = BannerTone.ERROR,
                                durationMs = 6000L
                            )
                        }
                    }
                    publishUiState()
                }
            },
            onCellularLinkState = { validated, lostAt ->
                handler.post {
                    if (isDestroyed) return@post
                    cellularValidated = validated
                    if (lostAt != null) cellularLostAtMs = lostAt
                    publishUiState()
                }
            },
            onStatusError = { msg ->
                handler.post {
                    if (isDestroyed) return@post
                    rfStatusOverride = msg
                    publishUiState()
                }
            }
        )
        secret = runCatching { ControllerSecretStore(prefs).read(controller) }.getOrElse {
            controllerEnabled = false
            showTopBanner("无法解密控制器密钥", "Keystore 密钥不可用，请重新配置", BannerTone.ERROR, durationMs = 5500L)
            ""
        }
        if (controllerEnabled) {
            connectionState = "等待重新连接"
            notifyNextControllerError = true
        }
        prefs.getString("history", null)?.let { stored ->
            runCatching { SavedHistory.decode(stored) }.onSuccess { history ->
                trend.addAll(history.trend)
                forwardTrend.addAll(history.forwardTrend)
                forwardRates.restoreTotals(history.rx, history.tx, overseasExits)
                requestLogs.addAll(history.logs)
                sampledAt = history.sampledAt
                forwardSampledAt = history.forwardSampledAt
            }.onFailure {
                Toast.makeText(this, "历史记录读取失败", Toast.LENGTH_LONG).show()
            }
        }
        uiState = uiState.copy(activeTab = initialTab, preferences = uiPrefs)
        publishUiState()

        val actions = buildActions()
        setContent {
            WieslerExpressiveTheme(
                themePreference = uiState.preferences.themeMode,
                dynamicColor = uiState.preferences.dynamicColor
            ) {
                WieslerAppShell(state = uiState, actions = actions)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", uiState.activeTab.index)
        outState.putBoolean("paused", paused)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        rates.reset()
        lastRate = null
        appendSample(trend, null)
        studio.resetBaseline()
        resetForward()
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (controllerEnabled && !controllerBusy && !capturePaused) fetchConnections()
        if (rfEnabled && RfCellularCollector.hasPermissions(this)) {
            rfCollector?.start()
        }
        refreshSystemProbes(force = true)
        if (uiState.activeTab == WieslerTab.MONITOR && cachedUsageAllowed) loadUsage()
        if (!hasAutoCheckedQuality && uiState.activeTab == WieslerTab.HOME) {
            hasAutoCheckedQuality = true
            runQualityCheck()
        }
        publishUiState()
    }

    override fun onPause() {
        foreground = false
        controllerGeneration++
        handler.removeCallbacks(tick)
        rates.reset()
        lastRate = null
        studio.resetBaseline()
        resetForward()
        if (lossRunning) stopLoss("已进入后台，监控停止")
        rfCollector?.stop()
        persistState(async = false)
        super.onPause()
    }

    override fun onDestroy() {
        rfCollector?.stop()
        lossProbe?.cancel()
        lossWorker.shutdownNow()
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        secret = ""
        super.onDestroy()
    }

    private fun buildActions(): WieslerActions = WieslerActions(
        onSelectTab = { tab ->
            uiState = uiState.copy(activeTab = tab)
            if (tab == WieslerTab.HOME) {
                refreshSystemProbes(force = true)
                if (!hasAutoCheckedQuality) {
                    hasAutoCheckedQuality = true
                    runQualityCheck()
                }
            }
            if (tab == WieslerTab.MONITOR && usageAllowed() && uiState.appUsage.isEmpty()) loadUsage()
            if (tab == WieslerTab.PRIVACY) refreshSystemNetwork()
            persistState()
            publishUiState()
        },
        onRunQualityCheck = { runQualityCheck() },
        onToggleQualityMonitor = { enabled -> toggleQualityContinuousMonitor(enabled) },
        onSelectQualityPingTarget = { target -> selectQualityPingTarget(target) },
        onSwitchStudioSource = { live ->
            if (studio.live != live) {
                studio.switchSource(live)
                if (!live) {
                    demoTick = 0
                    sampleDemo()
                } else if (controllerEnabled && !controllerBusy && !capturePaused) {
                    fetchConnections()
                }
                publishUiState()
            }
        },
        onToggleFreeze = {
            if (studio.held == null) studio.freeze() else studio.now()
            publishUiState()
        },
        onToggleCapturePause = {
            capturePaused = !capturePaused
            if (capturePaused) {
                controllerGeneration++
                studio.resetBaseline()
                resetForward()
            } else if (controllerEnabled && !controllerBusy) {
                fetchConnections()
            }
            publishUiState()
        },
        onReplayIndex = { index ->
            studio.replay(index)
            publishUiState()
        },
        onBackToNow = {
            studio.now()
            publishUiState()
        },
        onQueryChange = { q ->
            studio.query = q
            publishUiState()
        },
        onProtocolChange = { proto ->
            studio.protocol = proto
            publishUiState()
        },
        onSourceFilterChange = { filter ->
            studio.sourceFilter = filter
            publishUiState()
        },
        onGraphModeChange = { mode ->
            studio.graphMode = mode
            publishUiState()
        },
        onSelectFlow = { id ->
            studio.selectedId = id
            publishUiState()
        },
        onRefreshEgress = { refreshEgress() },
        onToggleGlobal = { enabled ->
            paused = !enabled
            rates.reset()
            lastRate = null
            appendSample(trend, null)
            persistState()
            publishUiState()
        },
        onToggleForward = { enabled ->
            forwardEnabled = enabled
            controllerGeneration++
            resetForward()
            persistState()
            publishUiState()
        },
        onJumpOverseasConnections = {
            studio.sourceFilter = StudioSourceFilter.OVERSEAS
            uiState = uiState.copy(activeTab = WieslerTab.GRAPH)
            publishUiState()
        },
        onStartLoss = { target ->
            lossTarget = target
            getPreferences(0).edit().putString("lossTarget", target).apply()
            lossGeneration++
            lossWindow.clear()
            lossRunning = true
            lossStatus = "$target · 正在探测…"
            publishUiState()
            sampleLoss()
        },
        onStopLoss = {
            stopLoss("已停止 · 保留最后结果")
            publishUiState()
        },
        onToggleRfMonitor = { enabled -> setRfMonitoringEnabled(enabled) },
        onRequestRfPermissions = {
            rfPermissionLauncher.launch(RfCellularCollector.REQUIRED_PERMISSIONS)
        },
        onResetRfMonitor = {
            rfEngine.reset()
            rfStatusOverride = null
            publishUiState()
        },
        onRequestUsageAccess = {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        },
        onRefreshUsage = { loadUsage() },
        onRunActivePrivacy = { runActivePrivacyInspection() },
        onUpdateExpectProxy = { expect ->
            updatePrefs(uiPrefs.copy(expectProxy = expect))
        },
        onUpdateUploadThresholdKib = { kib ->
            updatePrefs(uiPrefs.copy(uploadThresholdKib = kib))
        },
        onRunHttpsProbe = { runHttpsProbe() },
        onRefreshSystemNetwork = { refreshSystemNetwork() },
        onRunEnvironmentCheck = { runEnvironmentCheck() },
        onGeneratePrivacyJson = { generatePrivacyJson() },
        onShareTextReport = { shareTextReport() },
        onShareJsonReport = {
            val json = generatePrivacyJson()
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_TEXT, json)
            }, "分享隐私 JSON 证据报告"))
        },
        onPickDatabase = { openDbLauncher.launch(arrayOf("*/*")) },
        onSelectHistorySession = { session ->
            uiState = uiState.copy(selectedHistorySession = session, historyReportMarkdown = "")
        },
        onGenerateHistoryReport = { generateHistoryReport() },
        onShareHistoryReport = {
            val report = uiState.historyReportMarkdown
            if (report.isNotBlank()) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, report)
                }, "分享历史会话诊断报告"))
            }
        },
        onScanControllers = { scanLocalControllers() },
        onConnectDiscovered = { discovered ->
            if (discovered.requiresAuth) {
                controller = discovered.address
                uiState = uiState.copy(controllerAddress = controller, showControllerDialog = true)
            } else {
                saveControllerConfig(discovered.address, "")
            }
        },
        onSaveController = { addr, newSecret -> saveControllerConfig(addr, newSecret) },
        onDisconnectController = { disconnectController() },
        onSaveExits = { selected -> saveExits(selected) },
        onTogglePulseAnimation = { enabled -> updatePrefs(uiPrefs.copy(pulseAnimation = enabled)) },
        onChangeThemeMode = { mode -> updatePrefs(uiPrefs.copy(themeMode = mode)) },
        onToggleDynamicColor = { enabled -> updatePrefs(uiPrefs.copy(dynamicColor = enabled)) },
        onSetControllerDialog = { open -> uiState = uiState.copy(showControllerDialog = open) },
        onSetExitsDialog = { open -> uiState = uiState.copy(showExitsDialog = open) },
        onSetScopeDialog = { open -> uiState = uiState.copy(showScopeDialog = open) },
        onDismissBanner = { id -> dismissTopBanner(id) }
    )

    private fun showTopBanner(
        title: String,
        detail: String? = null,
        tone: BannerTone = BannerTone.ERROR,
        durationMs: Long = 4500L
    ) {
        val msg = TopBannerMessage(
            id = ++bannerSeq,
            title = title,
            detail = detail,
            tone = tone,
            durationMs = durationMs
        )
        uiState = uiState.copy(topBanner = msg)
    }

    private fun dismissTopBanner(id: Long) {
        if (uiState.topBanner?.id == id) {
            uiState = uiState.copy(topBanner = null)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!foreground) return
            val nowMs = SystemClock.elapsedRealtime()
            val tab = uiState.activeTab
            if (!paused) {
                lastRx = android.net.TrafficStats.getTotalRxBytes()
                lastTx = android.net.TrafficStats.getTotalTxBytes()
                lastRate = rates.accept(CounterSample(nowMs, lastRx, lastTx))
                appendSample(trend, lastRate)
                sampledAt = sampleDateFmt.format(Date())
            }
            var triggeredFetch = false
            if (!capturePaused) {
                if (tab == WieslerTab.GRAPH && !studio.live) sampleDemo()
                val needsController = forwardEnabled || tab in setOf(WieslerTab.HOME, WieslerTab.GRAPH, WieslerTab.MONITOR, WieslerTab.PRIVACY)
                if (controllerEnabled && needsController && !controllerBusy) {
                    fetchConnections()
                    triggeredFetch = true
                }
            }
            if (nowMs - lastPersistedAt >= 5000) persistState(async = true)
            // 本轮若只更新 TrafficStats 且即将等待控制器回包，避免在无变化标签页做无谓重组。
            // Only publish immediately on tabs that show live TrafficStats/demo or when no fetch is in flight.
            if (!triggeredFetch || tab == WieslerTab.HOME || tab == WieslerTab.MONITOR || (tab == WieslerTab.GRAPH && !studio.live)) {
                publishUiState()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun updatePrefs(next: AppUiPreferences) {
        uiPrefs = next
        AppUiPreferencesStore.write(getPreferences(0), next)
        publishUiState()
    }

    private fun persistState(async: Boolean = true) {
        val history = SavedHistory(
            trend.toList(),
            forwardTrend.toList(),
            forwardRates.observedRx,
            forwardRates.observedTx,
            requestLogs.toList(),
            sampledAt,
            forwardSampledAt
        )
        val tabIndex = uiState.activeTab.index
        val isPaused = paused
        val isForward = forwardEnabled
        val exitsCopy = overseasExits.toSet()
        lastPersistedAt = SystemClock.elapsedRealtime()
        val writeAction = {
            val encoded = history.encode()
            getPreferences(0).edit()
                .putBoolean("studioAvailable", true)
                .putInt("ui_tab", tabIndex)
                .putBoolean("paused", isPaused)
                .putBoolean("forwardEnabled", isForward)
                .putStringSet("overseasExits", exitsCopy)
                .putString("history", encoded)
                .apply()
        }
        if (async && !worker.isShutdown) {
            worker.execute(writeAction)
        } else {
            writeAction()
        }
    }

    private fun appendSample(samples: MutableList<Rate?>, rate: Rate?) {
        samples.add(rate)
        if (samples.size > 60) samples.removeAt(0)
    }

    private fun resetForward(clearHistory: Boolean = false) {
        forwardRates.reset(clearTotals = clearHistory)
        forwardRate = null
        if (clearHistory) forwardTrend.clear() else appendSample(forwardTrend, null)
        forwardState = when {
            !forwardEnabled -> "监控未开启"
            !controllerEnabled -> "请连接手机控制器"
            overseasExits.isEmpty() -> "请选择海外出口"
            else -> "等待连续采样"
        }
    }

    private fun sampleDemo() {
        val flows = studioDemo(demoTick++)
        val downSum = flows.sumOf { it.download.coerceAtLeast(0) }
        val upSum = flows.sumOf { it.upload.coerceAtLeast(0) }
        studio.accept(flows, SystemClock.elapsedRealtime(), System.currentTimeMillis(), downloadTotal = downSum, uploadTotal = upSum)
    }

    private fun saveControllerConfig(address: String, enteredSecret: String): String? {
        val changedController = controller != address
        val effectiveSecret = if (enteredSecret.isEmpty() && !changedController) secret else enteredSecret
        val saved = runCatching {
            ControllerSecretStore(getPreferences(0)).save(address, effectiveSecret, true)
        }.onFailure { err ->
            Log.w("Wiesler", "Failed to persist controller secret for $address: ${err.javaClass.simpleName}: ${err.message}")
        }.getOrDefault(false)
        if (!saved) {
            showTopBanner("保存控制器设置失败", "Keystore 写入异常，请重试", BannerTone.ERROR)
            return "无法安全保存连接设置，请重试"
        }
        controller = address
        secret = effectiveSecret
        controllerEnabled = true
        controllerHealthy = false
        capturePaused = false
        // 递增代际立即解除旧地址在途请求的阻塞，保证新地址立刻发起拉取并汇报结果。
        // Bumping generation immediately unblocks a new fetch even if a stale request is still timing out.
        controllerGeneration++
        notifyNextControllerSuccess = true
        notifyNextControllerError = true
        if (studio.live) {
            studio.switchSource(false)
            studio.switchSource(true)
        }
        if (changedController) {
            overseasExits = emptySet()
            studio.overseasExits = emptySet()
        }
        resetForward(clearHistory = changedController)
        getPreferences(0).edit()
            .putString("controller", controller)
            .putStringSet("overseasExits", overseasExits)
            .apply()
        availableExitsCache = emptyList()
        connectionState = "连接中…"
        persistState()
        publishUiState()
        fetchConnections()
        refreshEgress()
        return null
    }

    private fun disconnectController() {
        if (!runCatching { ControllerSecretStore(getPreferences(0)).save(controller, "", false) }.getOrDefault(false)) {
            showTopBanner("断开设置保存失败", "无法清除本地凭证，请重试", BannerTone.ERROR)
            return
        }
        controllerGeneration++
        controllerEnabled = false
        controllerHealthy = false
        notifyNextControllerSuccess = false
        notifyNextControllerError = false
        secret = ""
        availableExitsCache = emptyList()
        connectionState = "已断开"
        resetForward()
        appendSample(mihomoTrend, null)
        if (studio.live) {
            studio.now()
            studio.accept(emptyList(), SystemClock.elapsedRealtime(), System.currentTimeMillis(), connectionState)
        }
        persistState()
        publishUiState()
    }

    private fun saveExits(selected: Set<String>) {
        if (selected == overseasExits) return
        overseasExits = selected.toSet()
        studio.overseasExits = overseasExits
        getPreferences(0).edit().putStringSet("overseasExits", overseasExits).apply()
        controllerGeneration++
        resetForward(clearHistory = true)
        persistState()
        publishUiState()
    }

    private fun scanLocalControllers() {
        if (uiState.discoveringControllers) return
        uiState = uiState.copy(
            discoveringControllers = true,
            discoveryFailed = false,
            discoveryStatus = "正在并发扫描 ${ControllerDiscovery.DEFAULT_PORTS.size} 个常用回环端口…"
        )
        val extras = listOf(controller)
        worker.execute {
            val report = ControllerDiscovery.scanLocal(extras)
            handler.post {
                if (isDestroyed) return@post
                val failed = report.controllers.isEmpty()
                val msg = when {
                    failed -> report.failureSummary()
                    report.controllers.size == 1 -> "发现 1 个控制器（扫描 ${report.scannedPorts} 端口），可点击右侧直接连接"
                    else -> "发现 ${report.controllers.size} 个控制器，请选择其中一个连接"
                }
                if (failed) {
                    Log.w("Wiesler", "Local controller scan found none (${report.scannedPorts} ports): $msg")
                    appendRequestLog("本机端口扫描 (${report.scannedPorts})", null, msg)
                    showTopBanner("未发现可用本机控制器", msg, BannerTone.ERROR, durationMs = 5500L)
                } else {
                    val foundList = report.controllers.joinToString("、") { it.address.removePrefix("http://") }
                    showTopBanner(
                        title = "发现 ${report.controllers.size} 个本机控制器",
                        detail = foundList,
                        tone = BannerTone.SUCCESS,
                        durationMs = 3600L
                    )
                }
                uiState = uiState.copy(
                    discoveringControllers = false,
                    discoveredControllers = report.controllers,
                    discoveryStatus = msg,
                    discoveryFailed = failed
                )
            }
        }
    }

    private fun routedCounter(item: JSONObject): RoutedCounter = RoutedCounter(
        item.optString("id"),
        item.optString("start"),
        item.optJSONArray("chains")?.optString(0)?.takeIf { it.isNotBlank() },
        item.optLong("download", -1),
        item.optLong("upload", -1)
    )

    private fun fetchConnections() {
        if (!foreground || !controllerEnabled || controllerBusy || capturePaused) return
        val generation = controllerGeneration
        inFlightControllerGeneration = generation
        val address = controller
        val token = secret
        worker.execute {
            var httpStatus: Int? = null
            val result = runCatching {
                val connection = URL("$address/connections").openConnection(Proxy.NO_PROXY) as HttpURLConnection
                try {
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.instanceFollowRedirects = false
                    if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
                    val code = connection.responseCode
                    httpStatus = code.takeIf { it in 100..599 }
                    if (code == 401 || code == 403) error("鉴权失败，请检查 Secret")
                    if (code != 200) error("控制器返回 HTTP $code")
                    val payload = connection.inputStream.bufferedReader().use { reader ->
                        val data = StringBuilder()
                        val buffer = CharArray(8192)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            data.append(buffer, 0, count)
                            if (data.length > 4_000_000) error("控制器快照过大")
                        }
                        data.toString()
                    }
                    val json = JSONObject(payload)
                    if (!json.has("connections")) error("不是有效的 Mihomo 快照")
                    val array = json.optJSONArray("connections")
                    if (array == null && !json.isNull("connections")) error("连接列表格式无效")
                    val count = array?.length() ?: 0
                    val parsedFlows = ArrayList<StudioFlow>(count)
                    val counters = ArrayList<RoutedCounter>(count)
                    val exits = HashSet<String>()
                    // 在后台线程完成全部连接 JSON 解析与出口提取，彻底移出主线程。
                    // Parse all flows, counters, and candidate exits off the main thread.
                    for (idx in 0 until count) {
                        val item = array!!.getJSONObject(idx)
                        parsedFlows.add(parseStudioFlow(item))
                        val counter = routedCounter(item)
                        counters.add(counter)
                        val exit = counter.exit
                        if (exit != null && eligibleExit(exit)) exits.add(exit)
                    }
                    val downTotal = json.optLong("downloadTotal", -1L).takeIf { it >= 0 }
                    val upTotal = json.optLong("uploadTotal", -1L).takeIf { it >= 0 }
                    ParsedConnectionsSnapshot(
                        time = SystemClock.elapsedRealtime(),
                        flows = parsedFlows,
                        counters = counters,
                        exits = exits.sorted(),
                        downTotal = downTotal,
                        upTotal = upTotal
                    )
                } finally {
                    connection.disconnect()
                }
            }
            handler.post {
                if (inFlightControllerGeneration == generation) inFlightControllerGeneration = null
                if (isDestroyed || !foreground || generation != controllerGeneration) return@post
                result.fold(
                    onSuccess = { snapshot ->
                        controllerHealthy = true
                        lastControllerErrorMessage = null
                        availableExitsCache = snapshot.exits
                        if (studio.live) {
                            studio.accept(
                                flows = snapshot.flows,
                                time = snapshot.time,
                                wallTime = System.currentTimeMillis(),
                                downloadTotal = snapshot.downTotal,
                                uploadTotal = snapshot.upTotal
                            )
                            val rxT = studio.frames.lastOrNull()?.rxTotal
                            val txT = studio.frames.lastOrNull()?.txTotal
                            appendSample(mihomoTrend, if (rxT != null && txT != null) Rate(rxT, txT) else null)
                        }
                        forwardSampledAt = sampleDateFmt.format(Date())
                        connectionState = "已连接 · HTTP $httpStatus · ${snapshot.flows.size} 条连接 · $forwardSampledAt"
                        appendRequestLog("Mihomo /connections", httpStatus, null)
                        if (notifyNextControllerSuccess) {
                            notifyNextControllerSuccess = false
                            notifyNextControllerError = false
                            showTopBanner(
                                title = "控制器已连接",
                                detail = "${address.removePrefix("http://")} · 当前 ${snapshot.flows.size} 条活跃连接",
                                tone = BannerTone.SUCCESS,
                                durationMs = 3200L
                            )
                        } else {
                            notifyNextControllerError = false
                        }
                        if (forwardEnabled) {
                            forwardRate = forwardRates.accept(snapshot.time, snapshot.counters, overseasExits)
                            appendSample(forwardTrend, forwardRate)
                            val count = snapshot.counters.count { trafficRoute(it.exit, overseasExits) == TrafficRoute.OVERSEAS }
                            forwardState = when {
                                overseasExits.isEmpty() -> "请选择海外出口"
                                count == 0 && snapshot.flows.isNotEmpty() -> "所选出口未匹配当前连接，请核对节点名称"
                                forwardRate == null -> "等待连续采样 · $forwardSampledAt"
                                else -> "$count 条转发连接 · $forwardSampledAt"
                            }
                        }
                    },
                    onFailure = { err ->
                        val wasHealthy = controllerHealthy
                        controllerHealthy = false
                        availableExitsCache = emptyList()
                        val rawMessage = when (err) {
                            is java.net.ConnectException -> "无法连接，请确认手机控制器已开启"
                            is java.net.SocketTimeoutException -> "连接超时，正在重试"
                            is IllegalStateException -> err.message ?: "快照无效"
                            else -> "读取失败：${err.message?.take(60) ?: "请检查地址、网络与证书"}"
                        }
                        Log.w("Wiesler", "Controller /connections failed ($address, http=$httpStatus): ${err.javaClass.simpleName}: ${err.message}")
                        lastControllerErrorAtMs = System.currentTimeMillis()
                        lastControllerErrorMessage = rawMessage
                        appendRequestLog("Mihomo /connections", httpStatus, rawMessage)
                        connectionState = "${httpStatus?.let { "HTTP $it" } ?: "未取得状态码"} · $rawMessage"
                        if (notifyNextControllerError || wasHealthy) {
                            notifyNextControllerSuccess = false
                            notifyNextControllerError = false
                            showTopBanner(
                                title = "控制器连接失败",
                                detail = "${address.removePrefix("http://")} · $rawMessage",
                                tone = BannerTone.ERROR,
                                durationMs = 5000L
                            )
                        }
                        appendSample(mihomoTrend, null)
                        if (studio.live) {
                            studio.accept(emptyList(), SystemClock.elapsedRealtime(), System.currentTimeMillis(), connectionState)
                        }
                        resetForward()
                        if (forwardEnabled) forwardState = connectionState
                    }
                )
                publishUiState()
            }
        }
    }

    private fun refreshEgress() {
        val token = ++egressGeneration
        val useController = controllerEnabled
        val addr = controller
        val sec = secret
        uiState = uiState.copy(egress = uiState.egress.copy(busy = true))
        worker.execute {
            val sysResult = EgressProbe.measure(proxyPort = null)
            val proxyResult: Result<EgressAddress> = if (useController) {
                EgressProbe.fetchMihomoProxyPort(addr, sec).fold(
                    onSuccess = { port -> EgressProbe.measure(proxyPort = port) },
                    onFailure = { Result.failure(it) }
                )
            } else {
                Result.failure(IllegalStateException("未连接控制器"))
            }
            handler.post {
                if (isDestroyed || token != egressGeneration) return@post
                uiState = uiState.copy(
                    egress = EgressUiState(
                        busy = false,
                        system = sysResult.getOrNull(),
                        systemError = sysResult.exceptionOrNull()?.message,
                        proxy = proxyResult.getOrNull(),
                        proxyError = proxyResult.exceptionOrNull()?.message
                    )
                )
            }
        }
    }

    private fun runActivePrivacyInspection() {
        val token = ++privacyRunGeneration
        val started = System.currentTimeMillis()
        val useController = controllerEnabled
        val addr = controller
        val sec = secret
        uiState = uiState.copy(activePrivacyBusy = true)
        worker.execute {
            val dnsResult = runCatching { collectActiveDnsInspection() }
            val sysResult = EgressProbe.measure(proxyPort = null)
            val proxyResult = if (useController) {
                EgressProbe.fetchMihomoProxyPort(addr, sec).fold(
                    onSuccess = { port -> EgressProbe.measure(proxyPort = port) },
                    onFailure = { Result.failure(it) }
                )
            } else {
                Result.failure(IllegalStateException("未连接控制器，代理出口未探测"))
            }
            handler.post {
                if (isDestroyed || token != privacyRunGeneration) return@post
                val run = ActivePrivacyRun(
                    startedAt = started,
                    dns = dnsResult.getOrNull(),
                    dnsError = dnsResult.exceptionOrNull()?.message,
                    systemEgress = sysResult.getOrNull(),
                    systemError = sysResult.exceptionOrNull()?.message,
                    proxyEgress = proxyResult.getOrNull(),
                    proxyError = proxyResult.exceptionOrNull()?.message
                )
                if (dnsResult.isSuccess) lastDnsInspection = dnsResult.getOrNull()
                uiState = uiState.copy(
                    activePrivacyBusy = false,
                    activePrivacyRun = run,
                    egress = EgressUiState(
                        busy = false,
                        system = sysResult.getOrNull() ?: uiState.egress.system,
                        systemError = sysResult.exceptionOrNull()?.message,
                        proxy = proxyResult.getOrNull() ?: uiState.egress.proxy,
                        proxyError = proxyResult.exceptionOrNull()?.message
                    )
                )
                publishUiState()
            }
        }
    }

    private fun collectActiveDnsInspection(): ActiveDnsInspection {
        val active = connectivity.activeNetwork
        val caps = connectivity.getNetworkCapabilities(active)
        val props = connectivity.getLinkProperties(active)
        val dnsServers = props?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        val domain = "example.com"
        val start = SystemClock.elapsedRealtime()
        val resolved = runCatching {
            InetAddress.getAllByName(domain).mapNotNull { it.hostAddress }.distinct()
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        return ActiveDnsInspection(
            interfaceName = props?.interfaceName,
            isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            mtu = props?.mtu?.takeIf { it > 0 },
            privateDnsActive = props?.isPrivateDnsActive == true,
            privateDnsServer = props?.privateDnsServerName,
            dnsServers = dnsServers,
            probeDomain = domain,
            resolvedAddresses = resolved.getOrDefault(emptyList()),
            probeElapsedMs = elapsed,
            probeError = resolved.exceptionOrNull()?.message
        )
    }

    private fun runEnvironmentCheck() {
        val locales = resources.configuration.locales
        val languages = (0 until locales.size()).map { locales[it].toLanguageTag() }
        val dm = resources.displayMetrics
        val props = connectivity.getLinkProperties(connectivity.activeNetwork)
        val privateDns = if (props?.isPrivateDnsActive == true) {
            "已启用${props.privateDnsServerName?.let { "（$it）" } ?: ""}"
        } else "未启用"
        uiState = uiState.copy(
            deviceEnvironment = DeviceEnvironmentInfo(
                languages = languages,
                timezone = TimeZone.getDefault().id,
                screenSummary = "${dm.widthPixels} × ${dm.heightPixels} · ${dm.densityDpi} dpi",
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                privateDnsSummary = privateDns
            )
        )
    }

    private fun generatePrivacyJson(): String {
        val policy = PrivacyPolicy(uiPrefs.expectProxy, uiPrefs.uploadThresholdKib.toLong() * 1024L)
        val report = PrivacyInspector.analyze(studio.current, policy, if (studio.live) "mihomo" else "demo")
        val summary = PrivacyInspector.summarize(report)
        val json = PrivacyInspector.exportJson(
            report,
            summary,
            uiState.activePrivacyRun,
            uiState.deviceEnvironment,
            evaluateRfSnapshot()
        )
        uiState = uiState.copy(exportedPrivacyJson = json)
        return json
    }

    private fun refreshSystemNetwork() {
        refreshSystemProbes(force = true)
        uiState = uiState.copy(
            networkSummary = cachedNetworkSummary,
            systemNetworkSnapshot = cachedDiagnosticText,
            usageAllowed = cachedUsageAllowed
        )
    }

    private fun refreshSystemProbes(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSystemProbeAt < 5000 && cachedDiagnosticText.isNotBlank()) return
        lastSystemProbeAt = now
        cachedSystemLink = collectSystemLinkSnapshot()
        cachedNetworkSummary = networkSummary()
        cachedDiagnosticText = diagnosticText()
        cachedUsageAllowed = usageAllowed()
    }

    private fun collectSystemLinkSnapshot(): SystemLinkSnapshot {
        val active = connectivity.activeNetwork
        val caps = connectivity.getNetworkCapabilities(active)
        val props = connectivity.getLinkProperties(active)
        if (active == null || caps == null) {
            return SystemLinkSnapshot(connected = false, transportLabel = "未连接网络")
        }
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val transportLabel = when {
            isWifi -> "Wi-Fi"
            isCellular -> "移动网络"
            isEthernet -> "以太网"
            else -> "网络已连接"
        }
        val rssi = if (isWifi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps.signalStrength.takeIf { it in -120..-20 }
        } else {
            null
        }
        return SystemLinkSnapshot(
            connected = true,
            transportLabel = transportLabel,
            isWifi = isWifi,
            isCellular = isCellular,
            isEthernet = isEthernet,
            isVpn = isVpn,
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            wifiRssiDbm = rssi,
            downstreamKbps = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 },
            upstreamKbps = caps.linkUpstreamBandwidthKbps.takeIf { it > 0 },
            interfaceName = props?.interfaceName,
            mtu = props?.mtu?.takeIf { it > 0 },
            privateDnsActive = props?.isPrivateDnsActive == true,
            privateDnsServer = props?.privateDnsServerName,
            dnsServers = props?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        )
    }

    private fun importDatabase(uri: Uri) {
        val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "wiesler.sqlite3"
        uiState = uiState.copy(historyBusy = true, historyStatus = "正在校验并读取 $displayName…")
        worker.execute {
            val result = SqliteHistoryReader.importFromUri(applicationContext, uri, displayName)
            handler.post {
                if (isDestroyed) return@post
                result.fold(
                    onSuccess = { info ->
                        uiState = uiState.copy(
                            historyBusy = false,
                            importedDb = info,
                            selectedHistorySession = info.sessions.firstOrNull(),
                            historyStatus = "已验证 Wiesler 证据库：${info.displayName}（${info.sessions.size} 个会话）",
                            historyReportMarkdown = ""
                        )
                    },
                    onFailure = { err ->
                        uiState = uiState.copy(
                            historyBusy = false,
                            historyStatus = "读取失败：${err.message ?: "无效文件"}"
                        )
                    }
                )
            }
        }
    }

    private fun generateHistoryReport() {
        val db = uiState.importedDb ?: return
        val session = uiState.selectedHistorySession ?: return
        uiState = uiState.copy(historyBusy = true, historyStatus = "正在生成会话 ${session.sessionId} 诊断报告…")
        worker.execute {
            val result = SqliteHistoryReader.diagnoseSession(db.localPath, session)
            handler.post {
                if (isDestroyed) return@post
                result.fold(
                    onSuccess = { md ->
                        uiState = uiState.copy(
                            historyBusy = false,
                            historyStatus = "诊断报告已生成",
                            historyReportMarkdown = md
                        )
                    },
                    onFailure = { err ->
                        uiState = uiState.copy(
                            historyBusy = false,
                            historyStatus = "诊断生成失败：${err.message ?: "未知错误"}"
                        )
                    }
                )
            }
        }
    }

    private fun usageAllowed(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun loadUsage() {
        if (usageBusy || !usageAllowed()) return
        usageBusy = true
        uiState = uiState.copy(usageBusy = true, usageError = null)
        worker.execute {
            val result = runCatching {
                val stats = getSystemService(NetworkStatsManager::class.java)
                val start = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val totals = mutableMapOf<Int, LongArray>()
                for (kind in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
                    stats.querySummary(kind, null, start, System.currentTimeMillis()).use { rows ->
                        val bucket = NetworkStats.Bucket()
                        while (rows.hasNextBucket()) {
                            rows.getNextBucket(bucket)
                            val entry = totals.getOrPut(bucket.uid) { longArrayOf(0, 0) }
                            entry[0] += bucket.rxBytes
                            entry[1] += bucket.txBytes
                        }
                    }
                }
                totals.entries
                    .filter { it.value[0] + it.value[1] > 0 }
                    .sortedByDescending { it.value[0] + it.value[1] }
                    .map { entry ->
                        val packages = packageManager.getPackagesForUid(entry.key)
                        val title = packages?.firstOrNull()?.let { pkg ->
                            runCatching {
                                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                            }.getOrDefault(pkg)
                        } ?: "系统 / UID ${entry.key}"
                        AppUsageEntry(title, entry.key, entry.value[0], entry.value[1])
                    }
            }
            handler.post {
                usageBusy = false
                if (isDestroyed) return@post
                result.fold(
                    onSuccess = { entries ->
                        uiState = uiState.copy(usageBusy = false, usageAllowed = true, appUsage = entries, usageError = null)
                    },
                    onFailure = {
                        uiState = uiState.copy(usageBusy = false, usageError = "暂时无法读取应用统计，请检查使用情况访问权限。")
                    }
                )
            }
        }
    }

    private fun stopLoss(message: String) {
        lossRunning = false
        lossGeneration++
        handler.removeCallbacks(lossTick)
        lossProbe?.cancel()
        lossProbe = null
        lossStatus = message
    }

    private fun sampleLoss() {
        if (!foreground || !lossRunning) return
        val generation = lossGeneration
        val address = lossTarget
        val probe = IcmpProbe()
        lossProbe = probe
        lossWorker.execute {
            val result = probe.run(address)
            handler.post {
                if (isDestroyed || !foreground || !lossRunning || generation != lossGeneration) return@post
                lossProbe = null
                lossWindow.add(result)
                timedPings.addLast(TimedPingEvidence(System.currentTimeMillis(), result.outcome, result.rttMs))
                while (timedPings.size > 60) timedPings.removeFirst()
                lossStatus = "$address · ${lossTimeFmt.format(Date())} · ${result.message}"
                publishUiState()
                handler.postDelayed(lossTick, 1000)
            }
        }
    }

    private fun measureHttpsProbe(): Triple<HttpsProbeSnapshot, String, String?> {
        var httpStatus: Int? = null
        var failure: String? = null
        var elapsedMs: Long? = null
        val text = runCatching {
            val connection = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                val start = SystemClock.elapsedRealtime()
                val code = connection.responseCode
                elapsedMs = SystemClock.elapsedRealtime() - start
                httpStatus = code.takeIf { it in 100..599 }
                "HTTP $code · ${elapsedMs} ms\n${if (code == 204) "HTTPS 请求成功" else "未得到预期 204 响应"}（含建连耗时）"
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            failure = if (it is java.net.SocketTimeoutException) "超时" else "网络或 TLS 错误"
            "检查失败：$failure"
        }
        val snapshot = HttpsProbeSnapshot(
            observedAtMs = System.currentTimeMillis(),
            statusCode = httpStatus,
            latencyMs = elapsedMs,
            ok = httpStatus == 204,
            error = failure
        )
        return Triple(snapshot, text, failure)
    }

    private fun runHttpsProbe() {
        if (probeBusy) return
        probeBusy = true
        probeResult = "正在检查…"
        publishUiState()
        worker.execute {
            val (snapshot, resultText, failure) = measureHttpsProbe()
            handler.post {
                probeBusy = false
                probeResult = resultText
                lastHttpsProbe = snapshot
                if (!isDestroyed) {
                    appendRequestLog("HTTPS gstatic 检查", snapshot.statusCode, failure)
                    publishUiState()
                }
            }
        }
    }

    private fun runQualityCheck() {
        if (qualityCheckBusy) return
        val token = ++qualityCheckGeneration
        qualityCheckBusy = true
        refreshSystemProbes(force = true)
        val useController = controllerEnabled
        val addr = controller
        val sec = secret
        val pingTargetSnapshot = lossTarget
        val needQuickBurst = !lossRunning
        uiState = uiState.copy(qualityCheckBusy = true)
        publishUiState()

        // 复用现有 DNS、HTTPS 204、公网出口与 ICMP 探针并发执行一次综合体检，各项完成即增量刷新 UI。
        // Run existing DNS, HTTPS 204, Egress, and ICMP probes concurrently with progressive UI updates.
        val remaining = java.util.concurrent.atomic.AtomicInteger(if (needQuickBurst) 4 else 3)
        fun markDone() {
            if (remaining.decrementAndGet() <= 0) {
                handler.post {
                    if (isDestroyed || token != qualityCheckGeneration) return@post
                    qualityCheckBusy = false
                    refreshSystemProbes(force = true)
                    publishUiState()
                }
            }
        }

        worker.execute {
            val dnsResult = runCatching { collectActiveDnsInspection() }
            handler.post {
                if (!isDestroyed && token == qualityCheckGeneration) {
                    dnsResult.getOrNull()?.let { lastDnsInspection = it }
                    publishUiState()
                }
            }
            markDone()
        }

        worker.execute {
            val (httpsSnap, text, failure) = measureHttpsProbe()
            handler.post {
                if (!isDestroyed && token == qualityCheckGeneration) {
                    lastHttpsProbe = httpsSnap
                    probeResult = text
                    appendRequestLog("HTTPS gstatic 体检", httpsSnap.statusCode, failure)
                    publishUiState()
                }
            }
            markDone()
        }

        worker.execute {
            val sysRes = EgressProbe.measure(proxyPort = null)
            val proxyRes = if (useController) {
                EgressProbe.fetchMihomoProxyPort(addr, sec).fold(
                    onSuccess = { port -> EgressProbe.measure(proxyPort = port) },
                    onFailure = { Result.failure(it) }
                )
            } else {
                Result.failure(IllegalStateException("未连接控制器"))
            }
            handler.post {
                if (!isDestroyed && token == qualityCheckGeneration) {
                    uiState = uiState.copy(
                        egress = EgressUiState(
                            busy = false,
                            system = sysRes.getOrNull() ?: uiState.egress.system,
                            systemError = sysRes.exceptionOrNull()?.message,
                            proxy = proxyRes.getOrNull() ?: uiState.egress.proxy,
                            proxyError = if (useController) proxyRes.exceptionOrNull()?.message else null
                        )
                    )
                    publishUiState()
                }
            }
            markDone()
        }

        if (needQuickBurst) {
            lossWorker.execute {
                repeat(4) { idx ->
                    if (isDestroyed || token != qualityCheckGeneration || lossRunning) return@repeat
                    val probe = IcmpProbe()
                    val sample = probe.run(pingTargetSnapshot)
                    val now = System.currentTimeMillis()
                    handler.post {
                        if (isDestroyed || token != qualityCheckGeneration) return@post
                        lossWindow.add(sample)
                        timedPings.addLast(TimedPingEvidence(now, sample.outcome, sample.rttMs))
                        while (timedPings.size > 60) timedPings.removeFirst()
                        lossStatus = "$pingTargetSnapshot · ${lossTimeFmt.format(Date())} · ${sample.message}"
                        publishUiState()
                    }
                    if (idx < 3 && sample.outcome == "reply") {
                        runCatching { Thread.sleep(220L) }
                    }
                }
                markDone()
            }
        }
    }

    private fun toggleQualityContinuousMonitor(enabled: Boolean) {
        if (enabled) {
            if (!lossRunning) {
                lossGeneration++
                lossRunning = true
                lossStatus = "$lossTarget · 持续监测中…"
                publishUiState()
                sampleLoss()
            }
            if (RfCellularCollector.hasPermissions(this) && !rfEnabled) {
                setRfMonitoringEnabled(true)
            }
        } else {
            if (lossRunning) {
                stopLoss("已停止持续监测 · 保留最后结果")
            }
            publishUiState()
        }
    }

    private fun selectQualityPingTarget(target: String) {
        val clean = target.trim()
        if (!validPingTarget(clean) || clean == lossTarget) return
        lossTarget = clean
        getPreferences(0).edit().putString("lossTarget", clean).apply()
        lossWindow.clear()
        timedPings.clear()
        if (lossRunning) {
            lossGeneration++
            lossStatus = "$clean · 正在探测…"
            publishUiState()
            sampleLoss()
        } else {
            lossStatus = "$clean · 已切换目标"
            publishUiState()
            runQualityCheck()
        }
    }

    private fun appendRequestLog(source: String, code: Int?, error: String?) {
        requestLogs.addFirst(
            RequestLog(sampleDateFmt.format(Date()), source, code, error)
        )
        while (requestLogs.size > 100) requestLogs.removeLast()
        if (!foreground) persistState(async = false)
    }

    private fun networkSummary(): String {
        val active = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val transport = when {
            active?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            active?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "移动网络"
            active?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "以太网"
            active != null -> "网络已连接"
            else -> "未连接网络"
        }
        val vpn = active?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return transport + if (vpn) " / VPN 已启用" else ""
    }

    private fun diagnosticText(): String {
        val active = connectivity.activeNetwork
        val capabilities = connectivity.getNetworkCapabilities(active)
        val properties = connectivity.getLinkProperties(active)
        val verified = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val privateDns = if (properties?.isPrivateDnsActive == true) {
            "已启用${properties.privateDnsServerName?.let { "（$it）" } ?: ""}"
        } else "未启用"
        return "网络：${networkSummary()}\n系统联网验证：${if (verified) "已通过" else "未通过 / 未知"}\n接口：${properties?.interfaceName ?: "未知"}\n本地地址：${properties?.linkAddresses?.joinToString() ?: "未知"}\nDNS：${properties?.dnsServers?.joinToString { it.hostAddress ?: "未知" } ?: "未知"}\nPrivate DNS：$privateDns\nMTU：${properties?.mtu ?: "未知"}"
    }

    private fun setRfMonitoringEnabled(enabled: Boolean) {
        if (enabled && !RfCellularCollector.hasPermissions(this)) {
            rfPermissionLauncher.launch(RfCellularCollector.REQUIRED_PERMISSIONS)
            return
        }
        rfEnabled = enabled
        getPreferences(0).edit().putBoolean("rfEnabled", enabled).apply()
        rfStatusOverride = null
        if (enabled) {
            if (foreground) rfCollector?.start()
        } else {
            rfCollector?.stop()
        }
        publishUiState()
    }

    private fun evaluateRfSnapshot(): RfDiagnosticsSnapshot {
        val nowMs = System.currentTimeMillis()
        val egressState = uiState.egress
        val link = RfLinkEvidence(
            nowMs = nowMs,
            recentPings = timedPings.toList(),
            controllerEnabled = controllerEnabled,
            controllerHealthy = controllerHealthy,
            lastControllerErrorAtMs = lastControllerErrorAtMs,
            lastControllerError = lastControllerErrorMessage,
            cellularValidated = cellularValidated,
            cellularLostAtMs = cellularLostAtMs,
            systemEgressOk = when {
                egressState.system != null -> true
                egressState.systemError != null -> false
                else -> null
            },
            proxyEgressOk = when {
                egressState.proxy != null -> true
                egressState.proxyError != null && controllerEnabled -> false
                else -> null
            }
        )
        return rfEngine.evaluate(
            nowMs = nowMs,
            active = rfEnabled && (rfCollector?.isRunning == true),
            permissionGranted = RfCellularCollector.hasPermissions(this),
            statusOverride = rfStatusOverride,
            linkEvidence = link
        )
    }

    private fun shareTextReport() {
        val sysIp = uiState.egress.system?.let { "${it.ip}（${it.location?.summary() ?: "位置未知"}）" } ?: "未探测"
        val proxyIp = uiState.egress.proxy?.let { "${it.ip}（${it.location?.summary() ?: "位置未知"}）" } ?: "未探测"
        val rfSummary = evaluateRfSnapshot().toSummaryText()
        val report = "Wiesler Android 0.1.1\n${Date()}\n${diagnosticText()}\n系统公网出口：$sysIp\n代理公网出口：$proxyIp\nICMP 目标：$lossTarget\n${lossWindow.text()}\n$lossStatus\nHTTPS：$probeResult\n系统接口累计 RX=${formatBytes(lastRx.toDouble())} TX=${formatBytes(lastTx.toDouble())}\nVPN 下系统接口可能重复计量。\n\n$rfSummary\n\n最近请求日志\n${requestLogs.joinToString("\n") { it.text() }}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
        }, "分享诊断报告"))
    }

    private fun publishUiState() {
        refreshSystemProbes(force = false)
        val tab = uiState.activeTab
        val currentFrame = studio.current
        // 仅在隐私页按需重新分析 400+ 连接，避免非隐私页每秒重复扫描。
        // Recompute privacy analysis lazily when viewing the Privacy tab or when frame/policy changes.
        if (tab == WieslerTab.PRIVACY) {
            val policy = PrivacyPolicy(uiPrefs.expectProxy, uiPrefs.uploadThresholdKib.toLong() * 1024L)
            if (currentFrame !== cachedPrivacyFrame || policy != cachedPrivacyPolicy || cachedPrivacyReport == null) {
                val report = PrivacyInspector.analyze(currentFrame, policy, if (studio.live) "mihomo" else "demo")
                cachedPrivacyFrame = currentFrame
                cachedPrivacyPolicy = policy
                cachedPrivacyReport = report
                cachedPrivacySummary = PrivacyInspector.summarize(report)
            }
        }
        val candidates = if (overseasExits.isEmpty()) availableExitsCache else (availableExitsCache + overseasExits).distinct().sorted()
        val frameIndex = if (studio.held == null) {
            (studio.frames.size - 1).coerceAtLeast(0)
        } else {
            studio.frames.indexOf(studio.held).coerceAtLeast(0)
        }
        // 仅在图谱页计算筛选列表、分组与前 60 条可见会话的速率微折线，避开全量 60×500 循环。
        // Compute filtered flows, host groups, and visible-slice sparklines only on the Graph tab.
        val filteredFlows: List<StudioFlow>
        val hostGroups: List<StudioHostGroup>
        val rateHist: Map<String, List<Rate?>>
        if (tab == WieslerTab.GRAPH) {
            filteredFlows = studio.filtered()
            hostGroups = studio.graphHostGroups(filteredFlows)
            val visibleKeys = filteredFlows.take(60).mapTo(HashSet()) { it.flowKey }
            rateHist = studio.rateHistory(filterKeys = visibleKeys)
        } else {
            filteredFlows = uiState.studioFilteredFlows
            hostGroups = uiState.studioHostGroups
            rateHist = uiState.studioRateHistory
        }
        val rfSnap = evaluateRfSnapshot()
        val nowWallMs = System.currentTimeMillis()
        val qualityInput = NetworkQualityInput(
            nowMs = nowWallMs,
            link = cachedSystemLink,
            pingTarget = lossTarget,
            pingRunning = lossRunning,
            recentPings = timedPings.toList(),
            lossPercent = lossWindow.lossPercent,
            averageRttMs = lossWindow.averageRtt,
            pingSent = lossWindow.sent,
            pingReceived = lossWindow.received,
            pingErrors = lossWindow.errors,
            dnsInspection = lastDnsInspection,
            httpsProbe = lastHttpsProbe,
            systemEgress = uiState.egress.system,
            systemEgressError = uiState.egress.systemError,
            proxyEgress = uiState.egress.proxy,
            proxyEgressError = uiState.egress.proxyError,
            rfDiagnostics = rfSnap,
            controllerEnabled = controllerEnabled,
            controllerHealthy = controllerHealthy,
            activeFlowCount = currentFrame?.flows?.size,
            directFlowCount = currentFrame?.flows?.count { it.routing == FlowRouting.DIRECT } ?: 0,
            proxyFlowCount = currentFrame?.flows?.count { it.routing == FlowRouting.PROXY } ?: 0,
            globalRate = lastRate,
            recentRequestLogs = requestLogs.take(20)
        )
        val evaluatedQuality = NetworkQualityEngine.evaluate(qualityInput, qualityHistory.snapshot())
        if (foreground) {
            qualityHistory.record(evaluatedQuality, lossWindow.averageRtt, lossWindow.lossPercent)
        }
        val qualitySnap = evaluatedQuality.copy(history = qualityHistory.snapshot())
        uiState = uiState.copy(
            preferences = uiPrefs,
            systemLink = cachedSystemLink,
            lastDnsInspection = lastDnsInspection,
            lastHttpsProbe = lastHttpsProbe,
            qualitySnapshot = qualitySnap,
            qualityCheckBusy = qualityCheckBusy,
            controllerAddress = controller,
            controllerEnabled = controllerEnabled,
            controllerHealthy = controllerHealthy,
            hasSecret = secret.isNotEmpty(),
            connectionState = connectionState,
            capturePaused = capturePaused,
            studioLive = studio.live,
            studioFrozen = studio.held != null,
            studioFrameCount = studio.frames.size,
            studioFrameIndex = frameIndex,
            studioCurrentFrame = currentFrame,
            studioFilteredFlows = filteredFlows,
            studioHostGroups = hostGroups,
            studioSelectedId = studio.selectedId,
            studioQuery = studio.query,
            studioProtocol = studio.protocol,
            studioSourceFilter = studio.sourceFilter,
            studioGraphMode = studio.graphMode,
            studioRateHistory = rateHist,
            mihomoTrend = if (tab == WieslerTab.MONITOR) mihomoTrend.toList() else uiState.mihomoTrend,
            globalEnabled = !paused,
            globalRate = lastRate,
            globalTrend = if (tab == WieslerTab.MONITOR || tab == WieslerTab.HISTORY) trend.toList() else uiState.globalTrend,
            globalSampledAt = sampledAt,
            bootRxBytes = lastRx,
            bootTxBytes = lastTx,
            networkSummary = cachedNetworkSummary,
            forwardEnabled = forwardEnabled,
            overseasExits = overseasExits,
            availableExits = candidates,
            forwardRate = forwardRate,
            forwardTrend = if (tab == WieslerTab.MONITOR) forwardTrend.toList() else uiState.forwardTrend,
            forwardState = forwardState,
            forwardObservedRx = forwardRates.observedRx,
            forwardObservedTx = forwardRates.observedTx,
            packetLoss = PacketLossUiState(
                target = lossTarget,
                running = lossRunning,
                status = lossStatus,
                lossPercent = lossWindow.lossPercent,
                averageRtt = lossWindow.averageRtt,
                sent = lossWindow.sent,
                received = lossWindow.received,
                errors = lossWindow.errors
            ),
            rfDiagnostics = rfSnap,
            usageAllowed = cachedUsageAllowed,
            systemNetworkSnapshot = cachedDiagnosticText,
            httpsProbeBusy = probeBusy,
            httpsProbeResult = probeResult,
            requestLogs = if (tab == WieslerTab.MONITOR || tab == WieslerTab.HISTORY) requestLogs.toList() else uiState.requestLogs,
            privacyReport = cachedPrivacyReport,
            privacySummary = cachedPrivacySummary
        )
    }
}
