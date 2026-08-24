package com.xjtu.toolbox.fitness

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.WebVpnUtil
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class FitnessLaunch(
    val session: LinkedHashMap<String, Any>,
    val referer: String,
)

/**
 * 体测 H5 v3 合同：回调参数在 URL hash 里，业务请求把字段 AES 加密后 POST 到 `/v3/api.php`。
 * 字段、密钥、签名与学校移动端页面一致。
 */
object FitnessProtocol {
    const val TARGET_HOST = "tyxylp.xjtu.edu.cn"
    const val ORIGIN = "https://tyxylp.xjtu.edu.cn"
    const val LOGIN_URL =
        "https://tyxylp.xjtu.edu.cn/bdlp_h5_fitness_test/public/index.php/index/login/xjtuLogin"
    const val API_V3 = "https://tyxylp.xjtu.edu.cn/v3/api.php"
    const val USER_INFO_URL = "$API_V3/WpLogin/UserInfo"
    const val H5_HOME_URL =
        "https://tyxylp.xjtu.edu.cn/bdlp_h5_fitness_test/view/h5xajt/#/pages/index/index"
    const val LEGACY_API_ROOT =
        "https://tyxylp.xjtu.edu.cn/bdlp_h5_fitness_test/public/index.php/index"

    val AES_KEY = "Wet2C8d34f62ndi3".toByteArray(Charsets.UTF_8)
    val AES_IV = "K6iv85jBD8jgf32D".toByteArray(Charsets.UTF_8)
    const val SIGN_SALT = "rDJiNB9j7vD2"

    val REQUIRED_LAUNCH_FIELDS = listOf(
        "uid", "token", "school_id", "term_id", "student_num", "card_id", "user_type",
    )
    val SESSION_FIELDS = listOf(
        "uid", "token", "school_id", "term_id", "student_num", "card_id", "nonce",
    )
    val ROLE_BY_USER_TYPE = mapOf("1" to 2, "2" to 1, "3" to 3)

    private val gson = Gson()
    private val random = SecureRandom()

    fun isFitnessCallback(url: String): Boolean =
        WebVpnUtil.isAtTargetSite(url, TARGET_HOST)

    fun extractLaunch(url: String): FitnessLaunch {
        if (!isFitnessCallback(url)) {
            throw RuntimeException("体测登录回调异常")
        }
        val hash = url.indexOf('#')
        if (hash < 0) throw RuntimeException("体测登录回调缺少会话参数")
        val fragment = url.substring(hash + 1)
        val q = fragment.indexOf('?')
        if (q < 0) throw RuntimeException("体测登录回调缺少会话参数")
        val fragmentPath = fragment.substring(0, q)
        val params = parseQuery(fragment.substring(q + 1))
        if (REQUIRED_LAUNCH_FIELDS.any { params[it].isNullOrBlank() }) {
            throw RuntimeException("体测登录回调缺少会话参数")
        }
        val role = ROLE_BY_USER_TYPE[params.getValue("user_type")]
            ?: throw RuntimeException("体测登录回调用户类型异常")
        val session = linkedMapOf<String, Any>()
        SESSION_FIELDS.forEach { key ->
            params[key]?.takeIf { it.isNotBlank() }?.let { session[key] = it }
        }
        session["ostype"] = "5"
        session["role"] = role
        val base = url.substring(0, hash).substringBefore('?')
        return FitnessLaunch(session, referer = "$base#$fragmentPath")
    }

    fun sessionFromTokens(tokens: Map<String, String>): LinkedHashMap<String, Any>? {
        val session = linkedMapOf<String, Any>()
        SESSION_FIELDS.forEach { key ->
            val value = tokens[key]?.takeIf { it.isNotBlank() } ?: return null
            session[key] = value
        }
        session["ostype"] = tokens["ostype"]?.ifBlank { "5" } ?: "5"
        session["role"] = tokens["role"]?.toIntOrNull() ?: return null
        return session
    }

    fun writeTokens(tokens: MutableMap<String, String>, launch: FitnessLaunch) {
        launch.session.forEach { (key, value) -> tokens[key] = value.toString() }
        tokens["referer_url"] = launch.referer
    }

