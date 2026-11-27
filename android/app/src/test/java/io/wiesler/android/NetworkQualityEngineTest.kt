package io.wiesler.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkQualityEngineTest {

    @Test
    fun offlineNetworkScoresZeroAndMarksAllScenariosDegraded() {
        val snap = NetworkQualityEngine.evaluate(
            NetworkQualityInput(
                nowMs = 1_000L,
                link = SystemLinkSnapshot(connected = false, transportLabel = "未连接网络")
            )
        )
        assertEquals(0, snap.overallScore)
        assertEquals(NetworkQualityGrade.OFFLINE, snap.grade)
        assertTrue(snap.scenarios.all { it.status == ScenarioStatus.DEGRADED })
    }

    @Test
    fun healthyWifiWithGoodProbesReachesExcellentGrade() {
        val pings = listOf(
            TimedPingEvidence(1000L, "reply", 18.0),
            TimedPingEvidence(2000L, "reply", 20.0),
            TimedPingEvidence(3000L, "reply", 19.0),
            TimedPingEvidence(4000L, "reply", 21.0)
        )
        val snap = NetworkQualityEngine.evaluate(
            NetworkQualityInput(
                nowMs = 5_000L,
                link = SystemLinkSnapshot(
                    connected = true,
                    transportLabel = "Wi-Fi",
                    isWifi = true,
                    validated = true,
                    wifiRssiDbm = -52,
                    downstreamKbps = 240_000,
                    upstreamKbps = 120_000,
                    privateDnsActive = true,
                    privateDnsServer = "dns.alidns.com"
                ),
                pingTarget = "223.5.5.5",
                recentPings = pings,
                lossPercent = 0.0,
                averageRttMs = 19.5,
                pingSent = 4,
                pingReceived = 4,
                dnsInspection = ActiveDnsInspection(
                    interfaceName = "wlan0",
                    isVpn = false,
                    mtu = 1500,
                    privateDnsActive = true,
                    privateDnsServer = "dns.alidns.com",
                    dnsServers = listOf("223.5.5.5"),
                    resolvedAddresses = listOf("93.184.216.34"),
                    probeElapsedMs = 22L
                ),
                httpsProbe = HttpsProbeSnapshot(
                    observedAtMs = 4_800L,
                    statusCode = 204,
                    latencyMs = 110L,
                    ok = true
                ),
                systemEgress = EgressAddress(
                    ip = "198.51.100.10",
                    observedAt = 4_900L,
                    provider = "https://api.ipify.org",
                    proxy = null,
                    location = EgressLocation(country = "CN", city = "Shanghai")
                )
            )
        )

        assertNotNull(snap.overallScore)
        assertTrue("Expected >= 90, got ${snap.overallScore}", snap.overallScore!! >= 90)
        assertEquals(NetworkQualityGrade.EXCELLENT, snap.grade)
        assertEquals(3, snap.measuredDimensions)
        assertEquals(3, snap.totalApplicableDimensions)
        assertNotNull(snap.jitterMs)
        assertTrue(snap.jitterMs!! < 2.0)
        assertEquals(ScenarioStatus.OPTIMAL, snap.scenarios.first { it.id == "game" }.status)
        assertEquals(ScenarioStatus.OPTIMAL, snap.scenarios.first { it.id == "voice" }.status)
    }

    @Test
    fun unmeasuredDimensionsDoNotFabricateScoresAndWeightsNormalizeCleanly() {
        val snap = NetworkQualityEngine.evaluate(
            NetworkQualityInput(
                nowMs = 10_000L,
                link = SystemLinkSnapshot(
                    connected = true,
                    transportLabel = "Wi-Fi",
                    isWifi = true,
                    validated = true,
                    wifiRssiDbm = -60
                )
            )
        )
        assertEquals(1, snap.measuredDimensions)
        assertEquals(3, snap.totalApplicableDimensions)
        val latency = snap.dimensions.first { it.id == QualityDimensionId.LATENCY_STABILITY }
        val reach = snap.dimensions.first { it.id == QualityDimensionId.REACHABILITY_DNS }
        val proxy = snap.dimensions.first { it.id == QualityDimensionId.PROXY_HEALTH }
        assertFalse(latency.available)
        assertNull(latency.score)
        assertFalse(reach.available)
        assertFalse(proxy.available)
        assertTrue(snap.overallScore in 85..100)
    }

    @Test
    fun highPacketLossAppliesHardBottleneckCap() {
        val snap = NetworkQualityEngine.evaluate(
            NetworkQualityInput(
                nowMs = 20_000L,
                link = SystemLinkSnapshot(
                    connected = true,
                    transportLabel = "Wi-Fi",
                    isWifi = true,
                    validated = true,
                    wifiRssiDbm = -48,
                    downstreamKbps = 300_000
                ),
                lossPercent = 35.0,
                averageRttMs = 45.0,
                pingSent = 20,
                pingReceived = 13,
                httpsProbe = HttpsProbeSnapshot(observedAtMs = 19_000L, statusCode = 204, latencyMs = 95L, ok = true)
            )
        )
        assertNotNull(snap.overallScore)
        assertTrue("35% loss must cap composite score <= 39, got ${snap.overallScore}", snap.overallScore!! <= 39)
        assertEquals(NetworkQualityGrade.POOR, snap.grade)
    }

    @Test
    fun severeRfPollutionCapsScoreAndSurfacesAttributionHeadline() {
        val rfSnap = RfDiagnosticsSnapshot(
            captureHealth = RfCaptureHealth(active = true, permissionGranted = true, freshFrames = 12),
            assessment = RfPollutionAssessment(
                score = 90,
                severity = RfSeverity.SEVERE_POLLUTION,
                title = "环境级宽带通信污染（跨运营商双卡并发）",
                detail = "30 秒内双卡同步触发剪刀差",
                slots = listOf(
                    RfSlotSummary(
                        slotIndex = 0,
                        subId = 1,
                        operatorName = "中国移动",
                        plmn = "46000",
                        rat = RfRat.NR,
                        arfcn = 504_990,
                        bandLabel = "n41 (2.6GHz)",
                        carrierAggregation = true,
                        inService = true,
                        rsrpDbm = -82,
                        rsrqDb = -18,
                        sinrDb = -4,
                        freshFrames = 6,
                        cachedDuplicates = 0,
                        cacheFrozen = false,
                        consecutiveScissors = 4,
                        scissorsCount30s = 4,
                        syncLossCount30s = 0,
                        pingPongTransitions60s = 2,
                        rsrpSinrCorrelation = -0.7,
                        activeFlags = setOf(RfAnomalyKind.RF_INTERFERENCE),
                        lastFreshAtMs = 29_000L,
                        trend = emptyList()
                    )
                )
            ),
            attribution = RfAttributionVerdict(
                cause = RfFaultCause.RF_AIR_INTERFACE,
                confidence = "高",
                title = "优先归因为蜂窝射频空口污染",
                detail = "双卡同时出现强场强低信噪比剪刀差",
                suggestion = "建议远离当前干扰源或切换 Wi-Fi"
            )
        )
        val snap = NetworkQualityEngine.evaluate(
            NetworkQualityInput(
                nowMs = 30_000L,
                link = SystemLinkSnapshot(
                    connected = true,
                    transportLabel = "移动网络",
                    isCellular = true,
                    validated = true
                ),
                rfDiagnostics = rfSnap,
                lossPercent = 5.0,
                averageRttMs = 95.0,
                pingSent = 10,
                pingReceived = 9
            )
        )
        assertTrue("Severe RF pollution must cap overall score <= 42, got ${snap.overallScore}", snap.overallScore!! <= 42)
        assertEquals("优先归因为蜂窝射频空口污染", snap.headline)
    }

    @Test
    fun proxyDimensionEntersScoringOnlyWhenControllerIsEnabled() {
        val snap = NetworkQualityEngine.evaluate(
            NetworkQualityInput(
                nowMs = 40_000L,
                link = SystemLinkSnapshot(connected = true, transportLabel = "Wi-Fi", isWifi = true, validated = true, wifiRssiDbm = -58),
                controllerEnabled = true,
                controllerHealthy = false,
                proxyEgressError = "代理出口探测超时",
                recentRequestLogs = listOf(
                    RequestLog("10:00:01", "Mihomo /connections", null, "连接超时"),
                    RequestLog("10:00:02", "Mihomo /connections", null, "连接超时")
                )
            )
        )
        assertEquals(4, snap.totalApplicableDimensions)
        val proxyDim = snap.dimensions.first { it.id == QualityDimensionId.PROXY_HEALTH }
        assertTrue(proxyDim.available)
        assertNotNull(proxyDim.score)
        assertTrue("Broken proxy should score <= 25, got ${proxyDim.score}", proxyDim.score!! <= 25)
    }

    @Test
    fun historyBufferThrottlesSubSecondWritesAndCapsLength() {
        val buffer = NetworkQualityHistoryBuffer(maxPoints = 5)
        for (i in 0..7) {
            val snap = NetworkQualitySnapshot(evaluatedAtMs = i * 1_000L, overallScore = 80 + i)
            buffer.record(snap, rttMs = 20.0 + i, lossPercent = 0.0)
            // 500ms 内的重复写入应被节流忽略 / Sub-900ms duplicate should be throttled.
            buffer.record(snap.copy(evaluatedAtMs = i * 1_000L + 500L), rttMs = 99.0, lossPercent = 0.0)
        }
        val list = buffer.snapshot()
        assertEquals(5, list.size)
        assertEquals(83, list.first().score)
        assertEquals(87, list.last().score)
    }
}
