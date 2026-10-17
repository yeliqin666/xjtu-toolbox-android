package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.desktop.jiaocai1.DesktopJiaocai1ShelfStore
import com.xjtu.toolbox.desktop.media.DesktopDownloadTaskStore
import com.xjtu.toolbox.desktop.score.DesktopScoreReportCache
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.keyValueStore
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

/** 多账号（`AppRoute.Accounts`）在桌面端的装配验收。 */
class DesktopAccountsJvmTest {

    private lateinit var dataRoot: File

    /** 与 `AccountStore` 同一个存储名。 */
    private val rawStore get() = secureKeyValueStore("xjtu_accounts")

    @BeforeTest
    fun setUp() {
        dataRoot = Files.createTempDirectory("xjtu-desktop-accounts-test").toFile()
        dataRootOverride = dataRoot
        rawStore.clear()
        AccountContext.activeAccountId = null
        // ⚠️ 先装常驻前置：`HttpClients.base` 是进程级 lazy，会抄走当时的 ProxySelector 与信任库，本类按名排在前面。
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
        assertEquals("2210000002" to "p-2210000002", auth.sessionManager.credentials)
        assertEquals("2210000002", AccountContext.activeAccountId)
        assertEquals("2210000002", auth.activeAccountId)
        assertTrue(auth.loggedIn)
        assertEquals("", auth.username)
        assertEquals("", auth.password)
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

        // ⚠️ 自己把命名空间文件建出来：空 cookie 罐不落盘，而这里要验删账号会清掉已存在的那份。
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
    fun `删账号带 deleteCache：清掉按账号分的那三处落盘，别的账号一条不动（S7b 的范围）`() = runBlocking {
        seed(account("2210000001"), account("2210000002"))
        val auth = DesktopAuth()
        val accounts = DesktopAccounts(auth)
        accounts.switchTo("2210000001")

        val mine = "2210000001"
        val other = "2210000002"
        val myReport = accountScopedStoreName(DesktopScoreReportCache.STORE_NAME, mine)
        val myQueue = accountScopedStoreName(DesktopDownloadTaskStore.STORE_NAME, mine)
        val myShelf = accountScopedStoreName(DesktopJiaocai1ShelfStore.STORE_NAME, mine)
        keyValueStore(myReport).putString(DesktopScoreReportCache.keyFor(mine), """{"at":1,"grades":[]}""")
        secureKeyValueStore(myQueue).putString("rows", "[]")
        secureKeyValueStore(myShelf).putString("rows", "[]")
        // 另一条账号的那两份（删 mine 时一条都不该动）
        val otherQueue = accountScopedStoreName(DesktopDownloadTaskStore.STORE_NAME, other)
        val otherShelf = accountScopedStoreName(DesktopJiaocai1ShelfStore.STORE_NAME, other)
        secureKeyValueStore(otherQueue).putString("rows", "[]")
        secureKeyValueStore(otherShelf).putString("rows", "[]")

        accounts.removeAccount(mine, deleteCache = true)

        assertNull(keyValueStore(myReport).getString(DesktopScoreReportCache.keyFor(mine)), "成绩报表缓存要清掉")
        assertNull(secureKeyValueStore(myQueue).getString("rows"), "下载队列要清掉")
        assertNull(secureKeyValueStore(myShelf).getString("rows"), "教材书架要清掉")
        assertEquals("[]", secureKeyValueStore(otherQueue).getString("rows"), "其余账号的队列一条都不能动")
        assertEquals("[]", secureKeyValueStore(otherShelf).getString("rows"), "其余账号的书架一条都不能动")
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
