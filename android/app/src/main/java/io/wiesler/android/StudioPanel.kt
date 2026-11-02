package io.wiesler.android

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StudioPanel(
    context: Context, private val session: StudioSession,
    private val configure: () -> Unit, private val sourceChanged: () -> Unit
) : LinearLayout(context) {
    constructor(context: Context) : this(context, StudioSession(), {}, {})
    private val graph = StudioGraphView(context) { inspect(it) }
    private val state = text("", 14, StudioColors.muted)
    private val count = text("", 14, StudioColors.mint)
    private val time = text("现在", 14, StudioColors.muted)
    private val timeline = SeekBar(context)
    private val rows = column()
    private val empty = text("", 16, StudioColors.muted)
    private val freeze = action("冻结") {
        if (session.held == null) session.freeze() else session.now()
        refresh()
    }
    private val demo = action("演示数据") { changeSource(false) }
    private val live = action("实时连接") { changeSource(true) }
    private var limit = 30
    private var dialog: Dialog? = null
    private val rowViews = LinkedHashMap<String, Pair<TextView, StudioFlow>>()
    private val more = action("显示更多会话") { limit = (limit + 30).coerceAtMost(200); refresh() }
    private var statusMessage = ""

    init {
        orientation = VERTICAL
        val sources = row()
        sources.addView(live, weighted()); sources.addView(demo, weighted())
        addView(sources); gap(this, 12)
        val sourceRow = row()
        sourceRow.addView(state, LayoutParams(0, -2, 1f))
        sourceRow.addView(action("控制器", configure))
        addView(sourceRow); gap(this, 16)
        val card = column().apply { background = shape(StudioColors.surface); setPadding(dp(12), dp(8), dp(12), dp(12)) }
        val title = row()
        title.addView(text("图谱", 20, bold = true), LayoutParams(0, -2, 1f)); title.addView(count)
        card.addView(title); gap(card, 8)
        val controls = row()
        controls.addView(freeze, weighted())
        controls.addView(action("−") { graph.zoomBy(.8f) }.apply { contentDescription = "缩小图谱" }, weighted())
        controls.addView(action("适配") { graph.fit() }, weighted())
        controls.addView(action("＋") { graph.zoomBy(1.25f) }.apply { contentDescription = "放大图谱" }, weighted())
        card.addView(controls)
        val frame = FrameLayout(context)
        frame.addView(graph, FrameLayout.LayoutParams(-1, -1))
        empty.gravity = Gravity.CENTER; empty.setPadding(dp(24), 0, dp(24), 0)
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        card.addView(frame, LayoutParams(-1, dp(400)))
        val legend = row()
        legend.addView(text("上传 →", 14, StudioColors.blue), weighted())
        legend.addView(text("← 下载", 14, StudioColors.mint), weighted())
        card.addView(legend); gap(card, 8)
        val playback = row()
        playback.addView(action("回到现在") { session.now(); refresh() })
        time.gravity = Gravity.END
        playback.addView(time, LayoutParams(0, -2, 1f)); card.addView(playback)
        timeline.contentDescription = "回放最近 60 个快照"
        timeline.minHeight = dp(48)
        timeline.progressTintList = ColorStateList.valueOf(StudioColors.mint)
        timeline.thumbTintList = ColorStateList.valueOf(StudioColors.mint)
        timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) { session.replay(progress); refresh() }
            }
            override fun onStartTrackingTouch(bar: SeekBar) { session.freeze() }
            override fun onStopTrackingTouch(bar: SeekBar) { refresh() }
        })
        card.addView(timeline); addView(card); gap(this, 24)
        addView(text("会话", 22, bold = true)); gap(this, 12)
        val search = EditText(context).apply {
            hint = "搜索应用或目标"; contentDescription = "搜索应用、目标或出口"
            setTextColor(StudioColors.ink); setHintTextColor(StudioColors.muted); textSize = 16f
            isSingleLine = true; minHeight = dp(48); background = shape(StudioColors.surface)
            setPadding(dp(12), dp(8), dp(12), dp(8)); setText(session.query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { session.query = s.toString(); refresh() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        addView(search); gap(this, 8)
        val filters = row()
        listOf("全部", "TCP", "UDP").forEach { protocol ->
            val button = action(protocol) { session.protocol = protocol; updateFilters(filters); refresh() }
            button.tag = protocol; filters.addView(button, weighted())
        }
        updateFilters(filters); addView(filters); gap(this, 12)
        addView(rows); addView(more); gap(this, 16)
        addView(action("关于图谱") {
            android.app.AlertDialog.Builder(context).setTitle("图谱数据")
                .setMessage("演示数据为本地场景，不发起测试流量。实时连接来自手机 Mihomo 控制器，只读观察。\n\n线条表示逻辑会话，脉冲表示快照的字节增量，并非单个数据包。冻结不停止采集。前台保留最近 60 个快照，退出后不保存图谱回放。\n\n图谱最多绘制 6 条会话，列表最多 200 条，可搜索缩小范围。连接消失不代表失败；实时数据不推断 TLS 或 HTTP 错误。")
                .setPositiveButton("知道了", null).show()
        })
        refresh()
    }

    fun updateStatus(value: String) { statusMessage = value; refresh() }

    private fun changeSource(value: Boolean) {
        if (session.live == value) return
        session.switchSource(value); limit = 30; graph.fit(); sourceChanged(); refresh()
    }

    fun refresh(animate: Boolean = false) {
        mark(demo, !session.live); mark(live, session.live)
        val matches = session.filtered()
        if (matches.none { it.id == session.selectedId }) session.selectedId = null
        count.text = context.getString(R.string.studio_count, minOf(matches.size, 6), matches.size)
        state.text = if (session.live) statusMessage.ifBlank { "等待手机控制器" } else "本地演示 · 不产生测试流量"
        freeze.text = if (session.held != null) "继续" else "冻结"
        freeze.isEnabled = session.current != null
        time.text = session.current?.let {
            (if (session.held == null) "现在 · " else "回放 · ") + SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(it.wallTime))
        } ?: "尚无快照"
        timeline.max = (session.frames.size - 1).coerceAtLeast(0)
        timeline.isEnabled = session.frames.isNotEmpty()
        timeline.progress = if (session.held == null) timeline.max else session.frames.indexOf(session.held).coerceAtLeast(0)
        graph.show(session.graphFlows(), session.selectedId, animate && session.held == null)
        empty.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
        empty.text = session.current?.error ?: when {
            session.current == null && session.live -> "连接手机控制器后\n在这里查看实时图谱"
            session.current?.flows?.isNotEmpty() == true -> "没有匹配的会话"
            else -> "当前没有活跃连接"
        }
        val shown = matches.take(limit)
        val ids = shown.map { it.id }
        if (rowViews.keys.toList() != ids) {
            rows.removeAllViews(); rowViews.clear()
            shown.forEach { flow ->
                val view = action("") { rowViews[flow.id]?.second?.let(::inspect) }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 16f
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                }
                rowViews[flow.id] = view to flow
                rows.addView(view, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        shown.forEach { flow ->
            val view = rowViews.getValue(flow.id).first
            rowViews[flow.id] = view to flow
            view.text = context.getString(R.string.studio_flow, flow.app, flow.protocol, flow.host, rate(flow.rx), rate(flow.tx), flow.route)
            if (flow.event != null) view.append("\n演示 · TLS 超时")
            mark(view, flow.id == session.selectedId)
        }
        more.visibility = if (matches.size > shown.size && limit < 200) View.VISIBLE else View.GONE
    }

    private fun inspect(flow: StudioFlow) {
        session.selectedId = flow.id; refresh(); dialog?.dismiss()
        val sheet = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        dialog = sheet
        val content = column().apply { setPadding(dp(20), dp(12), dp(20), dp(24)); background = shape(StudioColors.surface) }
        val header = row()
        header.addView(text("会话检查器 · ${flow.protocol}", 16, StudioColors.muted), LayoutParams(0, -2, 1f))
        header.addView(action("关闭") { sheet.dismiss() }); content.addView(header)
        content.addView(text(flow.host, 24, bold = true)); gap(content, 8)
        content.addView(text(flow.destination, 14, StudioColors.muted).apply { setTextIsSelectable(true) }); gap(content, 16)
        val details = text("", 16).apply { setTextIsSelectable(true); setLineSpacing(dp(5).toFloat(), 1f) }
        fun tab(name: String) {
            details.typeface = if (name == "原始数据") Typeface.MONOSPACE else Typeface.DEFAULT
            details.text = when (name) {
                "事件" -> flow.event ?: if (session.live) "未采集会话级事件。连接快照不提供请求结果。" else "本演示会话暂无异常事件。"
                "原始数据" -> runCatching { JSONObject(flow.raw).toString(2) }.getOrDefault(flow.raw)
                else -> "应用\n${flow.app}\n\n源地址\n${flow.source}\n\n出口\n${flow.route}\n\n匹配规则\n${flow.rule}\n\n下载 / 秒       上传 / 秒\n${rate(flow.rx)}       ${rate(flow.tx)}\n\n累计下载       累计上传\n${bytes(flow.download.toDouble())}       ${bytes(flow.upload.toDouble())}\n\n开始\n${flow.start}\n\n会话 ID\n${flow.id}"
            }
        }
        val tabs = row()
        listOf("概览", "事件", "原始数据").forEach { name ->
            val button = action(name) {
                tab(name)
                for (i in 0 until tabs.childCount) mark(tabs.getChildAt(i) as TextView, (tabs.getChildAt(i) as TextView).text == name)
            }
            mark(button, name == "概览"); tabs.addView(button, weighted())
        }
        content.addView(tabs); gap(content, 16); content.addView(details); tab("概览")
        val scroll = ScrollView(context).apply { addView(content) }
        sheet.setContentView(scroll); sheet.show()
        sheet.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent); setGravity(Gravity.BOTTOM)
            val available = resources.displayMetrics.heightPixels
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (available * .82f).toInt())
        }
    }

    private fun updateFilters(filters: LinearLayout) {
        for (i in 0 until filters.childCount) mark(filters.getChildAt(i) as TextView, filters.getChildAt(i).tag == session.protocol)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weighted() = LayoutParams(0, -2, 1f).apply { marginEnd = dp(4); marginStart = dp(4) }
    private fun gap(parent: LinearLayout, size: Int) { parent.addView(View(context), LayoutParams(1, dp(size))) }
    private fun text(value: String, size: Int, color: Int = StudioColors.ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat(); setStroke(dp(1), StudioColors.border) }
    private fun action(value: String, run: () -> Unit) = Button(context).apply {
        text = value; textSize = 14f; isAllCaps = false; minHeight = dp(48); minimumWidth = 0; minWidth = 0
        setPadding(dp(8), dp(8), dp(8), dp(8)); setTextColor(StudioColors.ink)
        background = RippleDrawable(ColorStateList.valueOf(0x337ce6cd), shape(StudioColors.surface), null)
        setOnClickListener { run() }
    }
    private fun mark(view: TextView, active: Boolean) {
        view.isSelected = active
        view.setTextColor(if (active) StudioColors.mint else StudioColors.ink)
        view.background = RippleDrawable(ColorStateList.valueOf(0x337ce6cd), shape(if (active) StudioColors.selected else StudioColors.surface), null)
    }
    override fun onDetachedFromWindow() { dialog?.dismiss(); dialog = null; super.onDetachedFromWindow() }

    companion object {
        fun parse(item: JSONObject): StudioFlow {
            val metadata = item.optJSONObject("metadata") ?: JSONObject()
            fun value(camel: String, snake: String = camel) = metadata.optString(camel).ifBlank { metadata.optString(snake) }
            val host = value("host").ifBlank { value("destinationIP", "destination_ip").ifBlank { "未知目标" } }
            val chains = item.optJSONArray("chains")
            return StudioFlow(item.optString("id"), item.optString("start"), value("process").ifBlank { "未知应用" }, host,
                studioEndpoint(value("sourceIP", "source_ip"), value("sourcePort", "source_port")),
                studioEndpoint(value("destinationIP", "destination_ip").ifBlank { host }, value("destinationPort", "destination_port")),
                value("network").uppercase(Locale.ROOT), (0 until (chains?.length() ?: 0)).joinToString(" → ") { chains!!.optString(it) }.ifBlank { "未知" },
                item.optString("rule", "未知"), item.optLong("download", -1), item.optLong("upload", -1), item.toString())
        }
        private fun rate(value: Double?) = value?.let { bytes(it) + "/s" } ?: "—"
        private fun bytes(value: Double): String {
            if (value < 0 || !value.isFinite()) return "—"
            var number = value; var index = 0
            while (number >= 1024 && index < 4) { number /= 1024; index++ }
            return String.format(Locale.ROOT, if (index == 0) "%.0f %s" else "%.1f %s", number, listOf("B", "KB", "MB", "GB", "TB")[index])
        }
    }
}
