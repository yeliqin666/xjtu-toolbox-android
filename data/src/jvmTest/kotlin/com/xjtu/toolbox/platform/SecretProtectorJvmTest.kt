package com.xjtu.toolbox.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 保护器缝本身的两件事：**平台分派**（Windows 给 DPAPI，其余平台什么都不给）与
 * **真 DPAPI 的行为**。
 *
 * 分派那几条在 Linux 上就能验（判据是纯函数，`os.name` 是参数），DPAPI 那两条只在
 * `os.name` 是 Windows 时执行 —— 本机（Linux）跳过，CI 的 `windows-latest` 会真跑
 *（见 `docs/windows-delivery.md` §7「未验证项」：那两条是**留给 CI 验**的，不是不验）。
 *
 * 「密文落盘 → 读回解密 → 篡改/换用户解不开」这套**存储层**语义在
 * [ProtectedKeyValueStoreJvmTest] 里用假保护器钉住（那几条在任何平台都能跑）。
 */
class SecretProtectorJvmTest {

    private fun isWindows() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Test
    fun `平台分派：Windows 给 DPAPI，其余平台什么都不给`() {
        assertSame(DpapiSecretProtector, secretProtectorForPlatform("Windows 10"))
        assertSame(DpapiSecretProtector, secretProtectorForPlatform("Windows 11"))
        assertSame(DpapiSecretProtector, secretProtectorForPlatform("Windows Server 2022"))
        assertEquals("dpapi", DpapiSecretProtector.id)

        // 没有原生保护就是没有：不造一个「身份保护器」出来假装有（设计文档 §5.1 的 Linux 口径）
        listOf("Linux", "Mac OS X", "FreeBSD", "").forEach { os ->
            assertEquals(null, secretProtectorForPlatform(os), "「$os」上不该有保护器")
        }
    }

    @Test
    fun `默认参数按本机 os name 分派（在两种平台上都是对的）`() {
        // 不写死「本机一定是 Linux」：这条断言在 CI 的 windows-latest 上也要成立
        assertEquals(isWindows(), secretProtectorForPlatform() != null)
    }

    @Test
    fun `DPAPI：往返、密文不是明文、篡改解不开（只在 Windows 上真跑）`() {
        if (!isWindows()) return

        val plain = "hunter2-密码-🔐".encodeToByteArray()
        val sealed = DpapiSecretProtector.protect(plain)

        assertFalse(sealed.contentEquals(plain), "DPAPI 必须真的改变字节")
        assertContentEquals(plain, DpapiSecretProtector.unprotect(sealed), "本机本用户要能原样解回来")

        // 篡改任意一个字节 ⇒ 解不开（DPAPI 自己带完整性），且必须是本缝的异常类型
        val tampered = sealed.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val failure = assertFailsWith<SecretProtectionException> { DpapiSecretProtector.unprotect(tampered) }
        assertTrue(failure.message!!.contains("CryptUnprotectData"), "失败信息要指出是哪一步：${failure.message}")
    }

    @Test
    fun `DPAPI：每次保护都带新盐（两次密文不同，但都解回同一份明文）`() {
        if (!isWindows()) return

        val plain = "hunter2".encodeToByteArray()
        val first = DpapiSecretProtector.protect(plain)
        val second = DpapiSecretProtector.protect(plain)

        assertContentEquals(plain, DpapiSecretProtector.unprotect(first))
        assertContentEquals(plain, DpapiSecretProtector.unprotect(second))
        // DPAPI 每次都会换盐 ⇒ 两次密文不同（这不是缺陷，是它自带随机性）
        assertFalse(first.contentEquals(second))
    }
}
