package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 多账号（`AppRoute.Accounts`，第 29 条真数据路由）在桌面端的**装配验收**。
 *
 * 这一条路由的屏（`:core` 的 `AccountManagerScreen`）本轮才搬完 —— 搬迁前它在 `:app`，桌面只能落
 * `NotPortedScreen`，而 `DesktopAuth` 的 KDoc 里那句「多账号 = 单账号，换账号 = 退出再登」
 * 就是这一轮要划掉的那一行。
 *
 * 这里钉住的是**动作本体**（`:app` 的 `AccountManager` 那一层在桌面的对应物 [DesktopAccounts]）：
 *
 *  1. **切账号真的换绑会话命名空间**：`SessionManager.reconfigureForAccount(suffix)` 之后
 *     backend 绑到 `cookies_*_<学号>`（与其余端同一套命名），凭据/指纹/公钥从账号表恢复，
 *     激活指针落盘，`lastUsedAt` 刷新；界面输入框**保持空**（红线：学号不上屏）；
 *  2. **新增账号的失败分支不发请求**：空学号/空密码、以及「该账号已存在」在探活之前就返回 ——
 *     这两条是可以在没有网络的机器上验的（探活那一段要真登一次，见下面那条注释）；
 *  3. **删账号的顺序**：删非激活的不动指针；删激活的切到剩下那条；删最后一条回匿名命名空间；
 *  4. **账号表就是 `:app` 那一份**：存储名 `xjtu_accounts`、键名 `accounts` / `active_account`
 *     逐字相同（Android 的 actual 是同一个 `SecurePrefs` 文件）—— 改一个字符老用户就找不到了。
 *
 * ## 哪些没在这里验（如实）
 *
 * - **新增成功那条路**要真过一遍统一认证（CAS 表单 POST + 教务/一网通办探活）⇒ 属于「有网络的
 *   机器 + 真账号」的验收，与其余站点登录同一处境（`:desktop` 那批 `DesktopAuth*JvmTest` 用
 *   `:testkit` 的假上游验登录，假上游里没有「第二条账号」这一档）。这里只钉住它**之前**的判据。
 * - 昵称（`:app` 用一网通办回填）在桌面端没有等价物 ⇒ 一直是 null，见 `DesktopAccounts` 的 KDoc。
 */
class DesktopAccountsJvmTest {

    private lateinit var dataRoot: File

    /** 与 `AccountStore` 用的是同一个存储名（搬迁前 Android 那份 `SecurePrefs` 的文件名）。 */
    private val rawStore get() = secureKeyValueStore("xjtu_accounts")

    @BeforeTest
    fun setUp() {
        dataRoot = Files.createTempDirectory("xjtu-desktop-accounts-test").toFile()
        dataRootOverride = dataRoot
        rawStore.clear()
        AccountContext.activeAccountId = null
        // ⚠️ 顺序要紧：`HttpClients.base` 是**进程级 `by lazy`**，它会把当时的默认 `ProxySelector`
        // 与信任库抄进自己的配置。这个类会构造 `DesktopAuth`（⇒ 建会话 ⇒ 碰 `HttpClients`），
        // 而它按类名排在 `DesktopAuth*JvmTest` 那一批**之前** —— 不在这里先装上那两件常驻前置，
        // 后面那些「假上游」用例会直连真站点（RenderScreens 的 KDoc 里那条实测坑）。
        // 幂等，而且它只装「读可变端口的 selector」，不依赖哪个假上游在跑。
        com.xjtu.toolbox.FakeCampusProxy.installFakeUpstreams()
    }

    @AfterTest
    fun tearDown() {
        rawStore.clear()
        wipeSecureStore("xjtu_accounts")
        AccountContext.activeAccountId = null
        dataRootOverride = null
    }

    private fun seed(vararg accounts: Account): AccountStore {
        val store = AccountStore()
        accounts.forEach { store.upsert(it) }
        return store
    }

    private fun account(id: String, password: String = "p-$id") =
        Account(accountId = id, password = password, accountType = AccountType.UNDERGRADUATE)

