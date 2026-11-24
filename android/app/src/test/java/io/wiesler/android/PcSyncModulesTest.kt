package io.wiesler.android

import org.junit.Assert.*
import org.junit.Test

class PcSyncModulesTest {
    @Test fun egressParsesOnlyValidIpAndMatchesGeoIp() {
        assertEquals("203.0.113.42", EgressProbe.parseIpAddress("203.0.113.42\n"))
        assertEquals("2001:db8:0:0:0:0:0:1", EgressProbe.parseIpAddress("2001:db8::1"))
        assertNull(EgressProbe.parseIpAddress("<html>login</html>"))
        assertNull(EgressProbe.parseIpAddress("127.0.0.1 extra"))
        assertNull(EgressProbe.parseIpAddress("example.com"))

        val body = """{"ip":"203.0.113.42","success":true,"country":"Japan","country_code":"JP","region":"Tokyo","city":"Tokyo","connection":{"isp":"Example ISP"},"timezone":{"id":"Asia/Tokyo"}}"""
        val loc = EgressProbe.parseLocation(body, "203.0.113.42").getOrThrow()
        assertEquals("Tokyo", loc.city)
        assertEquals("Example ISP", loc.isp)
        assertEquals("Asia/Tokyo", loc.timezone)
        assertTrue(EgressProbe.parseLocation(body, "203.0.113.43").isFailure)
        assertTrue(EgressProbe.parseLocation("""{"ip":"203.0.113.42","success":false}""", "203.0.113.42").isFailure)
        assertEquals(7897, EgressProbe.parseMihomoHttpProxyPort("""{"port":7890,"mixed-port":7897}"""))
        assertEquals(7890, EgressProbe.parseMihomoHttpProxyPort("""{"port":7890,"mixed-port":0}"""))
        assertNull(EgressProbe.parseMihomoHttpProxyPort("""{"mixed-port":0}"""))
    }

    @Test fun controllerDiscoveryValidatesBothEndpointsAndLoopbackOnly() {
        assertTrue(ControllerDiscovery.isLoopbackHttp("http://127.0.0.1:9090"))
        assertTrue(ControllerDiscovery.isLoopbackHttp("http://localhost:9097"))
        assertFalse(ControllerDiscovery.isLoopbackHttp("http://192.168.1.1:9090"))
        assertFalse(ControllerDiscovery.isLoopbackHttp("https://127.0.0.1:9090"))

        val verOk = ControllerDiscovery.parseVersionResponse(200, """{"version":"v1.19.32"}""")
        assertTrue(verOk.isMihomo && verOk.version == "v1.19.32" && !verOk.authRequired)
        val verAuth = ControllerDiscovery.parseVersionResponse(401, "unauthorized")
        assertTrue(verAuth.isMihomo && verAuth.authRequired)
        assertFalse(ControllerDiscovery.parseVersionResponse(400, "proxy port").isMihomo)

        val connOk = ControllerDiscovery.parseConnectionsResponse(200, """{"connections":[]}""")
        assertTrue(connOk.isMihomo && connOk.ready)
        val connGeneric = ControllerDiscovery.parseConnectionsResponse(200, """{"ok":true}""")
        assertFalse(connGeneric.isMihomo)
    }

    @Test fun studioHostGroupingRoutePathAndRateHistoryMatchPc() {
        val f1 = studioDemo(0)[0].copy(id = "a", host = "api.example.com", destination = "203.0.113.10:443", route = "JP-01 → 节点选择")
        val f2 = studioDemo(0)[0].copy(id = "b", host = "API.EXAMPLE.COM.", destination = "203.0.113.10:8443", route = "DIRECT")
        val groups = groupByHost(listOf(f1, f2))
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().flows.size)

        val path = routePath(f1)
        assertEquals(listOf("手机", "节点选择", "JP-01", "服务器"), path.map { it.label })
        val directPath = routePath(f2)
        assertEquals(listOf("手机", "服务器"), directPath.map { it.label })

