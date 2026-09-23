package com.xjtu.toolbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactTest {

    @Test
    fun redactsCredentialQueryParamsButKeepsKeys() {
        val url = "https://ncard.xjtu.edu.cn/plat/?ticket=ST-abc-123&from=h5&code=Xy9kQ&state=1"
        val out = url.redactUrl()
        assertFalse(out.contains("ST-abc"))
        assertFalse(out.contains("Xy9kQ"))
        assertTrue(out.contains("ticket=<redacted>"))
        assertTrue(out.contains("code=<redacted>"))
        assertTrue(out.contains("from=h5"))
        assertTrue(out.contains("state=1"))
    }

    @Test
    fun redactsJsonTokenButKeepsStatusCode() {
        val body = """{"code":200,"access_token":"abc.def","token_type":"bearer","msg":"ok"}"""
        val out = body.redactBody(500)
        assertFalse(out.contains("abc.def"))
        assertTrue(out.contains("\"code\":200"))
        assertTrue(out.contains("\"msg\":\"ok\""))
    }

    @Test
    fun redactsCasHiddenInputs() {
        val html = """<input type="hidden" name="execution" value="e1s1-secret"/><input name="lt" value="LT-9">"""
        val out = html.redactBody(500)
        assertFalse(out.contains("e1s1-secret"))
        assertFalse(out.contains("LT-9"))
    }

    @Test
    fun redactsStudentIdsAndLongTokens() {
        val out = LogRedact.redact("user 2211312345 cookie eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcdef")
        assertFalse(out.contains("2211312345"))
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
    }

    @Test
    fun redactsBeforeTruncating() {
        // 截断点落在 token 中间时，残段不能因为变短而逃过长串规则
        val token = "A".repeat(40)
        val out = "prefix $token".redactBody(20)
        assertFalse(out.contains("AAAAAAAAAA"))
    }

    @Test
    fun nullSafe() {
        assertEquals("null", (null as String?).redactUrl())
        assertEquals("null", (null as String?).redactBody())
    }
}
