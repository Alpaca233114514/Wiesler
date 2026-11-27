package io.wiesler.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteDictionaryTest {
    @Test fun resolvesCdnSubdomainsWithLongestSuffixPrecedenceAndBoundarySafety() {
        assertEquals("哔哩哔哩", SiteDictionary.resolveSite("i0.hdslb.com")?.name)
        assertEquals("bilibili.com", SiteDictionary.resolveSite("UPOS-SZ.BILIVIDEO.COM.")?.url)
        assertEquals("微信", SiteDictionary.resolveSite("weixin.qq.com")?.name)
        assertEquals("微信", SiteDictionary.resolveSite("mmbiz.qpic.cn")?.name)
        assertEquals("腾讯 / QQ", SiteDictionary.resolveSite("a.qpic.cn")?.name)
        assertEquals("YouTube", SiteDictionary.resolveSite("rr3---sn-abc.googlevideo.com")?.name)
        assertEquals("YouTube", SiteDictionary.resolveSite("youtubei.googleapis.com")?.name)
        assertEquals("Google AI / Gemini", SiteDictionary.resolveSite("aicode.googleapis.com")?.name)
        assertEquals("Cloudflare DNS", SiteDictionary.resolveSite("1.1.1.1")?.name)
        assertEquals("中国电信", SiteDictionary.resolveSite("sipsc202.r01.rcs.189.cn")?.name)
        assertEquals("哈啰出行", SiteDictionary.resolveSite("mpaasmdap.hellobike.com")?.name)
        assertEquals("推送 / 统计 SDK", SiteDictionary.resolveSite("sdk-open-phone.getui.com")?.name)
        assertEquals("公网出口 / 归属地探测", SiteDictionary.resolveSite("api.ipify.org")?.name)
        assertNull(SiteDictionary.resolveSite("notgithub.com"))
        assertNull(SiteDictionary.resolveSite("example.com"))
        assertNull(SiteDictionary.resolveSite("203.0.113.10"))
    }

    @Test fun parseStudioFlowFallsBackToSniffHostAndGroupsBySiteOrRawHost() {
        val b1 = parseStudioFlow(
            JSONObject("""{"id":"b1","start":"t1","metadata":{"process":"Bili","host":"i0.hdslb.com","destinationIP":"203.0.113.1","destinationPort":"443","network":"tcp"},"chains":["DIRECT"],"rule":"DomainSuffix","download":100,"upload":10}""")
        )
        val b2 = parseStudioFlow(
            JSONObject("""{"id":"b2","start":"t2","metadata":{"process":"Bili","host":"api.bilibili.com","destinationIP":"203.0.113.2","destinationPort":"443","network":"tcp"},"chains":["DIRECT"],"rule":"DomainSuffix","download":200,"upload":20}""")
        )
        val y1 = parseStudioFlow(
            JSONObject("""{"id":"y1","start":"t3","metadata":{"process":"Tube","host":"","sniffHost":"rr1.googlevideo.com","destinationIP":"203.0.113.3","destinationPort":"443","network":"tcp"},"chains":["JP-01"],"rule":"DomainSuffix","download":300,"upload":30}""")
        )

        assertEquals("rr1.googlevideo.com", y1.host)
        assertEquals("YouTube", y1.displayTarget)

        val session = StudioSession()
        session.accept(listOf(b1, b2, y1), 1000L, 1000L)

        val siteGroups = session.graphHostGroups()
        assertEquals(2, siteGroups.size)
        assertEquals("哔哩哔哩", siteGroups[0].label)
        assertEquals(listOf("i0.hdslb.com", "api.bilibili.com"), siteGroups[0].distinctHosts)
        assertEquals(2, siteGroups[0].flows.size)
        assertEquals("YouTube", siteGroups[1].label)

        session.graphMode = StudioGraphMode.HOST
        val hostGroups = session.graphHostGroups()
        assertEquals(3, hostGroups.size)

        session.graphMode = StudioGraphMode.SITE
        session.query = "油管"
        assertEquals(listOf("y1"), session.filtered().map { it.id })
        session.query = "b站"
        assertEquals(listOf("b1", "b2"), session.filtered().map { it.id })
    }
}
