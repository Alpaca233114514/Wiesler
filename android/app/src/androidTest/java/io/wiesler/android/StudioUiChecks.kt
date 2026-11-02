package io.wiesler.android

import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.io.File

class StudioUiChecks(private val instrumentation: Instrumentation) {
    fun run(): Int {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                parseControllerFields()
                listOf(Triple(360, 1f, "phone"), Triple(800, 1f, "landscape"), Triple(360, 2f, "large-font")).forEach { (width, font, name) ->
                    val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply { fontScale = font; densityDpi = 160 }
                    val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(configuration), R.style.AppTheme)
                    val session = StudioSession()
                    session.accept(studioDemo(0), 1000, 1); session.accept(studioDemo(1), 2000, 2)
                    val panel = StudioPanel(context, session, {}, {})
                    fun button(title: String) = descendants(panel).filterIsInstance<Button>().first { it.text.toString() == title }
                    button("UDP").performClick(); check(session.filtered().single().protocol == "UDP")
                    button("全部").performClick()
                    val search = descendants(panel).filterIsInstance<EditText>().single()
                    search.setText("sync.example.net"); check(session.filtered().single().app == "Cloud Sync")
                    search.setText("")
                    button("冻结").performClick(); val held = session.current
                    session.accept(studioDemo(2), 3000, 3); panel.refresh(); check(session.current === held)
                    button("回到现在").performClick(); check(session.current !== held)
                    val density = context.resources.displayMetrics.density
                    panel.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                    val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).apply { drawColor(StudioColors.background); panel.draw(this) }
                    File(context.cacheDir, "studio-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    descendants(panel).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE }.forEach {
                        check(it.measuredHeight >= (48 * density).toInt()) { "Touch target too small: ${it.text}" }
                        check(it.layout?.let { layout -> (0 until layout.lineCount).all { line -> layout.getLineMax(line) <= it.width - it.paddingLeft - it.paddingRight + 2 } } != false) { "Clipped button: ${it.text}" }
                    }
                    button("实时连接").performClick(); check(session.live && session.frames.isEmpty())
                }
                graphTouchSelection()
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        return 5
    }

    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()

    private fun parseControllerFields() {
        val camel = StudioPanel.parse(JSONObject("""{"id":"live-1","start":"today","metadata":{"network":"tcp","host":"example.com","process":"Browser","sourceIP":"2001:db8::1","sourcePort":1234,"destinationIP":"203.0.113.8","destinationPort":443},"download":8,"upload":2,"chains":["node","group"]}"""))
        check(camel.source == "[2001:db8::1]:1234" && camel.route == "node → group" && camel.event == null)
        val snake = StudioPanel.parse(JSONObject("""{"id":"live-2","metadata":{"source_ip":"192.0.2.1","source_port":1234,"destination_ip":"203.0.113.8","destination_port":443}}"""))
        check(snake.host == "203.0.113.8" && snake.destination == "203.0.113.8:443" && snake.download == -1L)
    }

    private fun graphTouchSelection() {
        var selected: StudioFlow? = null
        val graph = StudioGraphView(instrumentation.targetContext) { selected = it }
        graph.show(studioDemo(0), null, false)
        graph.layout(0, 0, 360, 440)
        val parent = android.widget.FrameLayout(instrumentation.targetContext)
        parent.addView(graph)
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(0, 20, action, 230f, 45f, 0)
            graph.dispatchTouchEvent(event); event.recycle()
        }
        check(selected?.id == "demo-0")
    }
}
