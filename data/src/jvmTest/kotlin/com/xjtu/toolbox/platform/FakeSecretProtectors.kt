package com.xjtu.toolbox.platform

/**
 * 测试用的**假保护器**（`:data` 的 `jvmTest` 专用，不进任何交付物 —— 与 `:testkit` 同一条纪律，
 * 只是它小到不值得单独开一个模块）。
 *
 * 为什么需要它：真的 [DpapiSecretProtector] 只有 Windows 上才跑得起来，而「密文落盘 → 读回解密 →
 * 篡改/换用户解不开」这套语义**必须**在 Linux 上（也就是本机与核心门禁里）被钉住。所以这里造一个
 * 行为同形的替身：它**真的改动字节**（异或 + 一把「钥匙」）、**真的带完整性校验**（篡改会被发现）、
 * 并且**只有同一把钥匙能解开**（另一把钥匙就是「换用户 / 换机器」）。
 *
 * 它刻意*不*加密学安全 —— 是替身，不是实现。别把它抄去当保护器用。
 */
internal class XorSecretProtector(
    override val id: String = "fake",
    private val key: Byte = 0x5A,
) : SecretProtector {

    override fun protect(plain: ByteArray): ByteArray {
        val body = xor(plain)
        return byteArrayOf(mac(body), key) + body
    }

    override fun unprotect(sealed: ByteArray): ByteArray {
        if (sealed.size < 2 || sealed[1] != key) {
            throw SecretProtectionException("不是同一把钥匙：换用户 / 换机器解不开（假保护器）")
        }
        val body = sealed.copyOfRange(2, sealed.size)
        if (sealed[0] != mac(body)) {
            throw SecretProtectionException("密文被改过（假保护器）")
        }
        return xor(body)
    }

    private fun xor(bytes: ByteArray): ByteArray =
        ByteArray(bytes.size) { (bytes[it].toInt() xor key.toInt()).toByte() }

    private fun mac(body: ByteArray): Byte = body.fold(key.toInt()) { acc, b -> (acc * 31 + b) and 0xFF }.toByte()
}

/**
 * 「宿主**没有**原生保护能力」的替身：一调就抛。
 *
 * 它钉的是那条最重要的语义：**原生保护不可用时，写路径必须响亮地失败，盘上绝不能留明文**
 *（`ProtectedKeyValueStoreJvmTest` 的 `原生保护不可用时…` 那条用例）。
 */
internal class UnavailableSecretProtector(override val id: String = "unavailable") : SecretProtector {

    override fun protect(plain: ByteArray): ByteArray =
        throw SecretProtectionException("原生保护不可用（替身：模拟 CryptProtectData 失败）")

    override fun unprotect(sealed: ByteArray): ByteArray =
        throw SecretProtectionException("原生保护不可用（替身：模拟 CryptUnprotectData 失败）")
}
