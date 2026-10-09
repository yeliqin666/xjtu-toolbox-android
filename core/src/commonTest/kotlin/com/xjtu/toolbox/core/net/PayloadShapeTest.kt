package com.xjtu.toolbox.core.net

import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用**真实抓到的**响应做夹具（2026-10-06，本机 campus-api）。
 * 不是编的样例——这正是探针要钉住的东西：换 Ktor 之后解析口径不能变脆。
 */
class PayloadShapeTest {

    @Test
    fun parsesRealTermPayload() {
        val json = """{"code":0,"data":{"term":"2026-2027-1"}}"""
        val env = toolboxJson.decodeFromString<Envelope<TermData>>(json)
        assertEquals(0, env.code)
        assertEquals("2026-2027-1", env.data?.term)
    }

    @Test
    fun parsesRealStatusPayloadWhichHasNoEnvelope() {
        // /api/status 是裸对象（没有 {code,data} 信封）——契约里最容易踩的不一致
        val json =
            """{"authenticated":true,"username":"2253415556","loggedAt":1791272163122,"uptimeSeconds":4327,"lastSessionLifetime":23149}"""
        val status = toolboxJson.decodeFromString<SessionStatus>(json)
        assertTrue(status.authenticated)
        assertEquals("2253415556", status.username)
        assertEquals(4327L, status.uptimeSeconds)
    }

    @Test
    fun unknownUpstreamFieldsDoNotBreakParsing() {
        // 上游 47 列的课表随时加列；App 现在靠 ignoreUnknownKeys 活着，换 Ktor 后必须一样
        val json = """{"code":0,"data":{"term":"2026-2027-1","brandNewColumn":"上游新加的"},"extra":1}"""
        val env = toolboxJson.decodeFromString<Envelope<TermData>>(json)
        assertEquals("2026-2027-1", env.data?.term)
    }

    @Test
    fun absentDataSurfacesAsNullNotThrow() {
        val json = """{"code":500,"message":"上游没登录"}"""
        val env = toolboxJson.decodeFromString<Envelope<TermData>>(json)
        assertEquals(500, env.code)
        assertNull(env.data)
    }
}
