package com.xjtu.toolbox.core.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 探针的第一等公民：**会话（Cookie）链路**。
 *
 * 为什么它比「能不能发请求」重要：App 现在是 okhttp + `okhttp-java-net-cookiejar`，
 * 而 Ktor 的会话模型完全不同（`CookiesStorage` 接口）。这条链路断了，登录全废，
 * 而且**只有在真机上才会发现**。用 MockEngine 把它钉成离线测试，就能进 CI 门禁。
 */
class CookieRoundTripTest {

    private val termPayload = """{"code":0,"data":{"term":"2026-2027-1"}}"""

    @Test
    fun sessionCookieIsStoredThenResent() = runTest {
        val jar = MemoryCookieJar()
        val cookieHeadersSeen = mutableListOf<String?>()

        var responseCount = 0
        val engine = MockEngine { request: HttpRequestData ->
            cookieHeadersSeen += request.headers[HttpHeaders.Cookie]
            responseCount++
            // 只在第 1 个响应下发会话；第 2 个不发 —— 考验的正是「客户端自己记得」
            val headers = if (responseCount == 1) {
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.SetCookie to listOf("JSESSIONID=probe-abc; Path=/; HttpOnly"),
                )
            } else {
                headersOf(HttpHeaders.ContentType to listOf("application/json"))
            }
            respond(content = termPayload, status = HttpStatusCode.OK, headers = headers)
        }

        val client = createToolboxClient(cookieStorage = jar, engine = engine)
        val api = CampusApi(client, "http://campus-api.probe")

        assertEquals("2026-2027-1", api.term())
        assertEquals("2026-2027-1", api.term())

        // 第一次请求不该带 Cookie（会话还没建立）
        assertNull(cookieHeadersSeen[0], "第一次请求不应该带 Cookie")
        // 第二次必须带上——这正是 okhttp-java-net-cookiejar 现在替我们做的事
        assertEquals("JSESSIONID=probe-abc", cookieHeadersSeen[1], "第二次请求必须带上会话 Cookie")
        assertEquals(1, jar.addedCount, "Set-Cookie 应该只落库一次")
        client.close()
    }

    @Test
    fun jarKeepsSubsystemsSeparate() = runTest {
        // 校园系统的会话是跨子系统共享的：CAS 一次登录，jwxt / lms 各自发 Cookie。
        // 不同 host 的 Cookie 不能串味（校内的 jwapp 与 lms 是两个域）。
        val jar = MemoryCookieJar()
        val jwxt = Url("https://jwxt.xjtu.edu.cn/")
        val lms = Url("https://lms.xjtu.edu.cn/")
        jar.addCookie(jwxt, Cookie("jwxt_sid", "1"))
        jar.addCookie(lms, Cookie("lms_sid", "2"))

        assertEquals("jwxt_sid=1", jar.cookieHeaderFor(jwxt))
        assertEquals("lms_sid=2", jar.cookieHeaderFor(lms))
        assertEquals(2, jar.allCookies().size)
    }
}
