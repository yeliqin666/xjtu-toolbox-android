package com.xjtu.toolbox.platform

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 凭据保护那层包装（`ProtectedKeyValueStore`）的语义。 */
class ProtectedKeyValueStoreJvmTest {

    private val name = "protected_kv_test"

    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("xjtu-protected-kv-test").toFile()
        dataRootOverride = root
        wipeSecureStore(name)
    }

    @AfterTest
    fun tearDown() {
        wipeSecureStore(name)
        dataRootOverride = null
    }

    /** 盘上那份文件：`FileKeyValueStore` 的落点（`<数据根>/<名字>.properties`）。 */
    private fun file() = File(root, "$name.properties")

    private fun fileText(): String = file().takeIf { it.exists() }?.readText().orEmpty()

    private fun store(protector: SecretProtector? = XorSecretProtector()) =
        ProtectedKeyValueStore(secureKeyValueStore(name), protector)

    @Test
    fun `有保护器时盘上是密文，读回来是原值`() {
        val store = store()
        store.putString("password", "hunter2")

        assertTrue(file().exists(), "值要真的落盘（不是只进内存）")
        assertFalse(fileText().contains("hunter2"), "有保护器时盘上不能出现明文")
        // ⚠️ 不能在文件文本里断言完整的 `xjtu-sealed:1:fake:`：`Properties` 会把值里的冒号转义成 `\:`
        //（文件层面的事，读回来还是冒号）—— 所以这里只断言前缀。
        assertTrue(fileText().contains("xjtu-sealed"), "信封要写清是谁保护的")
        assertEquals("hunter2", store.getString("password"))
    }

    @Test
    fun `没有保护器时（Linux 的现状）字节就是这个值本身`() {
        val store = store(protector = null)
        store.putString("password", "hunter2")

        assertTrue(fileText().contains("password=hunter2"), "没有原生保护就是明文 —— 这一档不许被悄悄改成别的形状")
        assertEquals("hunter2", store.getString("password"))
    }

    @Test
    fun `盘上的密文被改过一个字节：抛，不返回密文原文`() {
        val fake = XorSecretProtector()
        val sealed = fake.protect("hunter2".encodeToByteArray())
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        secureKeyValueStore(name)
            .putString("password", ENVELOPE + "fake:" + Base64.getEncoder().encodeToString(tampered))

        val failure = assertFailsWith<SecretProtectionException> { store(fake).getString("password") }
        assertTrue(failure.message!!.contains("解不开"), "失败信息要说清是解不开：${failure.message}")
    }

    @Test
    fun `另一把钥匙（换用户或换机器）：抛`() {
        store(XorSecretProtector(key = 0x11)).putString("password", "hunter2")

        assertFailsWith<SecretProtectionException> {
            store(XorSecretProtector(key = 0x22)).getString("password")
        }
    }

    @Test
    fun `别的保护器写的信封（换平台）：抛，不当成明文`() {
        // 模拟「Windows 写下的凭据文件被拷到 Linux」：信封 id 是 dpapi，而本机没有保护器
        secureKeyValueStore(name).putString(
            "password",
            ENVELOPE + "dpapi:" + Base64.getEncoder().encodeToString("whatever".encodeToByteArray()),
        )

        val failure = assertFailsWith<SecretProtectionException> { store(protector = null).getString("password") }
        assertTrue(failure.message!!.contains("dpapi"), "失败信息要指出是谁保护的：${failure.message}")
    }

    @Test
    fun `这一轮之前的老明文凭据仍读得回来`() {
        // 老文件是直接写 Properties 的（`:desktop` 的 DesktopAuthLibraryJvmTest 就是这么造老文件的）
        secureKeyValueStore(name).putString("username", "2210000001")
        secureKeyValueStore(name).putString("password", "legacy-secret")

        val store = store()
        assertEquals("legacy-secret", store.getString("password"))
        assertEquals("2210000001", store.getString("username"))
    }

    @Test
    fun `原生保护不可用时：写直接失败，盘上不留明文（绝不静默降级）`() {
        val store = store(UnavailableSecretProtector())

        assertFailsWith<SecretProtectionException> { store.putString("password", "hunter2") }
        assertFalse(fileText().contains("hunter2"))
        assertNull(secureKeyValueStore(name).getString("password"))

        // 反过来（信封 id 对得上、但保护器自己失败）：读也是抛
        store(XorSecretProtector(id = "unavailable")).putString("password", "hunter2")
        assertFailsWith<SecretProtectionException> { store.getString("password") }
    }

    @Test
    fun `布尔 整数 集合档也走同一个信封，且空集合与没有键仍是两件事`() {
        val store = store()
        store.putBoolean("migration_done", true)
        store.putInt("rsa_key_time", 123)
        store.putStringSet("favorite_seats", setOf("7-101-1", "7-101-2"))
        store.putStringSet("empty_set", emptySet())

        val text = fileText()
        assertFalse(text.contains("migration_done=true"))
        assertFalse(text.contains("7-101-1"))
        assertEquals(true, store.getBoolean("migration_done", false))
        assertEquals(123, store.getInt("rsa_key_time", 0))
        assertEquals(setOf("7-101-1", "7-101-2"), store.getStringSet("favorite_seats"))
        assertEquals(emptySet(), store.getStringSet("empty_set"))
        assertNull(store.getStringSet("missing"))
    }

    @Test
    fun `remove 与 clear 直接落到盘上（不受信封影响）`() {
        val store = store()
        store.putString("password", "hunter2")
        assertTrue(store.contains("password"))

        store.remove("password")
        assertFalse(store.contains("password"))
        assertNull(store.getString("password"))

        store.putString("username", "2210000001")
        store.clear()
        assertNull(store.getString("username"))
    }

    private companion object {
        /** 与 `ProtectedKeyValueStore` 里的信封前缀同一个形状（那里是 `private`，测试自己拼一份）。 */
        const val ENVELOPE = "xjtu-sealed:1:"
    }
}
