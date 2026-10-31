package io.wiesler.android

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.*
import android.os.*
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(16, 21, 25)
    private val surface = Color.rgb(24, 31, 36)
    private val line = Color.rgb(43, 54, 61)
    private val ink = Color.rgb(233, 240, 241)
    private val muted = Color.rgb(143, 161, 171)
    private val mint = Color.rgb(152, 228, 206)
    private val violet = Color.rgb(174, 163, 236)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val rates = RateTracker()
    private val trend = ArrayList<Rate?>()
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var scroll: ScrollView
    private var page = 0
    private var foreground = false
    private var paused = false
    private var lastRate: Rate? = null
    private var lastRx = -1L
    private var lastTx = -1L
    private var sampledAt = "尚未采样"
    private var download: TextView? = null
    private var upload: TextView? = null
    private var status: TextView? = null
    private var networkText: TextView? = null
    private var totalText: TextView? = null
    private var chart: TrendView? = null
    private var usageRows: LinearLayout? = null
    private var connectionRows: LinearLayout? = null
    private var controllerStatus: TextView? = null
    private var controller = "http://127.0.0.1:9090"
    private var secret = ""
    private var controllerEnabled = false
    private var controllerBusy = false
    private var controllerGeneration = 0
    private var connectionState = "尚未连接手机控制器"
    private var connections = emptyList<JSONObject>()
    private var query = ""
    private var protocol = "全部"
    private var usageBusy = false
    private var probeBusy = false
    private var probeResult = "按需检查 HTTPS 连通性"
    private var probeLabel: TextView? = null
    private val connectivity get() = getSystemService(ConnectivityManager::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getInt("page") ?: 0
        paused = savedInstanceState?.getBoolean("paused") ?: false
        controller = getPreferences(0).getString("controller", controller) ?: controller
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val top = row().apply { setPadding(dp(23), dp(16), dp(23), dp(12)) }
        top.addView(label("W", 22, bg, true).apply {
            gravity = Gravity.CENTER; background = shape(mint, 12)
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        top.addView(label("Wiesler", 23, ink, true).apply { setPadding(dp(11), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = column().apply { setPadding(dp(22), dp(16), dp(22), dp(20)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = row().apply { setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(surface) }
        root.addView(nav)
        setContentView(root)
        render()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("page", page); out.putBoolean("paused", paused)
        super.onSaveInstanceState(out)
    }

    override fun onResume() {
        super.onResume(); foreground = true; rates.reset(); trend.add(null)
        handler.removeCallbacks(tick); handler.post(tick)
        if (page == 1) showApps()
    }

    override fun onPause() {
        foreground = false; controllerGeneration++; handler.removeCallbacks(tick); rates.reset()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow(); handler.removeCallbacksAndMessages(null); secret = ""
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!foreground) return
            if (!paused) {
                lastRx = TrafficStats.getTotalRxBytes(); lastTx = TrafficStats.getTotalTxBytes()
                lastRate = rates.accept(CounterSample(SystemClock.elapsedRealtime(), lastRx, lastTx))
                trend.add(lastRate); if (trend.size > 60) trend.removeAt(0)
                sampledAt = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
                updateOverview()
                if (controllerEnabled && !controllerBusy) fetchConnections()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun shape(color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (color == surface) setStroke(dp(1), line)
    }
    private fun label(value: String, size: Int = 14, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun gap(parent: LinearLayout = body, size: Int = 16) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(size))) }
    private fun card(parent: LinearLayout = body): LinearLayout = column().apply {
        background = shape(surface); setPadding(dp(18), dp(18), dp(18), dp(18))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (primary) bg else mint); background = shape(if (primary) mint else Color.rgb(34, 51, 48), 12)
        setPadding(dp(16), dp(13), dp(16), dp(13)); minHeight = dp(48)
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }
    private fun heading(kicker: String, title: String, subtitle: String) {
        body.addView(label(title, 30, ink, true)); gap(size = 5)
        body.addView(label(subtitle, 14, muted)); gap(size = 24)
    }
    private fun render() {
        body.removeAllViews(); nav.removeAllViews()
        download = null; upload = null; chart = null; status = null; networkText = null; totalText = null
        usageRows = null; connectionRows = null; controllerStatus = null; probeLabel = null
        listOf("◉\n概览", "▦\n应用", "⇄\n连接", "◎\n诊断").forEachIndexed { index, title ->
            nav.addView(label(title, 13, if (page == index) mint else muted, page == index).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)); minHeight = dp(56)
                if (page == index) background = shape(Color.rgb(34, 51, 48), 14)
                isClickable = true; isFocusable = true
                setOnClickListener { page = index; render(); scroll.scrollTo(0, 0) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        when (page) { 0 -> showOverview(); 1 -> showApps(); 2 -> showConnections(); 3 -> showDiagnostics() }
    }

    private fun showOverview() {
        heading("YOUR NETWORK, IN FOCUS", "手机网络", "此刻的流量与连接")
        val live = row()
        status = label("●  实时观察", 14, mint)
        live.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        live.addView(button(if (paused) "继续观察" else "暂停") {
            paused = !paused; controllerGeneration++; rates.reset(); trend.add(null); render()
        })
        body.addView(live); gap()
        val metrics = row()
        fun metric(title: String, color: Int): TextView {
            val block = column().apply { background = shape(surface); setPadding(dp(16), dp(20), dp(12), dp(18)) }
            metrics.addView(block, LinearLayout.LayoutParams(0, -2, 1f).apply { if (metrics.childCount == 1) marginEnd = dp(10) })
            block.addView(label(title, 13, color)); gap(block, 14)
            val value = label("—", 26, ink, true); block.addView(value)
            return value
        }
        download = metric("↓  下载速率", mint); upload = metric("↑  上传速率", violet)
        body.addView(metrics); gap()
        val graphCard = card()
        val graphTitle = row()
        graphTitle.addView(label("流量趋势", 15, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        graphTitle.addView(label("● 下载  ", 12, mint)); graphTitle.addView(label("● 上传", 12, violet))
        graphCard.addView(graphTitle); gap(graphCard, 12)
        chart = TrendView(this); graphCard.addView(chart, LinearLayout.LayoutParams(-1, dp(140)))
        val times = row(); times.addView(label("最近一分钟", 12, muted), LinearLayout.LayoutParams(0, -2, 1f)); times.addView(label("现在", 12, muted)); graphCard.addView(times)
        val networkCard = card()
        networkCard.addView(label("当前网络", 14, muted)); gap(networkCard, 8)
        networkText = label("读取中", 18, ink, true); networkCard.addView(networkText)
        gap(networkCard, 14); totalText = label("", 14, muted); networkCard.addView(totalText)
        networkCard.setOnClickListener { showScope() }
        updateOverview()
    }

    private fun bytes(value: Double): String {
        if (!value.isFinite() || value < 0) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var n = value; var i = 0
        while (n >= 1024 && i < 4) { n /= 1024; i++ }
        return if (i == 0) "${n.toLong()} ${units[i]}" else String.format(Locale.ROOT, "%.1f %s", n, units[i])
    }
    private fun networkSummary(): String {
        val active = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        // 按默认网络识别上网类型，排除热点局域网。 / Use the default route, not hotspot interfaces.
        val transport = when {
            active?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            active?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "移动网络"
            active?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "以太网"
            active != null -> "网络已连接"
            else -> "未连接网络"
        }
        val vpn = active?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return transport + if (vpn) "  /  VPN 已启用" else ""
    }
    private fun updateOverview() {
        download?.text = lastRate?.let { bytes(it.rx) + "/s" } ?: "—"
        upload?.text = lastRate?.let { bytes(it.tx) + "/s" } ?: "—"
        status?.text = if (paused) "Ⅱ  已暂停 · $sampledAt" else "●  实时观察 · $sampledAt"
        networkText?.text = networkSummary()
        totalText?.text = "本次开机累计\n↓ ${bytes(lastRx.toDouble())}    ↑ ${bytes(lastTx.toDouble())}"
        chart?.invalidate()
    }

    private fun usageAllowed(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }
    private fun showApps() {
        if (page != 1) return
        body.removeAllViews()
        heading("APPLICATION ACTIVITY", "流量花在哪里", "今天 00:00 起 · Wi-Fi 与移动网络")
        if (!usageAllowed()) {
            val box = card(); box.addView(label("开启应用流量统计", 20, ink, true)); gap(box)
            box.addView(label("授权使用情况访问后，查看各应用的下载、上传和流量排行。", 14, muted)); gap(box)
            box.addView(button("前往系统授权", true) {
                try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }); return
        }
        body.addView(button("刷新统计") { loadUsage() }); gap()
        usageRows = column(); body.addView(usageRows)
        loadUsage()
    }
    private fun loadUsage() {
        if (usageBusy || !usageAllowed()) return
        usageBusy = true
        usageRows?.removeAllViews(); usageRows?.addView(label("正在读取系统账本…", 14, muted))
        worker.execute {
            val result = runCatching {
                val stats = getSystemService(NetworkStatsManager::class.java)
                val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                val totals = mutableMapOf<Int, LongArray>()
                for (kind in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
                    stats.querySummary(kind, null, start, System.currentTimeMillis()).use { rows ->
                        val bucket = NetworkStats.Bucket()
                        while (rows.hasNextBucket()) {
                            rows.getNextBucket(bucket)
                            val entry = totals.getOrPut(bucket.uid) { longArrayOf(0, 0) }
                            entry[0] += bucket.rxBytes; entry[1] += bucket.txBytes
                        }
                    }
                }
                totals.entries.sortedByDescending { it.value[0] + it.value[1] }.filter { it.value.sum() > 0 }.map { entry ->
                    val packages = packageManager.getPackagesForUid(entry.key)
                    val title = packages?.firstOrNull()?.let { pkg -> runCatching {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg) } ?: "系统 / UID ${entry.key}"
                    Triple(title, entry.key, entry.value)
                }
            }
            handler.post {
                usageBusy = false
                if (isDestroyed || page != 1) return@post
                usageRows?.removeAllViews()
                result.fold(onSuccess = { entries ->
                    if (entries.isEmpty()) usageRows?.addView(label("系统暂未提供今天的流量记录。", 14, muted))
                    entries.forEachIndexed { index, entry ->
                        val parent = usageRows ?: return@forEachIndexed
                        val box = card(parent); val head = row()
                        head.addView(label(String.format(Locale.ROOT, "%02d", index + 1), 12, mint), LinearLayout.LayoutParams(dp(34), -2))
                        head.addView(label(entry.first, 16, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
                        head.addView(label(bytes(entry.third.sum().toDouble()), 14, mint)); box.addView(head); gap(box, 8)
                        box.addView(label("↓ ${bytes(entry.third[0].toDouble())}    ↑ ${bytes(entry.third[1].toDouble())}", 14, muted))
                        box.setOnClickListener { AlertDialog.Builder(this).setTitle(entry.first).setMessage("UID ${entry.second}\n系统统计可能延迟；共享 UID 合并显示。").setPositiveButton("知道了", null).show() }
                    }
                }, onFailure = { usageRows?.addView(label("暂时无法读取应用统计，请检查使用情况访问权限。", 14, muted)) })
            }
        }
    }

    private fun showConnections() {
        heading("CONNECTION OBSERVER", "连接观察", "手机 Mihomo 的目标、协议与代理链")
        val source = card()
        source.addView(label("Mihomo 控制器", 15, ink, true)); gap(source, 7)
        controllerStatus = label(connectionState, 14, muted); source.addView(controllerStatus); gap(source)
        source.addView(button("配置手机控制器") { configureController() })
        val search = EditText(this).apply {
            hint = "搜索目标、应用、规则…"; setTextColor(ink); setHintTextColor(muted); textSize = 14f
            isSingleLine = true; setText(query); setPadding(dp(14), dp(10), dp(14), dp(10)); background = shape(surface, 12)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); renderConnections() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        body.addView(search); gap()
        val filters = row()
        listOf("全部", "TCP", "UDP").forEach { value -> filters.addView(button(value, protocol == value) { protocol = value; render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }) }
        body.addView(filters); gap()
        connectionRows = column(); body.addView(connectionRows); renderConnections()
    }

    private fun configureController() {
        val content = column().apply { setPadding(dp(24), dp(12), dp(24), 0) }
        content.addView(label("填写手机 Clash Meta 中的 External Controller 地址。", 13, muted))
        val address = EditText(this).apply { setText(controller); hint = "http://127.0.0.1:9090"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true }
        val password = EditText(this).apply { hint = "Secret（如有，仅本次使用）"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSingleLine = true; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO }
        content.addView(address); content.addView(password)
        val dialog = AlertDialog.Builder(this).setTitle("连接手机控制器").setView(content).setNegativeButton("取消", null).setNeutralButton("断开") { _, _ ->
            controllerGeneration++; controllerEnabled = false; secret = ""; connections = emptyList(); connectionState = "已断开"; render()
        }.setPositiveButton("连接", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = address.text.toString().trim().trimEnd('/')
                val uri = runCatching { URI(value) }.getOrNull()
                val loopback = uri?.host in listOf("127.0.0.1", "localhost", "[::1]")
                if (uri == null || uri.host == null || uri.userInfo != null || uri.query != null || uri.fragment != null || !uri.path.isNullOrEmpty() || (uri.scheme != "https" && !(uri.scheme == "http" && loopback))) {
                    address.error = "使用本机 HTTP 或 HTTPS 根地址，不包含密钥"; return@setOnClickListener
                }
                controller = value; secret = password.text.toString(); controllerEnabled = true; controllerGeneration++
                getPreferences(0).edit().putString("controller", controller).apply()
                connections = emptyList(); connectionState = "连接中…"; dialog.dismiss(); render()
                if (!controllerBusy) fetchConnections()
            }
        }
        dialog.show()
    }

    private fun fetchConnections() {
        controllerBusy = true
        val generation = controllerGeneration; val address = controller; val token = secret
        worker.execute {
            val result = runCatching {
                val connection = URL("$address/connections").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 3000; connection.readTimeout = 3000; connection.instanceFollowRedirects = false
                    if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
                    val code = connection.responseCode
                    if (code == 401 || code == 403) error("鉴权失败，请检查 Secret")
                    if (code != 200) error("控制器返回 HTTP $code")
                    val payload = connection.inputStream.bufferedReader().use { reader ->
                        val data = StringBuilder(); val buffer = CharArray(8192)
                        while (true) { val count = reader.read(buffer); if (count < 0) break; data.append(buffer, 0, count); if (data.length > 4_000_000) error("控制器快照过大") }
                        data.toString()
                    }
                    val json = JSONObject(payload)
                    if (!json.has("connections")) error("不是有效的 Mihomo 快照")
                    val array = json.optJSONArray("connections")
                    (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }
                } finally { connection.disconnect() }
            }
            handler.post {
                controllerBusy = false
                if (isDestroyed || !foreground || paused || generation != controllerGeneration) return@post
                result.fold(onSuccess = { connections = it; connectionState = "● 已连接 · ${it.size} 条连接 · $sampledAt" }, onFailure = {
                    connections = emptyList()
                    connectionState = when (it) {
                        is java.net.ConnectException -> "无法连接，请确认手机控制器已开启"
                        is java.net.SocketTimeoutException -> "连接超时，正在重试"
                        is IllegalStateException -> it.message ?: "快照无效"
                        else -> "读取失败，请检查地址、网络与证书"
                    }
                })
                renderConnections()
            }
        }
    }
    private fun renderConnections() {
        controllerStatus?.text = connectionState
        val rows = connectionRows ?: return
        rows.removeAllViews()
        if (!controllerEnabled) {
            val box = card(rows); box.addView(label("接入后查看每一条连接", 18, ink, true)); gap(box)
            box.addView(label("请先连接手机上的 Mihomo 控制器。", 14, muted)); return
        }
        val filtered = connections.filter {
            (protocol == "全部" || it.optJSONObject("metadata")?.optString("network")?.equals(protocol, true) == true) && it.toString().contains(query, true)
        }
        if (filtered.isEmpty()) { rows.addView(label(if (connections.isEmpty()) "尚无有效连接快照" else "没有匹配的连接", 14, muted)); return }
        filtered.take(150).forEach { item ->
            val metadata = item.optJSONObject("metadata") ?: JSONObject()
            val host = metadata.optString("host").ifBlank { metadata.optString("destinationIP", "未知目标") }
            val process = metadata.optString("process").ifBlank { metadata.optString("type", "未知应用") }
            val box = card(rows)
            box.addView(label(host, 16, ink, true)); gap(box, 6)
            box.addView(label("${metadata.optString("network").uppercase()}  ·  $process", 14, mint)); gap(box, 6)
            box.addView(label("↓ ${bytes(item.optLong("download").toDouble())}   ↑ ${bytes(item.optLong("upload").toDouble())}", 14, muted))
            box.setOnClickListener {
                val chains = item.optJSONArray("chains")?.let { array -> (0 until array.length()).joinToString(" → ") { array.optString(it) } } ?: "未知"
                val detail = "应用：$process\n目标：$host:${metadata.optString("destinationPort")}\n源：${metadata.optString("sourceIP")}:${metadata.optString("sourcePort")}\n协议：${metadata.optString("network")}\n规则：${item.optString("rule")} ${item.optString("rulePayload")}\n代理链：$chains\n开始：${item.optString("start")}\nID：${item.optString("id")}"
                AlertDialog.Builder(this).setTitle("连接详情").setMessage(detail).setPositiveButton("关闭", null).show()
            }
            box.isFocusable = true
        }
        if (filtered.size > 150) rows.addView(label("显示前 150 条，请搜索缩小范围。", 12, muted))
    }

    private fun diagnosticText(): String {
        val active = connectivity.activeNetwork
        val capabilities = connectivity.getNetworkCapabilities(active)
        val properties = connectivity.getLinkProperties(active)
        val verified = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return "网络：${networkSummary()}\n系统联网验证：${if (verified) "已通过" else "未通过 / 未知"}\n接口：${properties?.interfaceName ?: "未知"}\n本地地址：${properties?.linkAddresses?.joinToString() ?: "未知"}\nDNS：${properties?.dnsServers?.joinToString { it.hostAddress ?: "未知" } ?: "未知"}\nPrivate DNS：${if (properties?.isPrivateDnsActive == true) "已启用" else "未启用"}\nMTU：${properties?.mtu ?: "未知"}"
    }
    private fun showDiagnostics() {
        heading("NETWORK INSIGHT", "诊断中心", "检查当前网络")
        val info = card(); info.addView(label("系统网络快照", 16, ink, true)); gap(info)
        info.addView(label(diagnosticText(), 14, muted).apply { setTextIsSelectable(true) })
        info.addView(button("刷新网络状态") { render() })
        val probe = card(); probe.addView(label("HTTPS 连通性", 16, ink, true)); gap(probe, 8)
        probeLabel = label(probeResult, 14, muted); probe.addView(probeLabel); gap(probe)
        probe.addView(button(if (probeBusy) "检查中…" else "检查连接", true) { runProbe() })
        body.addView(button("分享诊断报告") {
            val report = "Wiesler Android 0.1.0\n${Date()}\n${diagnosticText()}\nHTTPS：$probeResult\n系统接口累计 RX=${bytes(lastRx.toDouble())} TX=${bytes(lastTx.toDouble())}\nVPN 下系统接口可能重复计量。"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }, "分享诊断报告"))
        }); gap()
        body.addView(button("关于数据来源") { showScope() })
    }
    private fun showScope() {
        AlertDialog.Builder(this).setTitle("数据来源").setMessage("实时速率来自 Android 系统接口计数，只在前台采样。VPN 下可能重复计量，不作为套餐账单。\n\n应用流量来自系统使用情况统计，可能延迟。连接列表仅展示 Mihomo 处理的连接。\n\nHTTPS 检查向 www.gstatic.com 发起一次请求。App 不接管 VPN，也不会自动上传数据。").setPositiveButton("知道了", null).show()
    }
    private fun runProbe() {
        if (probeBusy) return
        probeBusy = true; probeResult = "正在检查…"; probeLabel?.text = probeResult
        worker.execute {
            val result = runCatching {
                val connection = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.instanceFollowRedirects = false; connection.useCaches = false
                    val start = SystemClock.elapsedRealtime(); val code = connection.responseCode
                    "HTTP $code · ${SystemClock.elapsedRealtime() - start} ms\n${if (code == 204) "HTTPS 请求成功" else "未得到预期 204 响应"}（含建连耗时）"
                } finally { connection.disconnect() }
            }.getOrElse { "检查失败：${if (it is java.net.SocketTimeoutException) "超时" else "网络或 TLS 错误"}" }
            handler.post { probeBusy = false; probeResult = result; if (!isDestroyed && page == 3) render() }
        }
    }

    private inner class TrendView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val top = dp(22).toFloat(); val bottom = height - dp(8).toFloat()
            paint.color = line; paint.strokeWidth = dp(1).toFloat()
            repeat(4) { index -> val y = top + (bottom - top) * index / 3; canvas.drawLine(0f, y, width.toFloat(), y, paint) }
            val max = maxOf(1024.0, trend.filterNotNull().maxOfOrNull { maxOf(it.rx, it.tx) } ?: 0.0)
            paint.color = muted; paint.textSize = dp(10).toFloat(); paint.style = Paint.Style.FILL
            canvas.drawText("${bytes(max)}/s", 0f, dp(12).toFloat(), paint)
            if (trend.none { it != null }) {
                paint.textSize = dp(13).toFloat(); canvas.drawText("等待连续采样", dp(12).toFloat(), height / 2f, paint); return
            }
            for (up in listOf(false, true)) {
                val path = Path(); var drawing = false
                trend.forEachIndexed { index, rate ->
                    if (rate == null) { drawing = false; return@forEachIndexed }
                    val x = width * (59 - trend.size + 1 + index) / 59f
                    val y = bottom - ((if (up) rate.tx else rate.rx) / max * (bottom - top)).toFloat()
                    if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                    drawing = true
                    if (index == trend.lastIndex) { paint.color = if (up) violet else mint; canvas.drawCircle(x.coerceAtMost(width - dp(3).toFloat()), y, dp(3).toFloat(), paint) }
                }
                paint.color = if (up) violet else mint; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            }
        }
    }
}
