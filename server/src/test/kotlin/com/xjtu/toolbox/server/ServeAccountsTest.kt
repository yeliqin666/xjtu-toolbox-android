package com.xjtu.toolbox.server

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.account.AccountsFixture
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject

/** `/api/accounts` 的契约验收（`docs/api-contract.md` §5.10）。 */
class ServeAccountsTest {

    @AfterTest
    fun resetSharedState() {
        dataRootOverride = null
        AccountContext.activeAccountId = null
    }

    /** 起一个只挂 `/api/accounts` 的真 serve（端口 0）+ 干净的临时数据根，把 [AccountStore] 与 [ServeSession] 直接交给它（两者都由调用方装配，见各自的参数说明）。 */
    private fun withAccountsServer(block: (AccountHarness) -> Unit) {
        // 进程级前置：必须在碰任何会话 / 客户端之前（见本函数的 KDoc）
        FakeCampusProxy.installFakeUpstreams()
        dataRootOverride = Files.createTempDirectory("xjtu-serve-accounts").toFile()
        wipeSecureStore(JvmCredentialStore.FILE_NAME)
        wipeSecureStore(AccountStore.FILE_NAME)
        AccountContext.activeAccountId = null

        val store = AccountStore()
        val session = ServeSession()
        val token = AccessToken.newToken()
        val (server, http) = startEndpointServer(token) { accountRoutes(session, store) }
        try {
            block(AccountHarness(http, store))
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            wipeSecureStore(JvmCredentialStore.FILE_NAME)
            wipeSecureStore(AccountStore.FILE_NAME)
            dataRootOverride = null
            AccountContext.activeAccountId = null
        }
    }

    /** 真 HTTP 客户端 + 账号表（见 [withAccountsServer]）。 */
    private class AccountHarness(private val http: EndpointHarness, private val store: AccountStore) {

        /** 带令牌那一发（[EndpointHarness] 的默认 bearer 就是本进程那一枚令牌）。 */
        fun get() = http.get(ACCOUNT_PATH)

        /** 不带令牌那一发（验闸门）。 */
        fun getWithoutToken() = http.get(ACCOUNT_PATH, bearer = null)
        /** 往账号表里塞一条（夹具给的编造值）。 */
        fun seedTable(
            accountId: String,
            password: String,
            accountType: AccountType = AccountType.UNDERGRADUATE,
            lastUsedAt: Long = AccountsFixture.LAST_USED_AT_MS,
        ) {
            store.upsert(
                Account(
                    accountId = accountId,
                    password = password,
                    accountType = accountType,
                    nickname = AccountsFixture.NICKNAME,
                    fpVisitorId = AccountsFixture.FP_VISITOR_ID,
                    rsaPublicKey = AccountsFixture.RSA_PUBLIC_KEY,
                    rsaKeyTime = lastUsedAt,
                    lastUsedAt = lastUsedAt,
                ),
            )
        }
    }