        val session = StudioSession()
        session.accept(listOf(f1), 1000, 1, downloadTotal = 1000, uploadTotal = 500)
        session.accept(listOf(f1.copy(download = f1.download + 2000, upload = f1.upload + 1000)), 2000, 2, downloadTotal = 3000, uploadTotal = 1500)
        assertEquals(2000.0, session.current!!.rxTotal!!, 0.1)
        assertEquals(1000.0, session.current!!.txTotal!!, 0.1)
        val hist = session.rateHistory()
        assertEquals(2, hist[f1.flowKey]!!.size)
        assertNull(hist[f1.flowKey]!![0])
        assertEquals(2000.0, hist[f1.flowKey]!![1]!!.rx, 0.1)
    }

    @Test fun privacyInspectorFlagsDnsBypassUploadAndComparesEgressTimezone() {
        val dnsFlow = studioDemo(0)[3] // port 53, DIRECT
        val uploadFlow = studioDemo(0)[2].copy(tx = 2_000_000.0)
        val frame = StudioFrame(1000, 1_700_000_000_000L, listOf(dnsFlow, uploadFlow))
        val report = PrivacyInspector.analyze(frame, PrivacyPolicy(expectProxy = true, uploadBytesPerSecond = 1024 * 1024))
        assertEquals(3, report.findings.size)
        val summary = PrivacyInspector.summarize(report)
        assertEquals("发现需要核对的连接", summary.title)

        val run = ActivePrivacyRun(
            startedAt = 1_700_000_000_000L,
            dns = ActiveDnsInspection("tun0", true, 1500, true, "dns.google", listOf("8.8.8.8"), "example.com", listOf("93.184.216.34"), 24L),
            systemEgress = EgressAddress("198.51.100.1", 1000L, "https://api64.ipify.org", null, EgressLocation("China", "CN", null, "Shanghai", "ISP", "Asia/Shanghai")),
            proxyEgress = EgressAddress("203.0.113.5", 1000L, "https://api64.ipify.org", "http://127.0.0.1:7897", EgressLocation("Japan", "JP", null, "Tokyo", "ISP2", "Asia/Tokyo"))
        )
        val cards = PrivacyInspector.activeCards(run, localTimeZoneId = "Asia/Shanghai")
        assertTrue(cards.any { it.name == "公网出口对比" && it.result.contains("不同") })
        assertTrue(cards.any { it.name == "时区与公网出口" && it.result.contains("不同") })
        val json = PrivacyInspector.exportJson(report, summary, run, DeviceEnvironmentInfo(listOf("zh-CN"), "Asia/Shanghai", "1080x2400", "Android 15", "已启用"))
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("203.0.113.5"))
    }

    @Test fun sqliteHeaderValidationAndDiagnosticReportBuilder() {
        val valid = ByteArray(72)
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(valid, 0)
        valid[63] = 1
        valid[68] = 0x57; valid[69] = 0x49; valid[70] = 0x53; valid[71] = 0x4c
        assertTrue(SqliteHistoryReader.isValidHeader(valid))
        valid[71] = 0x00
        assertFalse(SqliteHistoryReader.isValidHeader(valid))

        val session = StoredSession("sess-1", 1_700_000_000_000L, 1_700_000_060_000L, "completed", "0.1.1", 2)
        val events = listOf(
            """{"data":{"kind":"ControllerVersion","payload":{"version":"v1.19.32"}}}""",
            """{"data":{"kind":"SnapshotObserved","payload":{"connection_count":12,"download_total":4096,"upload_total":1024}}}""",
            """{"data":{"kind":"ConnectionObserved","payload":{"connection":{"chains":["JP-01"],"metadata":{"process":"Chrome","host":"example.com"}}}}}""",
            """{"data":{"kind":"ControllerUnavailable","payload":{"reason":"timeout"}}}"""
        )
        val md = SqliteHistoryReader.buildDiagnosticMarkdown(session, events)
        assertTrue(md.contains("sess-1"))
        assertTrue(md.contains("v1.19.32"))
        assertTrue(md.contains("Chrome"))
        assertTrue(md.contains("timeout"))
    }
}
