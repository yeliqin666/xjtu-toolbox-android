package com.xjtu.toolbox.srun

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 西安交通大学校园网（XJTU_STU）Srun 认证客户端。
 *
 * 服务器版本: SRunCGIAuthIntfSvr V1.18 B20191227（深澜软件）
 * 协议链路（已通过抓包对齐）：
 *   1. GET /cgi-bin/get_challenge  → 拿 challenge token
 *   2. GET /cgi-bin/srun_portal?action=login  → 提交加密后的 info+chksum
 *   3. GET /cgi-bin/rad_user_info  → 查询当前在线状态（保活/探测）
 *
 * 加密细节：
 *   - password:  "{MD5}" + HMAC-MD5(明文密码, challenge)，hex 输出
 *   - info:      "{SRBX1}" + srunBx1Encode(JSON, challenge)
 *               其中 JSON = {username, password, ip, acid, enc_ver:"srun_bx1"}
 *   - chksum:    SHA1(challenge + username + passwordMd5Hex + acId + ip + n + type + info)
 */
class SrunLogin(
    private val portalHost: String = DEFAULT_HOST,
    private val client: OkHttpClient = defaultClient
) {

    companion object {
        const val TAG = "SrunLogin"
        // 走 HTTP 避开自签证书问题（抓包对照 HTTPS 流量与 HTTP 等价；也可改 HTTPS+忽略证书）
        const val DEFAULT_HOST = "http://10.6.18.2"
        private const val AC_ID = "1"
        private const val N = "200"
        private const val TYPE = "1"

        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 查询当前网关是否已登录。
     * 同时也作为「是否在 Srun 网关网段内」的探测：
     *   - 网关可达 + error=ok + online_ip 非空  → 已登录
     *   - 网关可达 + error=not_online_error    → 网内但未登录
     *   - 网关不可达                            → 不在校园网网段或非 Srun
     */
    fun queryStatus(): SrunStatus {
        val cb = "jQueryCb_${System.currentTimeMillis()}"
        val url = "$portalHost/cgi-bin/rad_user_info?callback=$cb&_=${System.currentTimeMillis()}"
        return try {
            val resp = doGet(url)
            val json = parseJsonp(resp, cb) ?: return SrunStatus.UNKNOWN
            val err = json.optString("error", "")
            when {
                err == "ok" && json.optString("online_ip").isNotBlank() ->
                    SrunStatus.Online(
                        ip = json.optString("online_ip"),
                        username = json.optString("user_name").ifBlank { json.optString("username") }
                    )
                err == "not_online_error" -> SrunStatus.NotLoggedIn
                else -> SrunStatus.NotLoggedIn
            }
        } catch (e: Exception) {
            Log.d(TAG, "queryStatus fail: ${e.message}")
            SrunStatus.Unreachable
        }
    }

    /**
     * 执行 Srun 登录。
     * @param username 校园网账号（含后缀如 `2233712126@stu`，函数内部会原样发送）
     * @param password 明文密码
     * @return [SrunLoginResult] 登录结果
     */
    fun login(username: String, password: String): SrunLoginResult {
        // ── 1. 获取 challenge ──
        val challenge: String
        val clientIp: String
        try {
            val cb1 = "jQueryCb_${System.currentTimeMillis()}"
            val challengeUrl = "$portalHost/cgi-bin/get_challenge?callback=$cb1" +
                "&username=${urlEncode(username)}&ip=&_=${System.currentTimeMillis()}"
            val challengeResp = doGet(challengeUrl)
            val json = parseJsonp(challengeResp, cb1)
                ?: return SrunLoginResult(false, "challenge 解析失败: ${challengeResp.take(120)}")
            if (json.optString("error") != "ok") {
                return SrunLoginResult(false, "获取 challenge 失败: ${json.optString("error_msg")}")
            }
            challenge = json.getString("challenge")
            clientIp = json.optString("client_ip", json.optString("online_ip", ""))
        } catch (e: Exception) {
            return SrunLoginResult(false, "无法连接 Srun 网关: ${e.message}")
        }

        // ── 2. 计算 info / passwordMd5 / chksum ──
        // 手动构造 JSON 保证字段顺序固定，且不让 JSONObject.toString() 转义 `/`
        val infoJsonStr = buildString {
            append('{')
            append("\"username\":\"").append(jsonEscape(username)).append("\",")
            append("\"password\":\"").append(jsonEscape(password)).append("\",")
            append("\"ip\":\"").append(jsonEscape(clientIp)).append("\",")
            append("\"acid\":\"").append(AC_ID).append("\",")
            append("\"enc_ver\":\"srun_bx1\"")
            append('}')
        }
        Log.d(TAG, "info JSON: $infoJsonStr")
        val info = "{SRBX1}" + SrunCrypto.srunBx1Encode(infoJsonStr, challenge)
        val passwordMd5 = SrunCrypto.hmacMd5Hex(password, challenge)
        val passwordParam = "{MD5}$passwordMd5"
        // chksum 是 challenge 多次拼接（标准 Srun 算法）
        val chkBuilder = StringBuilder()
            .append(challenge).append(username)
            .append(challenge).append(passwordMd5)
            .append(challenge).append(AC_ID)
            .append(challenge).append(clientIp)
            .append(challenge).append(N)
            .append(challenge).append(TYPE)
            .append(challenge).append(info)
        val chksum = SrunCrypto.sha1Hex(chkBuilder.toString())

        // ── 3. 提交登录 ──
        return try {
            val cb2 = "jQueryCb_${System.currentTimeMillis()}"
            val loginUrl = StringBuilder("$portalHost/cgi-bin/srun_portal?")
                .append("callback=").append(cb2)
                .append("&action=login")
                .append("&username=").append(urlEncode(username))
                .append("&password=").append(urlEncode(passwordParam))
                .append("&ac_id=").append(AC_ID)
                .append("&ip=").append(urlEncode(clientIp))
                .append("&chksum=").append(chksum)
                .append("&info=").append(urlEncode(info))
                .append("&n=").append(N)
                .append("&type=").append(TYPE)
                .append("&os=Android")
                .append("&name=Android")
                .append("&double_stack=0")
                .append("&_=").append(System.currentTimeMillis())
                .toString()
            val loginResp = doGet(loginUrl)
            val json = parseJsonp(loginResp, cb2)
                ?: return SrunLoginResult(false, "登录响应解析失败: ${loginResp.take(120)}")
            val err = json.optString("error")
            if (err == "ok") {
                SrunLoginResult(true, "登录成功", username = username, ip = clientIp)
            } else {
                SrunLoginResult(
                    false,
                    json.optString("error_msg").ifBlank { "登录失败: $err" }
                )
            }
        } catch (e: Exception) {
            SrunLoginResult(false, "登录请求异常: ${e.message}")
        }
    }

    private fun doGet(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android; XJTUToolbox)")
            .header("Accept", "*/*")
            .get().build()
        client.newCall(req).execute().use { resp ->
            return resp.body?.string() ?: ""
        }
    }

    /** 解析 JSONP 响应 `cbName({...})` 提取内层 JSON。 */
    private fun parseJsonp(body: String, callbackName: String): JSONObject? {
        if (body.isBlank()) return null
        val start = body.indexOf('(').takeIf { it >= 0 } ?: return null
        val end = body.lastIndexOf(')').takeIf { it > start } ?: return null
        val jsonStr = body.substring(start + 1, end).trim()
        return try {
            JSONObject(jsonStr)
        } catch (_: Exception) {
            null
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    /** JSON 字符串值的最小转义。仅处理必要字符，不转义 `/`。 */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}

/** Srun 在线状态。 */
sealed class SrunStatus {
    /** 已登录在线。 */
    data class Online(val ip: String, val username: String) : SrunStatus()
    /** 网关可达但未登录（典型 Captive Portal 场景）。 */
    object NotLoggedIn : SrunStatus()
    /** 网关不可达（不在 Srun 网段或非校园网）。 */
    object Unreachable : SrunStatus()
    /** 未知响应（解析失败）。 */
    object UNKNOWN : SrunStatus()
}

/** Srun 登录结果。 */
data class SrunLoginResult(
    val success: Boolean,
    val message: String,
    val username: String = "",
    val ip: String = ""
)