    @Test
    fun `切账号：换绑命名空间、恢复凭据、激活指针落盘，界面上不留学号`() = runBlocking {
        seed(account("2210000001"), account("2210000002"))
        val auth = DesktopAuth()
        val accounts = DesktopAccounts(auth)

        assertEquals(listOf("2210000001", "2210000002"), accounts.accountList().map { it.accountId })
        // 还没有当前账号（测试里不走 restore()）
        assertFalse(auth.loggedIn)
        assertNull(auth.activeAccountId)

        val switched = accounts.switchTo("2210000002")

        assertEquals("2210000002", switched?.accountId)
        // ① 会话内核换到了这条账号的命名空间（凭据也换成了它的）
        assertEquals("2210000002" to "p-2210000002", auth.sessionManager.credentials)
        // ② `AccountContext` 与这份对象的可观察镜像同刻写入
        assertEquals("2210000002", AccountContext.activeAccountId)
        assertEquals("2210000002", auth.activeAccountId)
        // ③ 界面状态：进主界面，但两个输入框是空的（红线：学号不上屏）
        assertTrue(auth.loggedIn)
        assertEquals("", auth.username)
        assertEquals("", auth.password)
        // ④ 激活指针与 lastUsedAt 落盘
        assertEquals("2210000002", AccountStore().activeAccountId())
        assertTrue(AccountStore().get("2210000002")!!.lastUsedAt > 0L)

        // 切到不存在的账号：什么都不改
        assertNull(accounts.switchTo("2210999999"))
        assertEquals("2210000002", AccountContext.activeAccountId)
    }

    @Test
    fun `切到当前账号是幂等的：只刷新 lastUsedAt`() = runBlocking {
        seed(account("2210000001"))
        val auth = DesktopAuth()
        val accounts = DesktopAccounts(auth)

        accounts.switchTo("2210000001")
        val firstAt = AccountStore().get("2210000001")!!.lastUsedAt
        // 手动把它按回去，确认第二次切换确实刷新了它（而不是「碰巧相同」）
        AccountStore().update("2210000001") { it.copy(lastUsedAt = 1L) }

        accounts.switchTo("2210000001")
        assertTrue(AccountStore().get("2210000001")!!.lastUsedAt >= firstAt)
        assertEquals("2210000001", AccountContext.activeAccountId)
    }

    @Test
    fun `新增账号：空学号、空密码、已存在都在探活之前就失败`() = runBlocking {
        seed(account("2210000001"))
        val auth = DesktopAuth()
        val accounts = DesktopAccounts(auth)

        val blankId = accounts.addAccount("  ", "p", AccountType.UNDERGRADUATE)
        assertTrue(blankId.isFailure)
        assertEquals("学号和密码不能为空", blankId.exceptionOrNull()?.message)

        val blankPwd = accounts.addAccount("2210000009", " ", AccountType.UNDERGRADUATE)
        assertTrue(blankPwd.isFailure)
        assertEquals("学号和密码不能为空", blankPwd.exceptionOrNull()?.message)

        val existing = accounts.addAccount("2210000001", "p", AccountType.UNDERGRADUATE)
        assertTrue(existing.isFailure)
        assertEquals("该账号已存在", existing.exceptionOrNull()?.message)

        // 三条都没落库、也没动命名空间
        assertEquals(listOf("2210000001"), AccountStore().list().map { it.accountId })
        assertNull(AccountContext.activeAccountId)
        assertNull(auth.sessionManager.credentials)
    }

    @Test
    fun `删账号：非激活的不动指针，激活的切到剩下那条，最后一条回匿名`() = runBlocking {
        seed(account("2210000001"), account("2210000002"))
        val auth = DesktopAuth()
        val accounts = DesktopAccounts(auth)
        accounts.switchTo("2210000001")

        // 删不存在的
        assertFalse(accounts.removeAccount("2210999999", deleteCache = true))

        // 删**非**激活那条：指针不动
        assertTrue(accounts.removeAccount("2210000002", deleteCache = false))
        assertEquals("2210000001", AccountContext.activeAccountId)
        assertEquals("2210000001", AccountStore().activeAccountId())
        assertEquals(listOf("2210000001"), AccountStore().list().map { it.accountId })

        // 再补一条，然后删掉**激活**那条 ⇒ 切到剩下那条
        AccountStore().upsert(account("2210000003"))
        assertTrue(accounts.removeAccount("2210000001", deleteCache = true))
        assertEquals("2210000003", AccountContext.activeAccountId)
        assertEquals("2210000003", auth.activeAccountId)
        assertEquals("2210000003", AccountStore().activeAccountId())
        assertEquals("2210000003" to "p-2210000003", auth.sessionManager.credentials)

        // 删掉最后一条 ⇒ 回匿名命名空间、界面回登录页
        assertTrue(accounts.removeAccount("2210000003", deleteCache = true))
        assertNull(AccountContext.activeAccountId)
        assertNull(auth.activeAccountId)
        assertNull(AccountStore().activeAccountId())
        assertNull(auth.sessionManager.credentials)
        assertFalse(auth.loggedIn)
        assertTrue(AccountStore().list().isEmpty())
    }

