package io.wiesler.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.*

object StudioColors {
    val background = Color.rgb(12, 17, 27)
    val surface = Color.rgb(18, 27, 41)
    val border = Color.rgb(42, 55, 75)
    val ink = Color.rgb(232, 238, 248)
    val muted = Color.rgb(162, 176, 197)
    val mint = Color.rgb(124, 230, 205)
    val blue = Color.rgb(121, 182, 255)
    val amber = Color.rgb(255, 202, 131)
    val selected = Color.rgb(28, 56, 62)
}

class StudioGraphView(context: Context, private val select: (StudioFlow) -> Unit) : View(context) {
    constructor(context: Context) : this(context, {})
    private data class Node(val key: String, val title: String, val subtitle: String, val flow: StudioFlow, val box: RectF)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pathMeasure = PathMeasure()
    private val pulsePoint = FloatArray(2)
    private val positions = mutableMapOf<String, PointF>()
    private var nodes = emptyList<Node>()
    private var edges = emptyList<Pair<StudioFlow, Path>>()
    private var flows = emptyList<StudioFlow>()
    private var selected: String? = null
    private var zoom = 1f
    private var pan = PointF()
    private var drag: Node? = null
    private var last = PointF()
    private var down = PointF()
    private var moved = false
    private var pinched = false
    private var pulse = 1f
    private var animator: ValueAnimator? = null
    private val baseScale get() = min(width / 360f, height / 440f)
    private val scale get() = baseScale * zoom
    private val originX get() = (width - 360 * baseScale) / 2
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinched = true; zoomBy(detector.scaleFactor); return true
        }
    })

    init {
        setBackgroundColor(StudioColors.surface)
        contentDescription = "应用与目标连接图谱，可拖动节点、双指缩放；下方会话列表提供相同详情入口"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun show(value: List<StudioFlow>, selection: String?, animate: Boolean) {
        flows = value; selected = selection
        val keys = value.flatMap { listOf("app:${it.app}", it.id) }.toSet()
        positions.keys.retainAll(keys)
        layoutNodes()
        animator?.cancel(); pulse = 1f
        if (animate && isShown && ValueAnimator.areAnimatorsEnabled()) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700
                addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        invalidate()
    }

    fun fit() { zoom = 1f; pan = PointF(); positions.clear(); layoutNodes(); invalidate() }
    fun zoomBy(factor: Float) {
        val old = zoom
        zoom = (zoom * factor).coerceIn(0.75f, 2.5f)
        val centerX = width / 2f - originX
        pan.x = centerX - (centerX - pan.x) * zoom / old
        pan.y = height / 2f - (height / 2f - pan.y) * zoom / old
        invalidate()
    }

    private fun layoutNodes() {
        val apps = flows.map { it.app }.distinct()
        val list = ArrayList<Node>()
        apps.forEachIndexed { index, app ->
            val key = "app:$app"
            val point = positions[key] ?: PointF(16f, 20f + index * (350f / (apps.size - 1).coerceAtLeast(1)))
            val count = flows.count { it.app == app }
            list.add(Node(key, app, "$count 条会话", flows.first { it.app == app }, RectF(point.x, point.y, point.x + 112, point.y + 58)))
        }
        flows.forEachIndexed { index, flow ->
            val point = positions[flow.id] ?: PointF(206f, 20f + index * 70f)
            list.add(Node(flow.id, flow.host, if (flow.event != null) "演示 · TLS 超时" else flow.protocol, flow,
                RectF(point.x, point.y, point.x + 138, point.y + 58)))
        }
        nodes = list
        edges = flows.map { flow ->
            val from = nodes.first { it.key == "app:${flow.app}" }.box
            val to = nodes.first { it.key == flow.id }.box
            flow to Path().apply {
                moveTo(from.right, from.centerY())
                cubicTo(from.right + 48, from.centerY(), to.left - 48, to.centerY(), to.left, to.centerY())
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        paint.color = StudioColors.border
        for (x in 0..(width / (22 * density)).toInt()) for (y in 0..(height / (22 * density)).toInt())
            canvas.drawCircle(x * 22 * density, y * 22 * density, density * .65f, paint)
        if (flows.isEmpty()) return
        canvas.save(); canvas.translate(originX + pan.x, pan.y); canvas.scale(scale, scale)
        edges.forEach { (flow, path) ->
            paint.style = Paint.Style.STROKE
            paint.color = when { flow.event != null -> StudioColors.amber; flow.id == selected -> StudioColors.mint; else -> Color.rgb(71, 101, 130) }
            paint.strokeWidth = 1.5f + min(3f, ((flow.rx ?: 0.0) + (flow.tx ?: 0.0)).toFloat() / 250000)
            canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            if (pulse < 1f) {
                pathMeasure.setPath(path, false)
                if ((flow.tx ?: 0.0) > 0) { pathMeasure.getPosTan(pathMeasure.length * pulse, pulsePoint, null); paint.color = StudioColors.blue; canvas.drawCircle(pulsePoint[0], pulsePoint[1], 3.5f, paint) }
                if ((flow.rx ?: 0.0) > 0) { pathMeasure.getPosTan(pathMeasure.length * (1 - pulse), pulsePoint, null); paint.color = StudioColors.mint; canvas.drawCircle(pulsePoint[0], pulsePoint[1], 3.5f, paint) }
            }
        }
        nodes.forEach { node ->
            val active = node.flow.id == selected || (node.key.startsWith("app:") && flows.any { it.id == selected && it.app == node.title })
            paint.color = if (active) StudioColors.selected else Color.rgb(24, 39, 58)
            canvas.drawRoundRect(node.box, 10f, 10f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = if (active) 2f else 1f
            paint.color = when { node.flow.event != null && !node.key.startsWith("app:") -> StudioColors.amber; active -> StudioColors.mint; else -> Color.rgb(86, 117, 167) }
            canvas.drawRoundRect(node.box, 10f, 10f, paint); paint.style = Paint.Style.FILL
            paint.color = StudioColors.ink; paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = 14f * resources.configuration.fontScale.coerceAtMost(1.4f)
            canvas.drawText(ellipsize(node.title, node.box.width() - 20), node.box.left + 10, node.box.top + 24, paint)
            paint.typeface = Typeface.DEFAULT; paint.textSize = 12f * resources.configuration.fontScale.coerceAtMost(1.4f)
            paint.color = if (node.flow.event != null && !node.key.startsWith("app:")) StudioColors.amber else StudioColors.muted
            canvas.drawText(ellipsize(node.subtitle, node.box.width() - 20), node.box.left + 10, node.box.top + 44, paint)
        }
        canvas.restore()
    }

    private fun ellipsize(value: String, width: Float): String {
        if (paint.measureText(value) <= width) return value
        val length = paint.breakText(value, true, width - paint.measureText("…"), null)
        return value.take(length) + "…"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                down = PointF(event.x, event.y); last = PointF(event.x, event.y); moved = false; pinched = false
                val x = (event.x - originX - pan.x) / scale; val y = (event.y - pan.y) / scale
                drag = nodes.lastOrNull { it.box.contains(x, y) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - down.x, event.y - down.y) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
                if (!detector.isInProgress && !pinched && moved) {
                    val dx = event.x - last.x; val dy = event.y - last.y
                    val node = drag
                    if (node != null) {
                        positions[node.key] = PointF(node.box.left + dx / scale, node.box.top + dy / scale); layoutNodes()
                    } else { pan.offset(dx, dy) }
                    invalidate()
                }
                last = PointF(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (!moved && !pinched) {
                    performClick()
                    val target = drag?.flow ?: nearestEdge((event.x - originX - pan.x) / scale, (event.y - pan.y) / scale)
                    target?.let(select)
                }
                drag = null
            }
            MotionEvent.ACTION_CANCEL -> { drag = null; parent.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }

    private fun nearestEdge(x: Float, y: Float): StudioFlow? {
        var found: StudioFlow? = null; var distance = 22f
        val point = FloatArray(2)
        edges.forEach { (flow, path) ->
            val measure = PathMeasure(path, false)
            for (step in 0..30) {
                measure.getPosTan(measure.length * step / 30, point, null)
                val d = hypot(point[0] - x, point[1] - y)
                if (d < distance) { distance = d; found = flow }
            }
        }
        return found
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
}
