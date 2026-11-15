package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.auth.AccountType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** serve 契约 §5.10 / §5.19 的客户端写侧：路径、请求体形状、错误映射，以及密码不泄漏。 */
class ServeAccountsWriteApiTest {

    private class Call(
        val method: HttpMethod,
        val path: String,
        val body: String?,
    )

    private fun engineFor(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        calls: MutableList<Call>? = null,
    ): MockEngine = MockEngine { request ->
        calls?.add(Call(request.method, request.url.encodedPath, (request.body as? TextContent)?.text))
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
        )
    }

    private fun api(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        calls: MutableList<Call>? = null,
        mode: ApiMode = ApiMode.SERVE,
    ) = CampusAccountsApi(
        createToolboxClient(engine = engineFor(body, status, calls)),
        "",
        mode,
    )

    private val snapshot = """
        {"authenticated":true,"readOnly":false,"accounts":[
          {"id":"2023****0001","accountType":"undergraduate","lastUsedAt":null,"active":true}]}
    """.trimIndent()

    private val okWrite = """{"code":0,"data":{"ok":true,"changed":true,"message":"已完成","snapshot":$snapshot}}"""

    @Test
    fun switchPostsIdOnlyToTheSwitchPath() = runTest {
        val calls = mutableListOf<Call>()
        val result = api(okWrite, calls = calls).switchAccount("2023****0001")

        val call = calls.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("/api/accounts/switch", call.path)
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals("2023****0001", body["id"]!!.jsonPrimitive.content)
        assertEquals(setOf("id"), body.keys, "切换只认 id，不收密码")
        assertTrue(result.ok)
        assertTrue(result.changed)
        assertEquals("已完成", result.message)
        assertEquals("2023****0001", result.snapshot!!.accounts.single().id)
        assertTrue(result.snapshot!!.accounts.single().active)
    }

    @Test
    fun addPostsUsernamePasswordTypeAndActivate() = runTest {
        val calls = mutableListOf<Call>()
        val password = LEAK_PROBE
        val result = api(okWrite, calls = calls).addAccount(
            username = "202300000001",
            password = password,
            accountType = AccountType.POSTGRADUATE,
            activate = true,
        )

        val call = calls.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("/api/accounts/add", call.path)
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals("202300000001", body["username"]!!.jsonPrimitive.content)
        assertEquals(password, body["password"]!!.jsonPrimitive.content, "密码只出现在请求体里")
        assertEquals("postgraduate", body["accountType"]!!.jsonPrimitive.content)
        assertTrue(body["activate"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result.ok)
        assertEquals(AccountType.UNDERGRADUATE, result.snapshot!!.accounts.single().accountType)
        assertFalse(result.toString().contains(password), "结果 toString 不得带上密码")
        assertFalse(result.message.contains(password))
    }

    @Test
    fun deletePostsIdAndConfirmTrue() = runTest {
        val calls = mutableListOf<Call>()
        val result = api(okWrite, calls = calls).deleteAccount("2023****0001", confirm = true)

        val call = calls.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("/api/accounts/delete", call.path)
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals("2023****0001", body["id"]!!.jsonPrimitive.content)
        assertTrue(body["confirm"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result.ok)
    }

    @Test
    fun passwordPostsIdAndNewPassword() = runTest {
        val calls = mutableListOf<Call>()
        val password = LEAK_PROBE
        val result = api(okWrite, calls = calls).updatePassword("2023****0001", password)

        val call = calls.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("/api/accounts/password", call.path)
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals("2023****0001", body["id"]!!.jsonPrimitive.content)
        assertEquals(password, body["newPassword"]!!.jsonPrimitive.content)
        assertEquals(setOf("id", "newPassword"), body.keys)
        assertTrue(result.ok)
        assertFalse(result.toString().contains(password), "结果 toString 不得带上密码")
        assertFalse(result.message.contains(password))
    }

    @Test
    fun missingTokenMapsTo401AndKeepsPasswordOutOfTheOutcome() = runTest {
        val password = LEAK_PROBE
        val result = api(
            body = """{"code":401,"data":null,"message":"未授权"}""",
            status = HttpStatusCode.Unauthorized,
        ).addAccount("202300000001", password)

        assertFalse(result.ok)
        assertEquals(401, result.code)
        assertEquals("未授权", result.message)
        assertNull(result.snapshot)
        assertFalse(result.message.contains(password))
        assertFalse(result.toString().contains(password))
    }

    @Test
    fun httpsRequiredMapsTo403AndKeepsPasswordOutOfTheOutcome() = runTest {
        val password = LEAK_PROBE
        val result = api(
            body = """{"code":403,"data":null,"message":"需要先开启 HTTPS（--https）"}""",
            status = HttpStatusCode.Forbidden,
        ).updatePassword("2023****0001", password)

        assertFalse(result.ok)
        assertEquals(403, result.code)
        assertTrue(result.message.contains("--https"))
        assertFalse(result.message.contains(password))
        assertFalse(result.toString().contains(password))
    }

    @Test
    fun malformedErrorResponseDoesNotLeakThePasswordInTheException() = runTest {
        val password = LEAK_PROBE
        val error = assertFailsWith<IllegalStateException> {
            api(body = "not-json", status = HttpStatusCode.InternalServerError)
                .addAccount("202300000001", password)
        }
        assertFalse(error.message.orEmpty().contains(password), "异常文案不得带上密码")
        assertFalse(error.stackTraceToString().contains(password), "堆栈也不得带上密码")
    }

    @Test
    fun campusApiFormThrowsWithoutCallingWriteEndpoints() = runTest {
        val calls = mutableListOf<Call>()
        val client = api(okWrite, calls = calls, mode = ApiMode.CAMPUS_API)
        assertFailsWith<UnsupportedOperationException> { client.switchAccount("2023****0001") }
        assertFailsWith<UnsupportedOperationException> { client.addAccount("u", LEAK_PROBE) }
        assertFailsWith<UnsupportedOperationException> { client.deleteAccount("2023****0001") }
        assertFailsWith<UnsupportedOperationException> { client.updatePassword("2023****0001", LEAK_PROBE) }
        assertEquals(0, calls.size, "形态不对就不该打这一枪")
    }
}

/** 故意选一个不会出现在普通文案里的密码，用来断言泄漏。 */
private const val LEAK_PROBE = "s3cret-LEAK-check-99"
