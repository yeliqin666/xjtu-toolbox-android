package com.xjtu.toolbox.library

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher

/**
 * 测试用的 RSA 密钥对 —— 上游那半（解密码）与客户端那半（加密）都在这一个对象里。
 *
 * 为什么需要它：`XJTULogin.encryptPassword` 在没有缓存公钥时会去
 * `https://login.xjtu.edu.cn/cas/jwt/publicKey` 取一份（那条路是 https，本地假上游给不了）。
 * 真实 App 也是把公钥缓存下来复用的（`Account.rsaPublicKey` → `SessionManager.cachedRsaKey`），
 * 所以测试照同一条真实路径注入 [publicKeyBase64] 即可，不必为测试在生产代码里开口子。
 *
 * [publicKeyBase64] 的字节形状就是 `XJTULogin.parseRsaPublicKey` 要的
 * （X.509 SubjectPublicKeyInfo 的 DER，再做 base64）。
 */
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
