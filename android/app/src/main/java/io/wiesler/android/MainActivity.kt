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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.wiesler.android.ui.AppUsageEntry
import io.wiesler.android.ui.EgressUiState
import io.wiesler.android.ui.PacketLossUiState
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

    private var foreground = false
    private var paused = false
    private var capturePaused = false
    private var forwardEnabled = false
    private var overseasExits = emptySet<String>()
    private var controller = "http://127.0.0.1:9090"
    private var secret = ""
    private var controllerEnabled = false
    private var controllerBusy = false
    private var controllerGeneration = 0
    private var connectionState = "尚未连接手机控制器"
    private var connections = emptyList<JSONObject>()
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
    private var uiPrefs = AppUiPreferences()

    private var uiState by mutableStateOf(WieslerUiState())
    private val connectivity get() = getSystemService(ConnectivityManager::class.java)
    private val lossTick = Runnable { sampleLoss() }

    private val openDbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importDatabase(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getPreferences(0)
        uiPrefs = AppUiPreferencesStore.read(prefs)
        val savedTabIndex = savedInstanceState?.getInt("tab") ?: prefs.getInt("ui_tab", 0)
        val initialTab = WieslerTab.entries.firstOrNull { it.index == savedTabIndex } ?: WieslerTab.GRAPH
        paused = savedInstanceState?.getBoolean("paused") ?: prefs.getBoolean("paused", false)
        lossTarget = prefs.getString("lossTarget", "1.1.1.1") ?: "1.1.1.1"
        controller = prefs.getString("controller", controller) ?: controller
        overseasExits = prefs.getStringSet("overseasExits", emptySet())?.toSet() ?: emptySet()
        studio.overseasExits = overseasExits
        forwardEnabled = prefs.getBoolean("forwardEnabled", false)
        controllerEnabled = prefs.getBoolean("controllerEnabled", prefs.contains("controller"))
        secret = runCatching { ControllerSecretStore(prefs).read(controller) }.getOrElse {
            controllerEnabled = false
            Toast.makeText(this, "无法解密控制器密钥，请重新配置", Toast.LENGTH_LONG).show()
            ""
        }
        if (controllerEnabled) connectionState = "等待重新连接"
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
        if (uiState.activeTab == WieslerTab.MONITOR && usageAllowed()) loadUsage()
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
        persistState()
        super.onPause()
    }

    override fun onDestroy() {
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
            if (tab == WieslerTab.MONITOR && usageAllowed() && uiState.appUsage.isEmpty()) loadUsage()
            if (tab == WieslerTab.PRIVACY) refreshSystemNetwork()
            persistState()
            publishUiState()
        },
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
        onSetScopeDialog = { open -> uiState = uiState.copy(showScopeDialog = open) }
    )

    private val tick = object : Runnable {
        override fun run() {
            if (!foreground) return
            if (!paused) {
                lastRx = android.net.TrafficStats.getTotalRxBytes()
                lastTx = android.net.TrafficStats.getTotalTxBytes()
                lastRate = rates.accept(CounterSample(SystemClock.elapsedRealtime(), lastRx, lastTx))
                appendSample(trend, lastRate)
                sampledAt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            }
            if (!capturePaused) {
                if (uiState.activeTab == WieslerTab.GRAPH && !studio.live) sampleDemo()
                val needsController = forwardEnabled || uiState.activeTab in setOf(WieslerTab.GRAPH, WieslerTab.MONITOR, WieslerTab.PRIVACY)
                if (controllerEnabled && needsController && !controllerBusy) fetchConnections()
            }
            if (SystemClock.elapsedRealtime() - lastPersistedAt >= 5000) persistState()
            publishUiState()
            handler.postDelayed(this, 1000)
        }
    }

    private fun updatePrefs(next: AppUiPreferences) {
        uiPrefs = next
        AppUiPreferencesStore.write(getPreferences(0), next)
        publishUiState()
    }

    private fun persistState() {
        val history = SavedHistory(
            trend.toList(),
            forwardTrend.toList(),
            forwardRates.observedRx,
            forwardRates.observedTx,
            requestLogs.toList(),
            sampledAt,
            forwardSampledAt
        )
        getPreferences(0).edit()
            .putBoolean("studioAvailable", true)
            .putInt("ui_tab", uiState.activeTab.index)
            .putBoolean("paused", paused)
            .putBoolean("forwardEnabled", forwardEnabled)
            .putStringSet("overseasExits", overseasExits)
            .putString("history", history.encode())
            .apply()
        lastPersistedAt = SystemClock.elapsedRealtime()
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
        }.getOrDefault(false)
        if (!saved) return "无法安全保存连接设置，请重试"
        controller = address
        secret = effectiveSecret
        controllerEnabled = true
        capturePaused = false
        controllerGeneration++
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
        connections = emptyList()
        connectionState = "连接中…"
        persistState()
        publishUiState()
        if (!controllerBusy) fetchConnections()
        refreshEgress()
        return null
    }

    private fun disconnectController() {
        if (!runCatching { ControllerSecretStore(getPreferences(0)).save(controller, "", false) }.getOrDefault(false)) {
            Toast.makeText(this, "断开设置保存失败，请重试", Toast.LENGTH_LONG).show()
            return
        }
        controllerGeneration++
        controllerEnabled = false
        secret = ""
        connections = emptyList()
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
        uiState = uiState.copy(discoveringControllers = true, discoveryStatus = "正在并发扫描回环端口…")
        val extras = listOf(controller)
        worker.execute {
            val report = ControllerDiscovery.scanLocal(extras)
            handler.post {
                if (isDestroyed) return@post
                val msg = when {
                    report.controllers.isEmpty() -> "已扫描 ${report.scannedPorts} 个本机端口，未发现可用 Mihomo 控制器"
                    report.controllers.size == 1 -> "发现 1 个控制器（扫描 ${report.scannedPorts} 端口），可点击右侧直接连接"
                    else -> "发现 ${report.controllers.size} 个控制器，请选择其中一个连接"
                }
                uiState = uiState.copy(
                    discoveringControllers = false,
                    discoveredControllers = report.controllers,
                    discoveryStatus = msg
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
        controllerBusy = true
        val generation = controllerGeneration
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
                    val list = (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }
                    val downTotal = json.optLong("downloadTotal", -1L).takeIf { it >= 0 }
                    val upTotal = json.optLong("uploadTotal", -1L).takeIf { it >= 0 }
                    Triple(SystemClock.elapsedRealtime(), list, Pair(downTotal, upTotal))
                } finally {
                    connection.disconnect()
                }
            }
            handler.post {
                controllerBusy = false
                if (isDestroyed || !foreground || generation != controllerGeneration) return@post
                result.fold(
                    onSuccess = { (time, items, totals) ->
                        connections = items
                        val parsedFlows = items.map(::parseStudioFlow)
                        if (studio.live) {
                            studio.accept(
                                flows = parsedFlows,
                                time = time,
                                wallTime = System.currentTimeMillis(),
                                downloadTotal = totals.first,
                                uploadTotal = totals.second
                            )
                            val rxT = studio.frames.lastOrNull()?.rxTotal
                            val txT = studio.frames.lastOrNull()?.txTotal
                            appendSample(mihomoTrend, if (rxT != null && txT != null) Rate(rxT, txT) else null)
                        }
                        forwardSampledAt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())
                        connectionState = "已连接 · HTTP $httpStatus · ${items.size} 条连接 · $forwardSampledAt"
                        appendRequestLog("Mihomo /connections", httpStatus, null)
                        if (forwardEnabled) {
                            val counters = items.map(::routedCounter)
                            forwardRate = forwardRates.accept(time, counters, overseasExits)
                            appendSample(forwardTrend, forwardRate)
                            val count = counters.count { trafficRoute(it.exit, overseasExits) == TrafficRoute.OVERSEAS }
                            forwardState = when {
                                overseasExits.isEmpty() -> "请选择海外出口"
                                count == 0 && items.isNotEmpty() -> "所选出口未匹配当前连接，请核对节点名称"
                                forwardRate == null -> "等待连续采样 · $forwardSampledAt"
                                else -> "$count 条转发连接 · $forwardSampledAt"
                            }
                        }
                    },
                    onFailure = { err ->
                        connections = emptyList()
                        val rawMessage = when (err) {
                            is java.net.ConnectException -> "无法连接，请确认手机控制器已开启"
                            is java.net.SocketTimeoutException -> "连接超时，正在重试"
                            is IllegalStateException -> err.message ?: "快照无效"
                            else -> "读取失败，请检查地址、网络与证书"
                        }
                        appendRequestLog("Mihomo /connections", httpStatus, rawMessage)
                        connectionState = "${httpStatus?.let { "HTTP $it" } ?: "未取得状态码"} · $rawMessage"
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
        val json = PrivacyInspector.exportJson(report, summary, uiState.activePrivacyRun, uiState.deviceEnvironment)
        uiState = uiState.copy(exportedPrivacyJson = json)
        return json
    }

    private fun refreshSystemNetwork() {
        uiState = uiState.copy(systemNetworkSnapshot = diagnosticText())
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
                lossStatus = "$address · ${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())} · ${result.message}"
                publishUiState()
                handler.postDelayed(lossTick, 1000)
            }
        }
    }

    private fun runHttpsProbe() {
        if (probeBusy) return
        probeBusy = true
        probeResult = "正在检查…"
        publishUiState()
        worker.execute {
            var httpStatus: Int? = null
            var failure: String? = null
            val result = runCatching {
                val connection = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    val start = SystemClock.elapsedRealtime()
                    val code = connection.responseCode
                    httpStatus = code.takeIf { it in 100..599 }
                    "HTTP $code · ${SystemClock.elapsedRealtime() - start} ms\n${if (code == 204) "HTTPS 请求成功" else "未得到预期 204 响应"}（含建连耗时）"
                } finally {
                    connection.disconnect()
                }
            }.getOrElse {
                failure = if (it is java.net.SocketTimeoutException) "超时" else "网络或 TLS 错误"
                "检查失败：$failure"
            }
            handler.post {
                probeBusy = false
                probeResult = result
                if (!isDestroyed) {
                    appendRequestLog("HTTPS gstatic 检查", httpStatus, failure)
                    publishUiState()
                }
            }
        }
    }

    private fun appendRequestLog(source: String, code: Int?, error: String?) {
        requestLogs.addFirst(
            RequestLog(SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date()), source, code, error)
        )
        while (requestLogs.size > 100) requestLogs.removeLast()
        if (!foreground) persistState()
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

    private fun shareTextReport() {
        val sysIp = uiState.egress.system?.let { "${it.ip}（${it.location?.summary() ?: "位置未知"}）" } ?: "未探测"
        val proxyIp = uiState.egress.proxy?.let { "${it.ip}（${it.location?.summary() ?: "位置未知"}）" } ?: "未探测"
        val report = "Wiesler Android 0.1.1\n${Date()}\n${diagnosticText()}\n系统公网出口：$sysIp\n代理公网出口：$proxyIp\nICMP 目标：$lossTarget\n${lossWindow.text()}\n$lossStatus\nHTTPS：$probeResult\n系统接口累计 RX=${formatBytes(lastRx.toDouble())} TX=${formatBytes(lastTx.toDouble())}\nVPN 下系统接口可能重复计量。\n\n最近请求日志\n${requestLogs.joinToString("\n") { it.text() }}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
        }, "分享诊断报告"))
    }

    private fun publishUiState() {
        val policy = PrivacyPolicy(uiPrefs.expectProxy, uiPrefs.uploadThresholdKib.toLong() * 1024L)
        val privacyReport = PrivacyInspector.analyze(studio.current, policy, if (studio.live) "mihomo" else "demo")
        val privacySummary = PrivacyInspector.summarize(privacyReport)
        val candidates = (connections.mapNotNull { routedCounter(it).exit }.filter(::eligibleExit) + overseasExits).distinct().sorted()
        val frameIndex = if (studio.held == null) {
            (studio.frames.size - 1).coerceAtLeast(0)
        } else {
            studio.frames.indexOf(studio.held).coerceAtLeast(0)
        }
        uiState = uiState.copy(
            preferences = uiPrefs,
            controllerAddress = controller,
            controllerEnabled = controllerEnabled,
            hasSecret = secret.isNotEmpty(),
            connectionState = connectionState,
            capturePaused = capturePaused,
            studioLive = studio.live,
            studioFrozen = studio.held != null,
            studioFrameCount = studio.frames.size,
            studioFrameIndex = frameIndex,
            studioCurrentFrame = studio.current,
            studioFilteredFlows = studio.filtered(),
            studioHostGroups = studio.graphHostGroups(),
            studioSelectedId = studio.selectedId,
            studioQuery = studio.query,
            studioProtocol = studio.protocol,
            studioSourceFilter = studio.sourceFilter,
            studioRateHistory = studio.rateHistory(),
            mihomoTrend = mihomoTrend.toList(),
            globalEnabled = !paused,
            globalRate = lastRate,
            globalTrend = trend.toList(),
            globalSampledAt = sampledAt,
            bootRxBytes = lastRx,
            bootTxBytes = lastTx,
            networkSummary = networkSummary(),
            forwardEnabled = forwardEnabled,
            overseasExits = overseasExits,
            availableExits = candidates,
            forwardRate = forwardRate,
            forwardTrend = forwardTrend.toList(),
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
            usageAllowed = usageAllowed(),
            systemNetworkSnapshot = diagnosticText(),
            httpsProbeBusy = probeBusy,
            httpsProbeResult = probeResult,
            requestLogs = requestLogs.toList(),
            privacyReport = privacyReport,
            privacySummary = privacySummary
        )
    }
}