    /** 未登录 + 空表：`readOnly` 为 `true`；没有令牌照旧 401。 */
    @Test
    fun `未登录且账号表为空：如实给一张空表，能力开关说只读，没令牌 401`() {
        withAccountsServer { serve ->
            assertEquals(401, serve.getWithoutToken().statusCode(), "闸门先挡：账号表也在 /api 下面")
            assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(serve.getWithoutToken()))

            val response = serve.get()
            assertEquals(200, response.statusCode())
            assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
            val data = envelopeData(response)
            assertEquals(false, data.optBoolean("authenticated"))
            assertEquals(true, data.optBoolean("readOnly"), "本端没有写端点 ⇒ 能力开关如实报只读")
            assertTrue(data.arrOf("accounts").isEmpty(), "空表就是空表，不编一行出来")
        }
    }

    /** 登录态 + 表里一条别的账号：两条都列出来、只有打码形式、`active` 只标在 serve 当前会话那一条。 */
    @Test
    fun `登录态：账号表加上当前会话那一条，打码后逐字段，红线断言`() {
        withAccountsServer { serve ->
            // serve 这一侧的登录态：落盘凭据还在 ⇒ 冷启动静默恢复（不联网），与真跑一致
            JvmCredentialStore().save(AccountsFixture.STUDENT_NO, AccountsFixture.PASSWORD)
            serve.seedTable(
                accountId = AccountsFixture.SECOND_STUDENT_NO,
                password = AccountsFixture.SECOND_PASSWORD,
                accountType = AccountType.POSTGRADUATE,
            )

            val response = serve.get()
            assertEquals(200, response.statusCode())
            val data = envelopeData(response)
            assertEquals(true, data.optBoolean("authenticated"), "落盘凭据在 ⇒ 冷启动恢复过，算有会话")

            val rows = data.arrOf("accounts").map { it.jsonObject }
            assertEquals(2, rows.size, "表里那一条 + serve 当前会话那一条")

            val fromTable = rows.first()
            assertEquals(setOf("id", "accountType", "lastUsedAt", "active"), fromTable.keys)
            assertEquals(AccountsFixture.MASKED_SECOND_STUDENT_NO, fromTable.optString("id"))
            assertEquals("postgraduate", fromTable.optString("accountType"))
            assertEquals(AccountsFixture.LAST_USED_AT_MS, isoToEpochMs(fromTable.optString("lastUsedAt")))
            assertEquals(false, fromTable.optBoolean("active"))

            val fromSession = rows.last()
            assertEquals(AccountsFixture.MASKED_STUDENT_NO, fromSession.optString("id"))
            assertEquals("undergraduate", fromSession.optString("accountType"))
            assertEquals(true, fromSession.optBoolean("active"), "serve 这一侧当前会话就是它")
            assertTrue(fromSession["lastUsedAt"] is JsonNull, "不在表里 ⇒ 明确说没有（§4：null ≠ 缺字段）")
            assertNull(fromSession.optString("lastUsedAt"))

            assertNoIdentity(
                response.body(),
                "GET $ACCOUNT_PATH",
                listOf(
                    AccountsFixture.STUDENT_NO,
                    AccountsFixture.SECOND_STUDENT_NO,
                    AccountsFixture.PASSWORD,
                    AccountsFixture.SECOND_PASSWORD,
                    AccountsFixture.NICKNAME,
                    AccountsFixture.FP_VISITOR_ID,
                    AccountsFixture.RSA_PUBLIC_KEY,
                ),
            )
            for (key in listOf("password", "nickname", "fpVisitorId", "rsaPublicKey")) {
                assertFalse(response.body().contains("\"$key\""), "响应里不该有 $key 这个键")
            }
        }
    }

    /** 账号表里也有 serve 当前会话那一条：合成之后只有一行（去重），并带上表里的 `lastUsedAt`。 */
    @Test
    fun `表里也有当前会话那一条：合成成一行，并带上表里的 lastUsedAt`() {
        withAccountsServer { serve ->
            JvmCredentialStore().save(AccountsFixture.STUDENT_NO, AccountsFixture.PASSWORD)
            serve.seedTable(
                accountId = AccountsFixture.STUDENT_NO,
                password = AccountsFixture.PASSWORD,
            )

            val rows = envelopeData(serve.get()).arrOf("accounts").map { it.jsonObject }
            assertEquals(1, rows.size, "同一个学号在表里与在会话里是**一条**，不是两行")
            val row = rows.single()
            assertEquals(AccountsFixture.MASKED_STUDENT_NO, row.optString("id"))
            assertEquals(AccountsFixture.LAST_USED_AT_MS, isoToEpochMs(row.optString("lastUsedAt")))
            assertEquals(true, row.optBoolean("active"))
        }
    }

    /** `lastUsedAt` 是 ISO-8601 带时区（§4）⇒ 解回 epoch 毫秒比对。 */
    private fun isoToEpochMs(raw: String?): Long =
        OffsetDateTime.parse(requireNotNull(raw) { "这一格该有值（§4：null = 明确说没有）" }).toInstant().toEpochMilli()
}
