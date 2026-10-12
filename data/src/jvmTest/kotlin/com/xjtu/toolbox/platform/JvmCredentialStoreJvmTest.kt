package com.xjtu.toolbox.platform

import com.xjtu.toolbox.auth.XJTULogin
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **装上了保护器的真凭据存储**（`JvmCredentialStore` + `ProtectedKeyValueStore`）：
 * 这一轮 Windows 交付在 Linux 上能验到的最外一层。
 *
 * 与 [ProtectedKeyValueStoreJvmTest] 的分工：那边验信封的通用语义，这边验**凭据这一个 store**
 * 真的被保护了、且「用户看得见的契约」一条没变（账号/密码/指纹/账号类型/RSA 公钥的 24 小时窗口、
 * 退出登录即清除），以及 `:desktop` 那几条**手工写老明文凭据文件**的既有用例仍然成立。
 *
 * 用的还是假保护器（[XorSecretProtector]）—— 真 DPAPI 由 `SecretProtectorJvmTest` 里那两条
 * 「只在 Windows 上跑」的用例负责（本机 Linux 跳过，CI 的 `windows-latest` 会真跑）。
 */
class JvmCredentialStoreJvmTest {

    private val name = "xjtu_credentials_test"

    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("xjtu-credential-store-test").toFile()
        dataRootOverride = root
        wipeSecureStore(name)
    }

    @AfterTest
    fun tearDown() {
        wipeSecureStore(name)
        dataRootOverride = null
    }

    private fun file() = File(root, "$name.properties")

    private fun fileText(): String = file().takeIf { it.exists() }?.readText().orEmpty()

    private fun fake(key: Byte = 0x5A) = XorSecretProtector(key = key)

    @Test
    fun `有保护器时账号与密码都不落明文，读回来逐字相同`() {
        val store = JvmCredentialStore(name, fake())
        store.save("2210000001", "hunter2")

        assertFalse(fileText().contains("hunter2"), "密码不能出现在盘上")
        assertFalse(fileText().contains("2210000001"), "账号也不落明文（同一层保护，没有例外）")
        assertEquals("2210000001" to "hunter2", store.load())
    }

    @Test
    fun `没有原生保护的平台（Linux 或 macOS）字节与这一轮之前一模一样`() {
        val store = JvmCredentialStore(name, protector = null)
        store.save("2210000001", "hunter2")

        assertTrue(fileText().contains("password=hunter2"), "这一档刻意保持原状：文件 + 0600，明文")
        assertEquals("2210000001" to "hunter2", store.load())
    }

    @Test
    fun `默认参数按本机挑保护器：Windows 上是 DPAPI（本机若是 Windows，这条会真跑一遍）`() {
        val store = JvmCredentialStore(name)
        store.save("2210000001", "hunter2")

        val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        assertEquals(onWindows, fileText().contains("xjtu-sealed"), "只有 Windows 才有信封")
        assertEquals("2210000001" to "hunter2", store.load())
    }

    @Test
    fun `这一轮之前的老明文凭据文件仍读得回来`() {
        // `:desktop` 的 DesktopAuthLibraryJvmTest / DesktopAuthJwappJvmTest 就是这么造老文件的
        val raw = secureKeyValueStore(name)
        raw.putString("username", "2210000001")
        raw.putString("password", "legacy-secret")
        raw.putString("account_type", XJTULogin.AccountType.POSTGRADUATE.name)

        val store = JvmCredentialStore(name, fake())
        assertEquals("2210000001" to "legacy-secret", store.load())
        assertEquals(XJTULogin.AccountType.POSTGRADUATE, store.accountType)
    }

    @Test
    fun `另一台机器或另一个用户写的凭据：抛，不当成「没有凭据」`() {
        JvmCredentialStore(name, fake(key = 0x11)).save("2210000001", "hunter2")

        // 「读不出来」必须与「本来就没存过」分得开：后者会让用户以为自己是第一次登录，
        // 而前者要能说出「本机解不开这份凭据」（登录页据此提示重新登录）
        assertFailsWith<SecretProtectionException> { JvmCredentialStore(name, fake(key = 0x22)).load() }
    }

    @Test
    fun `设备指纹 账号类型 退出登录的清除：都按老口径来`() {
        val store = JvmCredentialStore(name, fake())
        store.save("2210000001", "hunter2")
        store.fpVisitorId = "fp-0001"
        store.accountType = XJTULogin.AccountType.POSTGRADUATE
        store.rsaPublicKey = "PUBKEY"

        assertEquals("fp-0001", store.fpVisitorId)
        assertEquals(XJTULogin.AccountType.POSTGRADUATE, store.accountType)
        assertEquals("PUBKEY", store.rsaPublicKey)

        store.clear()
        assertNull(store.load())
        assertNull(store.fpVisitorId)
        assertNull(store.rsaPublicKey)
        // `clear()` 只删那五个键（账号/密码/指纹/RSA 公钥/RSA 时间），**不动** `account_type` ——
        // 与这一轮之前的 `clear()` 逐字相同（这一端没有老数据要迁，行为也不改）
        assertEquals(
            XJTULogin.AccountType.POSTGRADUATE,
            store.accountType,
            "clear 之后 account_type 还在：它记的是「上一次用的是本科还是研究生」，不是本次登录的凭据",
        )
    }

    @Test
    fun `RSA 公钥的 24 小时窗口：过期就读不出来`() {
        val store = JvmCredentialStore(name, fake())
        store.rsaPublicKey = "PUBKEY"
        assertEquals("PUBKEY", store.rsaPublicKey)

        // 直接把时间戳改到 25 小时前（手工改盘上的值 = 老时间戳那一档）
        secureKeyValueStore(name).putString(
            "rsa_key_time",
            (System.currentTimeMillis() - 25 * 3600 * 1000L).toString(),
        )
        assertNull(store.rsaPublicKey, "过期后返回 null ⇒ 内核照常自己去取一份")
    }

    @Test
    fun `账号与密码任一为空都算没有（与 app 那一份同口径）`() {
        val store = JvmCredentialStore(name, fake())
        store.save("2210000001", "hunter2")

        val raw = secureKeyValueStore(name)
        raw.remove("password")
        assertNull(store.load())


        raw.putString("password", "")
        assertNull(store.load())

        // ⚠️ 另一档（用「没有保护器」的实例读一份已经封了信封的文件）会**抛**，不是回 null ——
        // 那是 `ProtectedKeyValueStoreJvmTest` 的「别的保护器写的信封」那条钉的事
    }
}
