package io.wiesler.android

import org.junit.Assert.*
import org.junit.Test

class RateTrackerTest {
    @Test fun elapsedTimeDeterminesRate() {
        val tracker = RateTracker()
        assertNull(tracker.accept(CounterSample(100, 100, 20)))
        assertEquals(Rate(200.0, 40.0), tracker.accept(CounterSample(1600, 400, 80)))
    }
    @Test fun resetAndPauseDoNotProduceSpikes() {
        val tracker = RateTracker()
        tracker.accept(CounterSample(0, 900, 90))
        assertNull(tracker.accept(CounterSample(1000, 10, 1)))
        assertEquals(Rate(10.0, 1.0), tracker.accept(CounterSample(2000, 20, 2)))
        tracker.reset()
        assertNull(tracker.accept(CounterSample(3000, 20000, 20000)))
    }
    @Test fun unsupportedCountersAndLongGapsAreUnknown() {
        val tracker = RateTracker()
        tracker.accept(CounterSample(0, 0, 0))
        assertNull(tracker.accept(CounterSample(9000, 5000, 5000)))
        assertNull(tracker.accept(CounterSample(10000, -1, -1)))
        assertNull(tracker.accept(CounterSample(11000, 6000, 6000)))
    }
}
