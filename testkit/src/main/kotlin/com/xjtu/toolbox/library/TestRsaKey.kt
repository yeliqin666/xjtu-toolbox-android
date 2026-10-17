package com.xjtu.toolbox.library

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher

/** 测试用的 RSA 密钥对 —— 上游那半（解密码）与客户端那半（加密）都在这一个对象里。 */
object TestRsaKey {

    private val pair: KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** 给 `SessionManager.cachedRsaKey` 用。 */
    val publicKeyBase64: String = Base64.getEncoder().encodeToString(pair.public.encoded)

    /** 解 `__RSA__<base64>` 形状的密码；认不出来（没加密 / 坏数据）返回 null。 */
    fun decrypt(encrypted: String): String? = runCatching {
        val body = encrypted.removePrefix("__RSA__")
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.private)
        cipher.doFinal(Base64.getDecoder().decode(body)).decodeToString()
    }.getOrNull()
}
