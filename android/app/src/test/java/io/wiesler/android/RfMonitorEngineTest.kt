package io.wiesler.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RfMonitorEngineTest {

    @Test
    fun deduplicatesStaleCellInfoAndFlagsCacheFreeze() {
        val engine = RfMonitorEngine()
        val base = 1_764_211_200_000L
        val first = RfCellSample(
            observedAtMs = base,
            slotIndex = 0,
            subId = 4,
            operatorName = "中国移动",
            plmn = "46000",
            rat = RfRat.NR,
            arfcn = 504_990,
            rsrpDbm = -91,
            rsrqDb = -11,
            sinrDb = 14,
            cellTimestampMs = 99_000L,
            source = RfSampleSource.ACTIVE_CELL_INFO
        )
        assertTrue(engine.ingest(first))
        // 连续 3 次返回同一硬件时间戳，必须过滤并标记缓存冻结。 / 3 identical hardware timestamps must dedup and flag freeze.
        assertFalse(engine.ingest(first.copy(observedAtMs = base + 3_000L)))
        assertFalse(engine.ingest(first.copy(observedAtMs = base + 6_000L)))
        assertFalse(engine.ingest(first.copy(observedAtMs = base + 9_000L)))

        val snap = engine.evaluate(nowMs = base + 9_000L, active = true, permissionGranted = true)
        assertEquals(1, snap.captureHealth.freshFrames)
        assertEquals(3, snap.captureHealth.cachedDuplicates)
        assertEquals(1, snap.captureHealth.frozenSlotCount)
        assertTrue(RfAnomalyKind.CACHE_FREEZE in snap.assessment.slots.first().activeFlags)

        // 硬件时间戳推进后自动解除冻结。 / Advancing hardware timestamp clears cache freeze.
        assertTrue(engine.ingest(first.copy(observedAtMs = base + 12_000L, cellTimestampMs = 111_000L, rsrpDbm = -92)))
        val recovered = engine.evaluate(nowMs = base + 12_000L, active = true, permissionGranted = true)
        assertEquals(2, recovered.captureHealth.freshFrames)
        assertEquals(0, recovered.captureHealth.frozenSlotCount)
        assertFalse(recovered.assessment.slots.first().cacheFrozen)
    }

    @Test
    fun detectsInterferenceScissorsAndDistinguishesWeakFade() {
        val engine = RfMonitorEngine()
        val t0 = 1_764_211_300_000L

        // 场景 A：纯弱覆盖（RSRP=-116 dBm, SINR=-2 dB）不应触发强信号剪刀差。
        engine.ingest(
            RfCellSample(
                observedAtMs = t0, slotIndex = 0, subId = 4, operatorName = "中国移动", plmn = "46000",
                rat = RfRat.NR, arfcn = 504_990, rsrpDbm = -116, rsrqDb = -16, sinrDb = -2, cellTimestampMs = 1L
            )
        )
        engine.ingest(
            RfCellSample(
                observedAtMs = t0 + 2_000L, slotIndex = 0, subId = 4, operatorName = "中国移动", plmn = "46000",
                rat = RfRat.NR, arfcn = 504_990, rsrpDbm = -118, rsrqDb = -17, sinrDb = -3, cellTimestampMs = 2L
            )
        )
        val fadeSnap = engine.evaluate(t0 + 2_000L, active = true, permissionGranted = true)
        assertEquals(RfSeverity.WATCH, fadeSnap.assessment.severity)
        assertFalse(RfAnomalyKind.RF_INTERFERENCE in fadeSnap.assessment.slots.first().activeFlags)

        // 场景 B：强信号剪刀差（实测报告 10:15:40-42 样本：RSRP=-85/-80, RSRQ=-24/-23, SINR=-12/-20）连续 2 帧触发 RF_INTERFERENCE。
        engine.ingest(
            RfCellSample(
                observedAtMs = t0 + 5_000L, slotIndex = 0, subId = 4, operatorName = "中国移动", plmn = "46000",
                rat = RfRat.NR, arfcn = 504_990, rsrpDbm = -85, rsrqDb = -24, sinrDb = -12, cellTimestampMs = 3L
            )
        )
        engine.ingest(
            RfCellSample(
                observedAtMs = t0 + 7_000L, slotIndex = 0, subId = 4, operatorName = "中国移动", plmn = "46000",
                rat = RfRat.NR, arfcn = 504_990, rsrpDbm = -80, rsrqDb = -23, sinrDb = -20, cellTimestampMs = 4L
            )
        )
        val scissorsSnap = engine.evaluate(t0 + 7_000L, active = true, permissionGranted = true)
        assertEquals(RfSeverity.INTERFERENCE, scissorsSnap.assessment.severity)
        assertTrue(RfAnomalyKind.RF_INTERFERENCE in scissorsSnap.assessment.slots.first().activeFlags)
    }

    @Test
    fun detectsSyncFloorDualSimCoincidenceAndPingPong() {
        val engine = RfMonitorEngine()
        val t0 = 1_764_211_400_000L

        // 卡 0（中国移动 46000）跌穿 3GPP 底板 (-156 dBm / -43 dB / -20 dB)。
        engine.ingest(
            RfCellSample(
                observedAtMs = t0, slotIndex = 0, subId = 4, operatorName = "中国移动", plmn = "46000",
                rat = RfRat.NR, arfcn = 504_990, rsrpDbm = -156, rsrqDb = -43, sinrDb = -20, cellTimestampMs = 10L
            )
        )
        // 卡 1（中国电信 46011）在 60s 内高频乒乓切换并在 30s 内遭遇强剪刀差 (-72 / -20 / -10)。
        val channels = listOf(
            RfRat.NR to 633_984,
            RfRat.NR to 627_264,
            RfRat.NR to 633_984,
            RfRat.LTE to 1_650,
            RfRat.LTE to 1_850
        )
        channels.forEachIndexed { idx, (rat, arfcn) ->
            val severe = idx >= 3
            engine.ingest(
                RfCellSample(
                    observedAtMs = t0 + (idx + 1) * 3_000L,
                    slotIndex = 1,
                    subId = 5,
                    operatorName = "中国电信",
                    plmn = "46011",
                    rat = rat,
                    arfcn = arfcn,
                    rsrpDbm = if (severe) -72 else -96,
                    rsrqDb = if (severe) -20 else -12,
                    sinrDb = if (severe) -10 else 6,
                    cellTimestampMs = 20L + idx
                )
            )
        }

        val now = t0 + 18_000L
        val snap = engine.evaluate(now, active = true, permissionGranted = true)
        assertEquals(RfSeverity.SEVERE_POLLUTION, snap.assessment.severity)
        assertTrue(snap.assessment.dualSimCoincidence)
        assertEquals(2, snap.assessment.distinctAlertPlmns)
        assertTrue(snap.assessment.score >= 85)
        val slot1 = snap.assessment.slots.first { it.slotIndex == 1 }
        assertTrue(RfAnomalyKind.CELL_PINGPONG in slot1.activeFlags)
        assertTrue(RfAnomalyKind.RF_INTERFERENCE in slot1.activeFlags)
        assertEquals("B3 (1.8GHz)", slot1.bandLabel)
    }

    @Test
    fun attributesAirInterfaceVsProxyVsUpstreamFaults() {
        val engine = RfMonitorEngine()
        val t0 = 1_764_211_500_000L

        // 1) 空口平稳 + ICMP 正常 + 代理控制器失败 -> 归因为代理/出口故障。
        engine.ingest(
            RfCellSample(
                observedAtMs = t0, slotIndex = 0, subId = 4, operatorName = "中国移动", plmn = "46000",
                rat = RfRat.NR, arfcn = 504_990, rsrpDbm = -92, rsrqDb = -11, sinrDb = 11, cellTimestampMs = 1L
            )
        )
        val goodPings = (0..4).map { i -> TimedPingEvidence(t0 + i * 1_000L, "reply", 68.0) }
        val proxyFault = engine.evaluate(
            nowMs = t0 + 5_000L,
            active = true,
            permissionGranted = true,
            linkEvidence = RfLinkEvidence(
                nowMs = t0 + 5_000L,
                recentPings = goodPings,
                controllerEnabled = true,
                controllerHealthy = false,
                lastControllerErrorAtMs = t0 + 4_000L,
                lastControllerError = "连接超时",
                cellularValidated = true
            )
        )
        assertEquals(RfFaultCause.PROXY_OR_EGRESS, proxyFault.attribution.cause)

        // 2) 空口平稳 + ICMP 40% 丢包 + 代理正常 -> 归因为骨干/接入网链路抖动。
        val badPings = listOf(
            TimedPingEvidence(t0 + 6_000L, "lost", null),
            TimedPingEvidence(t0 + 7_000L, "lost", null),
            TimedPingEvidence(t0 + 8_000L, "reply", 95.0),
            TimedPingEvidence(t0 + 9_000L, "reply", 110.0),
            TimedPingEvidence(t0 + 10_000L, "reply", 88.0)
        )
        val upstreamFault = engine.evaluate(
            nowMs = t0 + 11_000L,
            active = true,
            permissionGranted = true,
            linkEvidence = RfLinkEvidence(
                nowMs = t0 + 11_000L,
                recentPings = badPings,
                controllerEnabled = true,
                controllerHealthy = true,
                cellularValidated = true
            )
        )
        assertEquals(RfFaultCause.UPSTREAM_LINK, upstreamFault.attribution.cause)

        // 3) 空口进入 NO_SIGNAL 失锁 + ICMP 丢包 -> 归因为蜂窝射频空口污染。
        engine.ingest(
            RfCellSample(
                observedAtMs = t0 + 12_000L, slotIndex = 0, subId = 4, operatorName = "中国移动", plmn = "46000",
                rat = RfRat.NO_SIGNAL, inService = false, source = RfSampleSource.SERVICE_STATE
            )
        )
        val rfFault = engine.evaluate(
            nowMs = t0 + 13_000L,
            active = true,
            permissionGranted = true,
            linkEvidence = RfLinkEvidence(
                nowMs = t0 + 13_000L,
                recentPings = badPings,
                controllerEnabled = true,
                controllerHealthy = false,
                cellularValidated = false,
                cellularLostAtMs = t0 + 12_500L
            )
        )
        assertEquals(RfFaultCause.RF_AIR_INTERFACE, rfFault.attribution.cause)
        assertNotNull(rfFault.toJsonObject().optJSONObject("attribution"))
    }
}
