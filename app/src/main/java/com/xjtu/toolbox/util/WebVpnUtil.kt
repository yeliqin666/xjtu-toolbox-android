package com.xjtu.toolbox.util

import okhttp3.Interceptor
import okhttp3.Response
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WebVPN URL 加密工具
 * 使用 AES-128-CFB 加密域名，将普通 URL 转换为 WebVPN 代理 URL
 * 算法与 XJTUToolBox Python 版本完全一致
 */
object WebVpnUtil {

    private const val INSTITUTION = "webvpn.xjtu.edu.cn"
    private val KEY = "wrdvpnisthebest!".toByteArray(Charsets.UTF_8)
    private val IV = "wrdvpnisthebest!".toByteArray(Charsets.UTF_8)
    private val IV_HEX = IV.joinToString("") { "%02x".format(it) }

    const val WEBVPN_LOGIN_URL = "https://webvpn.xjtu.edu.cn/login?cas_login=true"

    /**
     * AES-128-CFB 加密（segment_size=128）
     * 手动实现以兼容所有 Android 设备（部分设备不支持 AES/CFB/NoPadding）
     */
    private fun cfb128Encrypt(plaintext: ByteArray): ByteArray {
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"))

        val result = ByteArray(plaintext.size)
        var feedback = IV.copyOf()
        var offset = 0

        while (offset < plaintext.size) {
            val encrypted = ecb.doFinal(feedback)
            val blockLen = minOf(16, plaintext.size - offset)
            for (j in 0 until blockLen) {
                result[offset + j] = (plaintext[offset + j].toInt() xor encrypted[j].toInt()).toByte()
            }
            // 下一轮使用密文作为反馈
            if (offset + 16 <= result.size) {
                feedback = result.copyOfRange(offset, offset + 16)
            }
            offset += 16
        }

        return result
    }

    /**
     * 加密域名为十六进制字符串
     */
    private fun encryptHostname(hostname: String): String {
        val encrypted = cfb128Encrypt(hostname.toByteArray(Charsets.UTF_8))
        return encrypted.joinToString("") { "%02x".format(it) }
    }

    /**
     * 将普通 URL 转换为 WebVPN 代理 URL
     * 例: http://bkkq.xjtu.edu.cn/path → https://webvpn.xjtu.edu.cn/http/77726476706e697374686562657374218b8559...ef/path
     */
    fun getVpnUrl(url: String): String {
        val parts = url.split("://", limit = 2)
        if (parts.size < 2) return url

        val protocol = parts[0]
        val rest = parts[1]

        val segments = rest.split("/", limit = 2)
        val hostPort = segments[0]
        val path = if (segments.size > 1) segments[1] else ""

        val domain = hostPort.split(":")[0]
        val port = if (":" in hostPort) "-${hostPort.split(":")[1]}" else ""

        val encryptedDomain = encryptHostname(domain)

        return "https://$INSTITUTION/$protocol$port/$IV_HEX$encryptedDomain/$path"
    }

    /**
     * 判断 URL 是否已是 WebVPN URL
     */
    fun isWebVpnUrl(url: String): Boolean =
        url.startsWith("https://$INSTITUTION") || url.startsWith("http://$INSTITUTION")

    /**
     * AES-128-CFB 解密（与加密对称）
     */
    private fun cfb128Decrypt(ciphertext: ByteArray): ByteArray {
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES")) // 注意：CFB 解密也用 ENCRYPT

        val result = ByteArray(ciphertext.size)
        var feedback = IV.copyOf()
        var offset = 0

        while (offset < ciphertext.size) {
            val encrypted = ecb.doFinal(feedback)
            val blockLen = minOf(16, ciphertext.size - offset)
            for (j in 0 until blockLen) {
                result[offset + j] = (ciphertext[offset + j].toInt() xor encrypted[j].toInt()).toByte()
            }
            // 下一轮使用密文（而不是明文）作为反馈
            if (offset + 16 <= ciphertext.size) {
                feedback = ciphertext.copyOfRange(offset, offset + 16)
            }
            offset += 16
        }
        return result
    }

    /**
     * 从十六进制字符串解密域名
     */
    private fun decryptHostname(hex: String): String {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(cfb128Decrypt(bytes), Charsets.UTF_8)
    }

    /**
     * 将 WebVPN 代理 URL 还原为原始 URL
     * 例: https://webvpn.xjtu.edu.cn/http-8086/77726476706e69...af/seat/ → http://rg.lib.xjtu.edu.cn:8086/seat/
     * 返回 null 表示无法解析
     */
    fun getOriginalUrl(vpnUrl: String): String? {
        if (!isWebVpnUrl(vpnUrl)) return null

        // 去掉 https://webvpn.xjtu.edu.cn/
        val path = vpnUrl.removePrefix("https://$INSTITUTION/").removePrefix("http://$INSTITUTION/")
        if (path.isBlank()) return null

        // path 结构: {protocol}{-port}/{iv_hex}{encrypted_domain_hex}/{rest_path}
        val segments = path.split("/", limit = 3)
        if (segments.size < 2) return null

        val protocolPort = segments[0]   // e.g. "http" or "http-8086" or "https"
        val hexPart = segments[1]        // iv_hex (32) + encrypted_domain_hex
        val restPath = if (segments.size > 2) segments[2] else ""

        // 解析协议和端口
        val protocol: String
        val port: String
        if ("-" in protocolPort) {
            val idx = protocolPort.indexOf("-")
            protocol = protocolPort.substring(0, idx)
            port = ":${protocolPort.substring(idx + 1)}"
        } else {
            protocol = protocolPort
            port = ""
        }

        // hexPart = IV_HEX (32 chars) + encrypted domain hex
        if (hexPart.length <= 32) return null
        val encryptedHex = hexPart.substring(32) // 跳过 IV

        return try {
            val domain = decryptHostname(encryptedHex)
            val pathSuffix = if (restPath.isNotEmpty()) "/$restPath" else ""
            "$protocol://$domain$port$pathSuffix"
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * OkHttp 拦截器：自动将非 WebVPN URL 加密为 WebVPN 代理 URL
 * 添加到 OkHttpClient 后，所有 HTTP 请求自动通过 WebVPN 代理
 */
class WebVpnInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val host = request.url.host

        // 已经是 WebVPN URL，不重复加密
        if (WebVpnUtil.isWebVpnUrl(url)) {
            return chain.proceed(request)
        }

        // CAS 认证服务器是公开的，不走 WebVPN（直接访问才能正确携带 TGC cookie 实现 SSO）
        if (host == "login.xjtu.edu.cn") {
            return chain.proceed(request)
        }

        val vpnUrl = WebVpnUtil.getVpnUrl(url)
        val newRequest = request.newBuilder().url(vpnUrl).build()
        return chain.proceed(newRequest)
    }
}
