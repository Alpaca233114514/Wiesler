package io.wiesler.android

import org.junit.Assert.*
import org.junit.Test

class RequestLogTest {
    @Test fun responseCodesAndTransportErrorsStayDistinct() {
        val ok = RequestLog("12:00:00", "Mihomo /connections", 200)
        assertEquals("成功", ok.result)
        assertFalse(ok.failed)
        val forbidden = ok.copy(status = 403)
        assertTrue(forbidden.failed)
        assertTrue(forbidden.text().contains("HTTP 403 · 失败"))
        assertEquals("重定向", ok.copy(status = 302).result)
        val timeout = ok.copy(status = null, error = "连接超时")
        assertTrue(timeout.failed)
        assertTrue(timeout.text().contains("未取得状态码"))
        assertFalse(timeout.text().contains("HTTP 200"))
    }
    @Test fun invalidPayloadCanFailAfterSuccessfulHttpResponse() {
        val entry = RequestLog("12:00:00", "Mihomo /connections", 200, "快照无效")
        assertTrue(entry.failed)
        assertTrue(entry.text().contains("HTTP 200 · 失败"))
    }
}
