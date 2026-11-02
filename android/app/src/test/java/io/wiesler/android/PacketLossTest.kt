package io.wiesler.android

import org.junit.Assert.*
import org.junit.Test

class PacketLossTest {
    @Test fun replyTimeoutAndExecutionErrorsStayDistinct() {
        val reply = parsePing(0, "64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=0.042 ms\n1 packets transmitted, 1 received, 0% packet loss")
        assertEquals("reply", reply.outcome)
        assertEquals(0.042, reply.rttMs!!, 0.0001)
        assertEquals("reply", parsePing(0, "1 packets transmitted, 1 packets received, 0% packet loss").outcome)
        assertEquals("lost", parsePing(1, "1 packets transmitted, 0 received, 100% packet loss").outcome)
        for ((exit, text) in listOf(2 to "ping: socket: Operation not permitted", 1 to "invalid output",
            1 to "Destination Host Unreachable\n1 packets transmitted, 0 received, +1 errors, 100% packet loss",
            0 to "0 packets transmitted, 0 received, 0% packet loss")) {
            assertEquals("error", parsePing(exit, text).outcome)
        }
    }

    @Test fun rollingWindowExcludesErrorsAndExpiresOldResults() {
        val window = PacketLossWindow()
        assertNull(window.lossPercent)
        window.add(PingSample("error", message = "unavailable"))
        assertNull(window.lossPercent)
        window.add(PingSample("reply", 20.0, "ok"))
        window.add(PingSample("lost", message = "timeout"))
        assertEquals(50.0, window.lossPercent!!, 0.01)
        assertEquals(20.0, window.averageRtt!!, 0.01)
        assertEquals(1, window.errors)
        repeat(60) { window.add(PingSample("reply", 0.0, "ok")) }
        assertEquals(60, window.sent)
        assertEquals(0, window.errors)
        assertEquals(0.0, window.lossPercent!!, 0.01)
        window.clear()
        assertNull(window.lossPercent)
    }

    @Test fun onlyNumericUnicastTargetsAreAccepted() {
        for (value in listOf("", "example.org", "::1", "-c 99", "1.1.1.1;id", "01.1.1.1", "256.1.1.1", "224.0.0.1", "0.0.0.0", "255.255.255.255")) {
            assertFalse(value, validPingTarget(value))
        }
        assertTrue(validPingTarget("127.0.0.1"))
        assertTrue(validPingTarget("1.1.1.1"))
    }
}
