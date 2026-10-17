package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.auth.AccountType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** serve 契约 §5.10 的客户端那一侧：多账号页的那一条只读端点（`GET /api/accounts`，[CampusAccountsApi]）。 */
class ServeAccountsApiTest {

    private class Call(val path: String, val url: String)

    private fun engineFor(body: String, calls: MutableList<Call>? = null): MockEngine =
        MockEngine { request ->
            calls?.add(Call(request.url.encodedPath, request.url.toString()))
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }

    /** 服务端那一条的实测形状（`ServeAccountsTest` 里那份响应体的同型，值换成夹具的编造学号）。 */
    private val accountsBody = """
        {"code":0,"data":{"authenticated":true,"readOnly":true,"accounts":[
          {"id":"2023****0002","accountType":"postgraduate","lastUsedAt":"2023-11-15T06:13:20+08:00","active":false},
          {"id":"2023****0001","accountType":"undergraduate","lastUsedAt":null,"active":true}]}}
    """.trimIndent()

    /** 四格逐字段；ISO 解成 epoch；`null` 如实是 null；`active` 只标一条。 */
    @Test
    fun serveAccountsReadsTheEndpoint() = runTest {
        val calls = mutableListOf<Call>()
        val api = CampusAccountsApi(
            createToolboxClient(engine = engineFor(accountsBody, calls)),
            "",
            ApiMode.SERVE,
        )

        val snapshot = api.accounts()

        assertEquals("/api/accounts", calls.single().path)
        assertTrue(snapshot.authenticated)
        assertTrue(snapshot.readOnly, "能力开关原样读出来 —— 屏按它决定能不能画动作")

        assertEquals(2, snapshot.accounts.size)
        val fromTable = snapshot.accounts.first()
        assertEquals("2023****0002", fromTable.id, "打码后的 id 就是显示名与句柄")
        assertEquals(AccountType.POSTGRADUATE, fromTable.accountType)
        // `2023-11-15T06:13:20+08:00` ⇒ epoch 毫秒（本端只做一次换算，屏自己按本地时区画）
        assertEquals(1_700_000_000_000L, fromTable.lastUsedAt)
        assertFalse(fromTable.active)

        val fromSession = snapshot.accounts.last()
        assertEquals("2023****0001", fromSession.id)
        assertEquals(AccountType.UNDERGRADUATE, fromSession.accountType)
        assertNull(fromSession.lastUsedAt, "服务端明确说没有 ⇒ 本端不编一个时刻")
        assertTrue(fromSession.active)
    }

    /** `readOnly` 缺了 / 认不出 ⇒ 只读；打码 id 为空的那一行丢掉；`accounts` 缺了就是空表。 */
    @Test
    fun missingCapabilityMeansReadOnlyAndBlankRowsAreDropped() = runTest {
        val api = CampusAccountsApi(
            createToolboxClient(
                engine = engineFor(
                    """{"code":0,"data":{"authenticated":false,"accounts":[
                        {"id":"","accountType":"undergraduate","lastUsedAt":null,"active":false},
                        {"id":"2023****0001","accountType":"不认识的身份","lastUsedAt":"不是时间","active":true}]}}""",
                ),
            ),
            "",
            ApiMode.SERVE,
        )

        val snapshot = api.accounts()

        assertFalse(snapshot.authenticated)
        assertTrue(snapshot.readOnly, "缺这一格按只读（保守的那一侧）")
        assertEquals(1, snapshot.accounts.size, "空 id 的那一行丢掉")
        val row = snapshot.accounts.single()
        assertEquals(AccountType.UNDERGRADUATE, row.accountType, "认不出的身份落回本科（与其余端同一条兜底）")
        assertNull(row.lastUsedAt, "解不出来的时间就是「没有」，不编")
    }

    /** campus-api 形态：没有这一族 ⇒ 抛错，而且**一个请求都不发**。 */
    @Test
    fun campusApiFormThrowsWithoutCallingAnything() = runTest {
        val calls = mutableListOf<Call>()
        val api = CampusAccountsApi(
            createToolboxClient(engine = engineFor(accountsBody, calls)),
            "",
            ApiMode.CAMPUS_API,
        )

        assertFailsWith<UnsupportedOperationException> { api.accounts() }
        assertEquals(0, calls.size, "形态不对就不该打这一枪（campus-api 没有 /api/accounts）")
    }
}
