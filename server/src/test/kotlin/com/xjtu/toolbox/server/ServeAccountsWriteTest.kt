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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

/**
 * `/api/accounts` 四条**写**端点的契约验收（`docs/api-contract.md` §5.10，
 * `docs/parity-ledger.md` §6.1 会议 #10 = 丙）。
 *
 * 两组前置：HTTPS 打开（四条写端点要求它）与 HTTPS 关掉（一律 403）。
 * 全程只碰临时数据根里的账号表与凭据（`dataRootOverride`），**不打真站点、不动宿主凭据**。
 */
class ServeAccountsWriteTest {

    @AfterTest
    fun resetSharedState() {
        dataRootOverride = null
        AccountContext.activeAccountId = null
    }

    /** 起一个只挂 `/api/accounts` 的真 serve（端口 0）+ 干净临时数据根；[https] 决定写端点开不开。 */
    private fun withAccountsServer(https: Boolean = true, block: (WriteHarness) -> Unit) {
        // 进程级前置：必须在碰任何会话 / 客户端之前（见 FakeCampusProxy.installFakeUpstreams）
        FakeCampusProxy.installFakeUpstreams()
        dataRootOverride = Files.createTempDirectory("xjtu-serve-accounts-write").toFile()
        wipeSecureStore(JvmCredentialStore.FILE_NAME)
        wipeSecureStore(AccountStore.FILE_NAME)
        AccountContext.activeAccountId = null

        val store = AccountStore()
        val session = ServeSession()
        val token = AccessToken.newToken()
        val (server, http) = startEndpointServer(token) { accountRoutes(session, store, httpsEnabled = https) }
        try {
            block(WriteHarness(http, token, store))
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            wipeSecureStore(JvmCredentialStore.FILE_NAME)
            wipeSecureStore(AccountStore.FILE_NAME)
            dataRootOverride = null
            AccountContext.activeAccountId = null
        }
    }

    /** 真 HTTP 客户端 + 账号表（见 [withAccountsServer]）。 */
    private class WriteHarness(
        private val http: EndpointHarness,
        private val token: String,
        val store: AccountStore,
    ) {

        fun get() = http.get(ACCOUNT_PATH)

        /** 打到某条写端点（[path] 是相对段，如 `switch`）；`bearer = null` 表示不带令牌。 */
        fun post(path: String, body: String, bearer: String? = token) =
            http.postJson("$ACCOUNT_PATH/$path", body, bearer = bearer)
    }

    private fun addBody(username: String, password: String, activate: Boolean = false) =
        """{"username":"$username","password":"$password","activate":$activate}"""

    // ── HTTPS 硬前提 ────────────────────────────────────────

    /** 明文 HTTP：四条写端点一律 403 + 「怎么开 HTTPS」，读端点照旧，账号表一点没动。 */
    @Test
    fun `没开 HTTPS：四条写端点一律 403 并给出开 HTTPS 的办法，读端点不受影响`() {
        withAccountsServer(https = false) { serve ->
            val bodies = mapOf(
                ACCOUNT_SWITCH to """{"id":"${AccountsFixture.MASKED_STUDENT_NO}"}""",
                ACCOUNT_ADD to addBody(AccountsFixture.STUDENT_NO, AccountsFixture.PASSWORD),
                ACCOUNT_DELETE to """{"id":"${AccountsFixture.MASKED_STUDENT_NO}","confirm":true}""",
                ACCOUNT_PASSWORD to """{"id":"${AccountsFixture.MASKED_STUDENT_NO}","newPassword":"x"}""",
            )
            for ((path, body) in bodies) {
                val response = serve.post(path, body)
                assertEquals(403, response.statusCode(), "$ACCOUNT_PATH/$path 在明文 HTTP 上必须拒绝")
                assertEquals(ApiErrors.HTTPS_REQUIRED, envelopeCode(response))
                val message = envelopeMessage(response).orEmpty()
                assertTrue(message.contains("--https"), "拒绝文案要说清怎么开：$message")
                assertTrue(message.contains("keytool"), "拒绝文案要给出自签证书的办法：$message")
                assertTrue(message.contains("serve.p12"), "拒绝文案要指出证书放在哪：$message")
            }
            assertTrue(serve.store.list().isEmpty(), "拒绝了就什么都不该动")

            val read = serve.get()
            assertEquals(200, read.statusCode())
            val data = envelopeData(read)
            assertEquals(true, data.optBoolean("readOnly"), "没开 HTTPS ⇒ 能力开关如实报只读")
            assertTrue(data.arrOf("accounts").isEmpty())
        }
    }

