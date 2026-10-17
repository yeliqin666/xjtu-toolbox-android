package com.xjtu.toolbox.platform

/** 测试用的假保护器（`:data` 的 `jvmTest` 专用，不进任何交付物 —— 与 `:testkit` 同一条纪律，只是它小到不值得单独开一个模块）。 */
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

/** 「宿主没有原生保护能力」的替身：一调就抛。 */
internal class UnavailableSecretProtector(override val id: String = "unavailable") : SecretProtector {

    override fun protect(plain: ByteArray): ByteArray =
        throw SecretProtectionException("原生保护不可用（替身：模拟 CryptProtectData 失败）")

    override fun unprotect(sealed: ByteArray): ByteArray =
        throw SecretProtectionException("原生保护不可用（替身：模拟 CryptUnprotectData 失败）")
}
