package io.wiesler.android

import org.junit.Assert.*
import org.junit.Test

class StudioModelTest {
    @Test fun ratesRebaselineAfterGapsResetsAndFailures() {
        val session = StudioSession()
        session.accept(studioDemo(0), 1000, 1)
        assertNull(session.current!!.flows[0].rx)
        session.accept(studioDemo(1), 2000, 2)
        assertEquals(650000.0, session.current!!.flows[0].rx!!, .01)
        session.accept(studioDemo(2), 8000, 3)
        assertNull(session.current!!.flows[0].rx)
        session.accept(studioDemo(0), 9000, 4)
        assertNull(session.current!!.flows[0].rx)
        session.accept(emptyList(), 10000, 5, "HTTP 401")
        assertTrue(session.current!!.flows.isEmpty())
        session.accept(studioDemo(1), 11000, 6)
        assertNull(session.current!!.flows[0].rx)
        session.resetBaseline()
        session.accept(studioDemo(2), 12000, 7)
        assertNull(session.current!!.flows[0].rx)
    }

    @Test fun freezeRetainsExactFrameWhileCaptureAndRetentionContinue() {
        val session = StudioSession()
        session.accept(studioDemo(0), 1000, 1); session.freeze()
        val frozen = session.current
        for (tick in 1..80) session.accept(studioDemo(tick), 1000L + tick * 1000, tick.toLong())
        assertSame(frozen, session.current)
        assertEquals(60, session.frames.size)
        session.replay(0); assertEquals(21L, session.current!!.wallTime)
        session.now(); assertEquals(80L, session.current!!.wallTime)
    }

    @Test fun sourceSwitchClearsDemoFailuresAndReplay() {
        val session = StudioSession()
        session.accept(studioDemo(18), 1000, 1); session.freeze()
        assertNotNull(session.current!!.flows[1].event)
        session.switchSource(true)
        assertNull(session.current); assertNull(session.held)
        assertTrue(session.frames.isEmpty())
        session.accept(studioDemo(0).map { it.copy(event = null) }, 2000, 2)
        assertTrue(session.current!!.flows.all { it.event == null && it.rx == null })
    }

    @Test fun filteringAndSelectionRespectGraphLimit() {
        val session = StudioSession()
        val flows = (0..20).map { studioDemo(0)[0].copy(id = "flow-$it") } + studioDemo(0)[3]
        session.accept(flows, 1000, 1); session.selectedId = "flow-20"
        assertEquals(6, session.graphFlows().size)
        assertEquals("flow-20", session.graphFlows().first().id)
        session.protocol = "UDP"
        assertEquals(1, session.filtered().size)
        assertEquals("demo-3", session.graphFlows().single().id)
        session.query = "missing"
        assertTrue(session.graphFlows().isEmpty())
    }

    @Test fun changedStartRouteAndInvalidCountersNeverProduceRates() {
        val session = StudioSession()
        val first = studioDemo(0)[0]
        session.accept(listOf(first), 1000, 1)
        session.accept(listOf(first.copy(start = "new", download = 999999)), 2000, 2)
        assertNull(session.current!!.flows.single().rx)
        session.accept(listOf(first.copy(start = "new", route = "DIRECT", download = 1000000)), 3000, 3)
        assertNull(session.current!!.flows.single().rx)
        session.accept(listOf(first.copy(download = -1)), 4000, 4)
        assertNull(session.current!!.flows.single().rx)
    }

    @Test fun ipv6AndUnknownPortsAreUnambiguous() {
        assertEquals("[2001:db8::1]:443", studioEndpoint("2001:db8::1", "443"))
        assertEquals("example.com:?", studioEndpoint("example.com", "-1"))
        assertEquals("未知:?", studioEndpoint("", ""))
    }
}