    fun buildApiPayload(
        session: Map<String, Any>,
        extra: Map<String, Any> = emptyMap(),
        timestamp: Long = System.currentTimeMillis() / 1000,
        nonce: String = "%06d".format(random.nextInt(1_000_000)),
    ): LinkedHashMap<String, Any> {
        val payload = linkedMapOf<String, Any>(
            "uid" to (session["uid"] ?: ""),
            "token" to (session["token"] ?: ""),
            "school_id" to (session["school_id"] ?: ""),
            "term_id" to (session["term_id"] ?: ""),
            "class_id" to 0,
            "student_num" to (session["student_num"] ?: ""),
            "card_id" to (session["card_id"] ?: ""),
            "timestamp" to timestamp,
            "version" to 1,
            "nonce" to nonce,
            "ostype" to 5,
        )
        session.forEach { (key, value) -> payload[key] = value }
        extra.forEach { (key, value) -> payload[key] = value }
        payload["timestamp"] = timestamp
        payload["nonce"] = nonce
        val source = payload.keys.sorted().joinToString("") { key -> "$key${payload[key]}" } + SIGN_SALT
        payload["sign"] = md5Hex(source)
        return payload
    }

    fun encryptPayload(payload: Map<String, Any>): String {
        val plaintext = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), IvParameterSpec(AES_IV))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))
    }

    fun decryptPayload(value: String?): Any? {
        if (value.isNullOrBlank()) return null
        return try {
            val encrypted = Base64.getDecoder().decode(value.filter { !it.isWhitespace() })
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), IvParameterSpec(AES_IV))
            val json = String(cipher.doFinal(encrypted), Charsets.UTF_8)
            JsonParser.parseString(json)
        } catch (_: Exception) {
            null
        }
    }

    fun requestUserInfo(
        client: OkHttpClient,
        session: Map<String, Any>,
        referer: String,
    ): JsonObject? {
        val payload = buildApiPayload(session, extra = mapOf("uid" to (session["uid"] ?: "")))
        val request = encryptedRequest(USER_INFO_URL, payload, referer)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (looksLikeAuthFailure(body)) return null
            return unwrapUserInfo(body)
        }
    }

    fun postEncrypted(
        site: SiteSession,
        path: String,
        extra: Map<String, Any>,
        referer: String,
    ): String {
        val session = sessionFromTokens(site.localToken)
            ?: throw RuntimeException("体测会话未初始化")
        val payload = buildApiPayload(session, extra)
        val request = encryptedRequest("$API_V3/$path", payload, referer)
        return runBlocking { site.executeWithReAuth(request) }.use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RuntimeException("体测服务响应 ${response.code}")
            text
        }
    }

    fun unwrapUserInfo(body: String): JsonObject? {
        val data = parseEnvelope(body) ?: return null
        return data.takeIf { it.entrySet().isNotEmpty() }
    }

    /**
     * 解开 v3 信封：整段密文、`{status:1,data:密文}` 或明文 `{status:1,data:{...}}`。
     * 返回业务 data 对象；学年/成绩接口的 data 就是这个对象。
     */
    fun parseEnvelope(body: String): JsonObject? {
        val trimmed = body.trim()
        val decryptedDirect = decryptPayload(trimmed)
        if (decryptedDirect is JsonObject) return decryptedDirect
        val root = runCatching { trimmed.safeParseJsonObject() }.getOrNull() ?: return null
        if (root.get("status")?.asInt != 1) return null
        val data = root.get("data") ?: return null
        if (data.isJsonNull) return null
        if (data.isJsonObject) return data.asJsonObject
        if (data.isJsonPrimitive) {
            val inner = decryptPayload(data.asString)
            if (inner is JsonObject) return inner
        }
        return null
    }

    private fun encryptedRequest(url: String, payload: Map<String, Any>, referer: String): Request {
        val form = FormBody.Builder()
            .add("ostype", "5")
            .add("data", encryptPayload(payload))
            .build()
        return Request.Builder()
            .url(url)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Origin", ORIGIN)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(form)
            .build()
    }

    private fun looksLikeAuthFailure(body: String): Boolean =
        body.contains("登录") && (body.contains("过期") || body.contains("失效") || body.contains("未登录"))

    private fun md5Hex(source: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(source.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("&").mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = part.substringBefore("=")
            val value = java.net.URLDecoder.decode(part.substringAfter("=", ""), Charsets.UTF_8.name())
            if (key.isBlank()) null else key to value
        }.toMap()
    }
}
