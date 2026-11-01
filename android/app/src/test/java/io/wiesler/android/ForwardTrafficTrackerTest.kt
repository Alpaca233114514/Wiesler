package io.wiesler.android

import org.junit.Assert.*
import org.junit.Test

class ForwardTrafficTrackerTest {
    private val selected = setOf("海外 A")
    private fun counter(id: String = "one", rx: Long = 100, tx: Long = 20,
                        exit: String? = "海外 A", start: String = "start") = RoutedCounter(id, start, exit, rx, tx)

    @Test fun onlyExplicitlySelectedExitsAreOverseas() {
        assertEquals(TrafficRoute.OVERSEAS, trafficRoute("海外 A", selected))
        assertEquals(TrafficRoute.OTHER, trafficRoute("未知代理", selected))
        assertEquals(TrafficRoute.OTHER, trafficRoute(null, selected))
        assertEquals(TrafficRoute.DIRECT, trafficRoute("DIRECT", setOf("DIRECT")))
        assertEquals(TrafficRoute.OTHER, trafficRoute("REJECT", setOf("REJECT")))
    }

    @Test fun measuresElapsedTimeAndExcludesDirectAndOtherExits() {
        val tracker = ForwardTrafficTracker()
        assertNull(tracker.accept(100, listOf(counter(), counter("direct", exit = "DIRECT"), counter("other", exit = "国内")), selected))
        val rate = tracker.accept(1600, listOf(counter(rx = 400, tx = 80), counter("direct", rx = 99999, exit = "DIRECT"), counter("other", rx = 99999, exit = "国内")), selected)
        assertEquals(Rate(200.0, 40.0), rate)
        assertEquals(300.0, tracker.observedRx, 0.0)
        assertEquals(60.0, tracker.observedTx, 0.0)
    }

    @Test fun churnDoesNotCreateNegativeRatesOrHistoricalSpikes() {
        val tracker = ForwardTrafficTracker()
        tracker.accept(0, listOf(counter(), counter("ends", rx = 900000)), selected)
        assertEquals(Rate(100.0, 10.0), tracker.accept(1000, listOf(counter(rx = 200, tx = 30), counter("new", rx = 800000)), selected))
        assertEquals(Rate(100.0, 0.0), tracker.accept(2000, listOf(counter("new", rx = 800100)), selected))
        assertEquals(200.0, tracker.observedRx, 0.0)
    }

    @Test fun changedLifetimeOrExitNeedsNewBaseline() {
        val tracker = ForwardTrafficTracker()
        tracker.accept(0, listOf(counter()), selected)
        assertEquals(Rate(0.0, 0.0), tracker.accept(1000, listOf(counter(rx = 900000, start = "replacement")), selected))
        assertEquals(Rate(0.0, 0.0), tracker.accept(2000, listOf(counter(rx = 999999, exit = "DIRECT", start = "replacement")), selected))
    }

    @Test fun pauseFailureAndLongGapDoNotBridgeRates() {
        val tracker = ForwardTrafficTracker()
        tracker.accept(0, listOf(counter()), selected)
        tracker.reset()
        assertNull(tracker.accept(1000, listOf(counter(rx = 10000)), selected))
        assertNull(tracker.accept(8000, listOf(counter(rx = 90000)), selected))
        assertEquals(Rate(100.0, 0.0), tracker.accept(9000, listOf(counter(rx = 90100)), selected))
        assertEquals(100.0, tracker.observedRx, 0.0)
    }

    @Test fun rollbackAndInvalidCountersAreUnknownThenRecover() {
        val tracker = ForwardTrafficTracker()
        tracker.accept(0, listOf(counter()), selected)
        assertNull(tracker.accept(1000, listOf(counter(rx = 10)), selected))
        assertEquals(Rate(10.0, 0.0), tracker.accept(2000, listOf(counter(rx = 20)), selected))
        assertNull(tracker.accept(3000, listOf(counter(rx = -1)), selected))
        assertNull(tracker.accept(4000, listOf(counter(rx = 40)), selected))
        assertEquals(Rate(10.0, 0.0), tracker.accept(5000, listOf(counter(rx = 50)), selected))
        assertEquals(20.0, tracker.observedRx, 0.0)
    }

    @Test fun emptyValidSnapshotIsZeroButNoSelectionIsUnknown() {
        val tracker = ForwardTrafficTracker()
        assertNull(tracker.accept(0, emptyList(), selected))
        assertEquals(Rate(0.0, 0.0), tracker.accept(1000, emptyList(), selected))
        assertNull(tracker.accept(2000, emptyList(), emptySet()))
        assertNull(tracker.accept(3000, emptyList(), emptySet()))
    }

    @Test fun selectionChangeResetsTotalsAndBaseline() {
        val tracker = ForwardTrafficTracker()
        tracker.accept(0, listOf(counter()), selected)
        tracker.accept(1000, listOf(counter(rx = 200)), selected)
        assertNull(tracker.accept(2000, listOf(counter(rx = 900000)), setOf("海外 B")))
        assertEquals(0.0, tracker.observedRx, 0.0)
        tracker.reset(clearTotals = true)
        assertNull(tracker.accept(3000, listOf(counter()), selected))
    }

    @Test fun nonIncreasingTimestampDoesNotAccumulateTraffic() {
        val tracker = ForwardTrafficTracker()
        tracker.accept(1000, listOf(counter()), selected)
        assertNull(tracker.accept(1000, listOf(counter(rx = 200)), selected))
        assertNull(tracker.accept(900, listOf(counter(rx = 300)), selected))
        assertEquals(0.0, tracker.observedRx, 0.0)
    }
}
