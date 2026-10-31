package io.wiesler.android

data class CounterSample(val time: Long, val rx: Long, val tx: Long)
data class Rate(val rx: Double, val tx: Double)

class RateTracker {
    private var previous: CounterSample? = null
    fun reset() { previous = null }
    fun accept(sample: CounterSample): Rate? {
        val before = previous
        previous = sample.takeIf { it.rx >= 0 && it.tx >= 0 }
        if (before == null || previous == null) return null
        val elapsed = sample.time - before.time
        // 不跨缺口或计数重置计算速率。 / Never bridge gaps or counter resets.
        if (elapsed <= 0 || elapsed > 5000 || sample.rx < before.rx || sample.tx < before.tx) return null
        return Rate((sample.rx - before.rx) * 1000.0 / elapsed, (sample.tx - before.tx) * 1000.0 / elapsed)
    }
}
