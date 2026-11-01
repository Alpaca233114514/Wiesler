package io.wiesler.android

data class RoutedCounter(
    val id: String, val start: String, val exit: String?, val rx: Long, val tx: Long
)

enum class TrafficRoute(val title: String) {
    DIRECT("直连"), OVERSEAS("海外转发"), OTHER("其他 / 未知")
}

fun eligibleExit(exit: String?): Boolean = !exit.isNullOrBlank() &&
    exit.uppercase(java.util.Locale.ROOT) !in setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "DNS")

fun trafficRoute(exit: String?, selected: Set<String>): TrafficRoute = when {
    exit.equals("DIRECT", true) -> TrafficRoute.DIRECT
    eligibleExit(exit) && exit in selected -> TrafficRoute.OVERSEAS
    else -> TrafficRoute.OTHER
}

class ForwardTrafficTracker {
    private var previousTime: Long? = null
    private var previous = emptyMap<String, RoutedCounter>()
    private var previousSelection = emptySet<String>()
    var observedRx = 0.0
        private set
    var observedTx = 0.0
        private set

    fun reset(clearTotals: Boolean = false) {
        previousTime = null
        previous = emptyMap()
        if (clearTotals) { observedRx = 0.0; observedTx = 0.0 }
    }

    fun accept(time: Long, counters: List<RoutedCounter>, selected: Set<String>): Rate? {
        if (selected != previousSelection) {
            reset(clearTotals = true)
            previousSelection = selected.toSet()
        }
        val beforeTime = previousTime
        val before = previous
        previousTime = time
        previous = counters.filter { it.id.isNotBlank() }.associateBy { it.id }
        val elapsed = beforeTime?.let { time - it }
        if (selected.isEmpty() || elapsed == null || elapsed <= 0 || elapsed > 5000) return null
        var rx = 0.0
        var tx = 0.0
        for (current in previous.values) {
            if (trafficRoute(current.exit, selected) != TrafficRoute.OVERSEAS) continue
            // 按连接差分，新增/消失连接不带入旧累计。 / Diff stable IDs, never aggregate changing lifetimes.
            val old = before[current.id] ?: continue
            if (old.start != current.start || old.exit != current.exit) continue
            if (old.rx < 0 || old.tx < 0 || current.rx < old.rx || current.tx < old.tx) return null
            rx += (current.rx - old.rx).toDouble()
            tx += (current.tx - old.tx).toDouble()
        }
        observedRx += rx
        observedTx += tx
        return Rate(rx * 1000.0 / elapsed, tx * 1000.0 / elapsed)
    }
}