    /** 写端点也在令牌闸门下面：没令牌连 403 都轮不到。 */
    @Test
    fun `没令牌：四条写端点照旧 401`() {
        withAccountsServer { serve ->
            val response = serve.post(ACCOUNT_ADD, addBody(AccountsFixture.STUDENT_NO, AccountsFixture.PASSWORD), bearer = null)
            assertEquals(401, response.statusCode())
            assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(response))
            assertTrue(serve.store.list().isEmpty())
        }
    }

    // ── add ─────────────────────────────────────────────────

    /** 新增一条：入表、快照里以打码形式回来、密码与身份字段一个都不进响应；再来一次不产生第二行。 */
    @Test
    fun `add：入表并打码回显，重复提交不产生第二行，响应里没有密码`() {
        withAccountsServer { serve ->
            val first = serve.post(ACCOUNT_ADD, addBody(AccountsFixture.STUDENT_NO, AccountsFixture.PASSWORD))
            assertEquals(200, first.statusCode())
            assertEquals(ApiEnvelope.CODE_OK, envelopeCode(first))
            val data = envelopeData(first)
            assertEquals(true, data.optBoolean("ok"))
            assertEquals(true, data.optBoolean("changed"))

            val rows = data.getValue("snapshot").jsonObject.arrOf("accounts")
            assertEquals(1, rows.size)
            assertEquals(AccountsFixture.MASKED_STUDENT_NO, rows.single().jsonObject.optString("id"))
            assertEquals("undergraduate", rows.single().jsonObject.optString("accountType"))
            assertNoIdentity(
                first.body(),
                "POST $ACCOUNT_PATH/add",
                listOf(AccountsFixture.STUDENT_NO, AccountsFixture.PASSWORD),
            )

            // 幂等：同一条再提交一次 = 目标态本来就成立（`ok:true` + `changed:false`），表里还是一条
            val again = serve.post(ACCOUNT_ADD, addBody(AccountsFixture.STUDENT_NO, AccountsFixture.PASSWORD))
            assertEquals(200, again.statusCode())
            assertEquals(false, envelopeData(again).optBoolean("changed"), "重复提交不算改动")
            assertEquals(1, serve.store.list().size, "重复提交不许产生第二行")
        }
    }

    /** 同学号但密码不同：409，且**不覆盖**已经记下的那条（改密有专门的端点）。 */
    @Test
    fun `add：同学号不同密码给 409，已记下的那条不动`() {
        withAccountsServer { serve ->
            serve.post(ACCOUNT_ADD, addBody(AccountsFixture.STUDENT_NO, AccountsFixture.PASSWORD))
            val conflict = serve.post(ACCOUNT_ADD, addBody(AccountsFixture.STUDENT_NO, AccountsFixture.SECOND_PASSWORD))
            assertEquals(409, conflict.statusCode())
            assertEquals(ApiErrors.ACTION_REJECTED, envelopeCode(conflict))
            assertTrue(envelopeMessage(conflict).orEmpty().contains(ACCOUNT_PASSWORD), "文案要指出改密走哪条端点")
            assertEquals(AccountsFixture.PASSWORD, serve.store.get(AccountsFixture.STUDENT_NO)?.password)
        }
    }

    /** `activate:true`：顺手认成当前账号，内存会话与落盘凭据一起换。 */
    @Test
    fun `add activate：当前会话换成它，落盘凭据也换了`() {
        withAccountsServer { serve ->
            val response = serve.post(
                ACCOUNT_ADD,
                """{"username":"${AccountsFixture.STUDENT_NO}","password":"${AccountsFixture.PASSWORD}","accountType":"postgraduate","activate":true}""",
            )
            assertEquals(200, response.statusCode())
            val snapshot = envelopeData(response).getValue("snapshot").jsonObject
            assertEquals(true, snapshot.optBoolean("authenticated"))
            val row = snapshot.arrOf("accounts").single().jsonObject
            assertEquals(true, row.optBoolean("active"))
            assertEquals("postgraduate", row.optString("accountType"))

            assertEquals(AccountType.POSTGRADUATE, serve.store.get(AccountsFixture.STUDENT_NO)?.accountType)
            assertEquals(
                AccountsFixture.STUDENT_NO to AccountsFixture.PASSWORD,
                JvmCredentialStore().load(),
                "认成当前账号就得落盘，否则重启后恢复不到它",
            )
        }
    }

    // ── switch ──────────────────────────────────────────────

    /** 打码 id 直接当句柄用；切过去之后 `active` 跟着走；再切同一条是幂等（`changed:false`）。 */
    @Test
    fun `switch：打码 id 认得出，active 跟着走，重复切同一条 changed 为 false`() {
        withAccountsServer { serve ->
            serve.post(ACCOUNT_ADD, addBody(AccountsFixture.STUDENT_NO, AccountsFixture.PASSWORD))
            serve.post(ACCOUNT_ADD, addBody(AccountsFixture.SECOND_STUDENT_NO, AccountsFixture.SECOND_PASSWORD))

            val switched = serve.post(ACCOUNT_SWITCH, """{"id":"${AccountsFixture.MASKED_SECOND_STUDENT_NO}"}""")
            assertEquals(200, switched.statusCode())
            assertEquals(true, envelopeData(switched).optBoolean("changed"))
            val snapshot = envelopeData(switched).getValue("snapshot").jsonObject
            assertEquals(true, snapshot.optBoolean("authenticated"))
            val active = snapshot.arrOf("accounts").map { it.jsonObject }.single { it.optBoolean("active") == true }
            assertEquals(AccountsFixture.MASKED_SECOND_STUDENT_NO, active.optString("id"))
            assertNoIdentity(
                switched.body(),
                "POST $ACCOUNT_PATH/switch",
                listOf(AccountsFixture.SECOND_STUDENT_NO, AccountsFixture.SECOND_PASSWORD),
            )

            val again = serve.post(ACCOUNT_SWITCH, """{"id":"${AccountsFixture.MASKED_SECOND_STUDENT_NO}"}""")
            assertEquals(200, again.statusCode())
            assertEquals(false, envelopeData(again).optBoolean("changed"), "已经是它 ⇒ 只刷新上次使用时间")
        }
    }

    /** 认不出的 id ⇒ 404；打码形式对上不止一条 ⇒ 409（不替调用方猜一条）。 */
    @Test
    fun `switch：认不出的 id 给 404，打码撞车给 409`() {
        withAccountsServer { serve ->
            val missing = serve.post(ACCOUNT_SWITCH, """{"id":"${AccountsFixture.MASKED_STUDENT_NO}"}""")
            assertEquals(404, missing.statusCode())
            assertEquals(ApiErrors.NOT_FOUND, envelopeCode(missing))

            // 两个学号打码后同形（互相撞车）：故意造一对只差中段的账号
            serve.store.upsert(Account(accountId = "20230000aa", password = AccountsFixture.PASSWORD))
            serve.store.upsert(Account(accountId = "2023zz00aa", password = AccountsFixture.PASSWORD))
            val masked = maskedAccountId("20230000aa")
            assertEquals(masked, maskedAccountId("2023zz00aa"), "这一对夹具本来就该撞车")
            val ambiguous = serve.post(ACCOUNT_SWITCH, """{"id":"$masked"}""")
            assertEquals(409, ambiguous.statusCode())
            assertEquals(ApiErrors.ACTION_REJECTED, envelopeCode(ambiguous))
        }
    }

    // ── delete ──────────────────────────────────────────────

    /** 少一个 `confirm:true` 就是 400 且什么都没动；确认后删掉；再删一次是幂等（`changed:false`）。 */
    @Test
    fun `delete：没 confirm 就 400 且不删，确认后删掉，重复删除 changed 为 false`() {
        withAccountsServer { serve ->
            serve.post(ACCOUNT_ADD, addBody(AccountsFixture.SECOND_STUDENT_NO, AccountsFixture.SECOND_PASSWORD))
            val masked = AccountsFixture.MASKED_SECOND_STUDENT_NO

            val unchecked = serve.post(ACCOUNT_DELETE, """{"id":"$masked"}""")
            assertEquals(400, unchecked.statusCode())
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(unchecked))
            assertEquals(1, serve.store.list().size, "没有二次确认就不许动")

            val deleted = serve.post(ACCOUNT_DELETE, """{"id":"$masked","confirm":true}""")
            assertEquals(200, deleted.statusCode())
            assertEquals(true, envelopeData(deleted).optBoolean("changed"))
            assertTrue(envelopeData(deleted).getValue("snapshot").jsonObject.arrOf("accounts").isEmpty())
            assertNull(serve.store.get(AccountsFixture.SECOND_STUDENT_NO))

            val again = serve.post(ACCOUNT_DELETE, """{"id":"$masked","confirm":true}""")
            assertEquals(200, again.statusCode())
            assertEquals(false, envelopeData(again).optBoolean("changed"), "目标态「它不在表里」已经成立")
        }
    }

    /** 删掉的正是当前账号：serve 回到未登录，落盘凭据被清（下一个账号要显式 switch）。 */
    @Test
    fun `delete 当前账号：serve 回到未登录，落盘凭据清掉`() {
        withAccountsServer { serve ->
            serve.post(
                ACCOUNT_ADD,
                """{"username":"${AccountsFixture.STUDENT_NO}","password":"${AccountsFixture.PASSWORD}","activate":true}""",
            )
            assertFalse(JvmCredentialStore().load() == null, "前置：认成当前账号之后凭据是有的")

            val deleted = serve.post(
                ACCOUNT_DELETE,
                """{"id":"${AccountsFixture.MASKED_STUDENT_NO}","confirm":true}""",
            )
            assertEquals(200, deleted.statusCode())
            val snapshot = envelopeData(deleted).getValue("snapshot").jsonObject
            assertEquals(false, snapshot.optBoolean("authenticated"), "删掉当前账号 ⇒ 回到未登录")
            assertTrue(snapshot.arrOf("accounts").isEmpty())
            assertNull(JvmCredentialStore().load(), "凭据也要清")
            assertNull(AccountContext.activeAccountId)
        }
    }

    // ── password ────────────────────────────────────────────

    /** 改密入库；新密码与旧的一样是幂等（`changed:false`）；改的是当前账号时落盘凭据一起换。 */
    @Test
    fun `password：改密入库，重复提交 changed 为 false，当前账号的落盘凭据一起换`() {
        withAccountsServer { serve ->
            serve.post(
                ACCOUNT_ADD,
                """{"username":"${AccountsFixture.STUDENT_NO}","password":"${AccountsFixture.PASSWORD}","activate":true}""",
            )
            val masked = AccountsFixture.MASKED_STUDENT_NO
            val newPassword = AccountsFixture.SECOND_PASSWORD

            val changed = serve.post(ACCOUNT_PASSWORD, """{"id":"$masked","newPassword":"$newPassword"}""")
            assertEquals(200, changed.statusCode())
            assertEquals(true, envelopeData(changed).optBoolean("changed"))
            assertEquals(newPassword, serve.store.get(AccountsFixture.STUDENT_NO)?.password)
            assertEquals(
                AccountsFixture.STUDENT_NO to newPassword,
                JvmCredentialStore().load(),
                "改的是当前账号 ⇒ 落盘那份也要换，否则重启后又用回旧密码",
            )
            assertNoIdentity(changed.body(), "POST $ACCOUNT_PATH/password", listOf(newPassword, AccountsFixture.PASSWORD))

            val again = serve.post(ACCOUNT_PASSWORD, """{"id":"$masked","newPassword":"$newPassword"}""")
            assertEquals(200, again.statusCode())
            assertEquals(false, envelopeData(again).optBoolean("changed"), "密码本来就是这个")

            val missing = serve.post(ACCOUNT_PASSWORD, """{"id":"${AccountsFixture.MASKED_SECOND_STUDENT_NO}","newPassword":"x"}""")
            assertEquals(404, missing.statusCode(), "表里没有的账号没有可改的密码")
        }
    }
}