    @Test
    fun `删账号会清掉它的会话文件，但不动别的账号`() = runBlocking {
        seed(account("2210000001"), account("2210000002"))
        val auth = DesktopAuth()
        val accounts = DesktopAccounts(auth)
        accounts.switchTo("2210000001")
        accounts.switchTo("2210000002")
        accounts.switchTo("2210000001")

        // 两个账号各自有自己那份命名空间文件（cookie 罐 + 站点快照，名里带学号后缀）。
        // ⚠️ 这里**自己把文件建出来**（而不是靠切账号产生）：空的 cookie 罐不写盘（没有任何 cookie
        // 时 `PersistentCookieJar` 不会落文件），而这条用例要验的是「删账号会清掉**已存在**的那一份」，
        // 用哪一条缝建出它不影响结论 —— 用的就是 `SessionBackend.wipe` 会清的那两个存储名。
        val suffix1 = AccountContext.suffixFor("2210000001")
        val suffix2 = AccountContext.suffixFor("2210000002")
        val file1 = File(dataRoot, "sites_normal$suffix1.properties")
        val file2 = File(dataRoot, "sites_normal$suffix2.properties")
        secureKeyValueStore("sites_normal$suffix1").putString("library", "fixture-snapshot")
        secureKeyValueStore("sites_normal$suffix2").putString("library", "fixture-snapshot")
        assertTrue(file1.exists(), "应当有它自己那份站点快照：${file1.absolutePath}")
        assertTrue(file2.exists(), "另一条账号的也应当还在：${file2.absolutePath}")

        accounts.removeAccount("2210000001", deleteCache = true)

        assertFalse(file1.exists(), "删掉的账号的会话文件要一并清掉")
        assertTrue(file2.exists(), "其余账号的会话文件一条都不能动")
    }

    @Test
    fun `改密码：落盘，并且当前账号同步到会话内核`() = runBlocking {
        seed(account("2210000001"), account("2210000002"))
        val auth = DesktopAuth()
        val accounts = DesktopAccounts(auth)
        accounts.switchTo("2210000001")

        accounts.updatePassword("2210000001", "new-pwd")
        assertEquals("new-pwd", AccountStore().get("2210000001")!!.password)
        assertEquals("2210000001" to "new-pwd", auth.sessionManager.credentials)

        // 改**非**当前账号的：只落盘，不动正在用的那份凭据
        accounts.updatePassword("2210000002", "other-pwd")
        assertEquals("other-pwd", AccountStore().get("2210000002")!!.password)
        assertEquals("2210000001" to "new-pwd", auth.sessionManager.credentials)
    }

    @Test
    fun `账号表就是 app 那一份：存储名与键名逐字相同`() = runBlocking {
        seed(account("2210000001"))
        val auth = DesktopAuth()
        DesktopAccounts(auth).switchTo("2210000001")

        // 直接读底层那份密文存储：Android 的 actual 是同一个 `SecurePrefs` 文件、同一批键
        assertEquals("2210000001", rawStore.getString("active_account"))
        assertTrue(rawStore.getString("accounts")!!.contains("2210000001"))
        // 磁盘上真的建出了那个文件（0600 的 Properties）
        val file = File(dataRoot, "${AccountStore.FILE_NAME}.properties")
        assertTrue(file.exists(), "账号表应当落盘：${file.absolutePath}")
        val props = Properties().apply { file.inputStream().use { load(it) } }
        assertEquals("2210000001", props.getProperty("active_account"))
    }
}
