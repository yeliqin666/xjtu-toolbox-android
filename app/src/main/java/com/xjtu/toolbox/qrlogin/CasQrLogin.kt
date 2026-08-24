package com.xjtu.toolbox.qrlogin

import android.net.Uri
import android.util.Log
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 统一身份认证「扫码登录」——用本 App 已建立的 CAS 会话去授权电脑端登录。
 *
 * 二维码内容即 scan URL。认证打在 login.xjtu.edu.cn（校外经 WebVPN 反代，同一台 CAS）：
 *  - Cookie `TGC`：任意一次 CAS 登录后即在对应 backend 的 cookie jar 里
 *  - Header `x-id-token`：一网通办登录链解出，官方 SPA 无差别附加；真正校验的是 TGC
 *  - UA 必须以 `" SuperApp"` 结尾，否则 CAS 判「不支持当前客户端」
 *
 * 官方 App 的时序（电脑端靠轮询感知）：
 * ```
 *   1. GET  /cas/qr/scan/{ticket}        → 电脑立刻变成「已扫描，请确认」
 *   2. GET  /cas/qr/authorize/{ticket}   → 确认页 / webflow 上下文
 *   3. POST /cas/qr/auth/{ticket}        → 点确认后电脑才真正登录
 * ```
 * 第 1 步必须在识别到码时立刻发，不能等到用户点确认——否则电脑一直停在「请扫码」。
 */
object CasQrLogin {
    private const val TAG = "CasQrLogin"
    private const val HOST = "login.xjtu.edu.cn"
    private const val TOKEN_SITE = "ywtb"
    private const val UA_SUFFIX = " SuperApp"

    sealed class Result {
        /** 已通知电脑「扫到了」，等用户在手机上确认。 */
        object Scanned : Result()

        /** 授权成功，电脑端已登录。 */
        object Success : Result()

        data class Invalid(val reason: String) : Result()
        data class Expired(val detail: String) : Result()
        data class Failed(val detail: String) : Result()
    }

    fun parseTicket(scanned: String): String? {
        val uri = runCatching { Uri.parse(scanned.trim()) }.getOrNull() ?: return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val segs = uri.pathSegments ?: return null
        if (!segs.contains("qr")) return null
        val si = segs.indexOf("scan")
        val ticket = if (si >= 0 && si + 1 < segs.size) segs[si + 1] else segs.lastOrNull()
        return ticket?.takeIf { it.isNotBlank() && it != "scan" && it != "authorize" && it != "auth" }
    }

    fun isXjtuQrLogin(scanned: String): Boolean = parseTicket(scanned) != null

    /** 识别到码后立刻调用：电脑端从「请扫码」切到「请确认」。 */
    suspend fun markScanned(sessionManager: SessionManager, scanned: String): Result =
        withContext(Dispatchers.IO) {
            when (val ctx = prepare(sessionManager, scanned)) {
                is Prepare.Err -> ctx.result
                is Prepare.Ok -> runScan(ctx)
            }
        }

    /** 用户点确认后调用：电脑端完成登录。 */
    suspend fun confirmAuth(sessionManager: SessionManager, scanned: String): Result =
        withContext(Dispatchers.IO) {
            when (val ctx = prepare(sessionManager, scanned)) {
                is Prepare.Err -> ctx.result
                is Prepare.Ok -> runAuth(ctx)
            }
        }

    private sealed class Prepare {
        data class Ok(val ticket: String, val site: SiteSession, val client: OkHttpClient) : Prepare()
        data class Err(val result: Result) : Prepare()
    }

    private suspend fun prepare(sessionManager: SessionManager, scanned: String): Prepare {
        val ticket = parseTicket(scanned)
            ?: return Prepare.Err(Result.Invalid("不是西安交大扫码登录二维码"))
        val site = try {
            sessionManager.ensureSite(TOKEN_SITE, userInitiated = true)
        } catch (e: Exception) {
            Log.w(TAG, "ensureSite(ywtb) failed: ${e.message}")
            return Prepare.Err(Result.Failed("登录态获取失败：${e.message ?: "请先登录账号"}"))
        }
        return Prepare.Ok(ticket, site, site.client)
    }

    private fun Prepare.Ok.build(url: String): Request.Builder {
        val b = Request.Builder().url(url)
            .header("User-Agent", baseUa() + UA_SUFFIX)
            .header("x-requested-with", "com.supwisdom.xjtu")
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        return site.decorateRequest(b)
    }

    private fun runScan(ctx: Prepare.Ok): Result {
        return try {
            val scanUrl = "https://$HOST/cas/qr/scan/${ctx.ticket}?locale=zh"
            ctx.client.newCall(ctx.build(scanUrl).get().build()).execute().use { r1 ->
                if (r1.code == 404 || r1.code == 410) {
                    return Result.Expired("二维码已失效（${r1.code}），请在电脑上重新生成")
                }
                Log.d(TAG, "scan http=${r1.code} final=${r1.request.url}")
            }
            val authorizeUrl = "https://$HOST/cas/qr/authorize/${ctx.ticket}?locale=zh_CN"
            ctx.client.newCall(ctx.build(authorizeUrl).get().build()).execute().use { r2 ->
                Log.d(TAG, "authorize http=${r2.code}")
            }
            Result.Scanned
        } catch (e: Exception) {
            Log.w(TAG, "markScanned error: ${e.message}")
            Result.Failed(e.message ?: "网络错误")
        }
    }

    private fun runAuth(ctx: Prepare.Ok): Result {
        return try {
            val authorizeUrl = "https://$HOST/cas/qr/authorize/${ctx.ticket}?locale=zh_CN"
            val authUrl = "https://$HOST/cas/qr/auth/${ctx.ticket}"
            val post = ctx.build(authUrl)
                .header("x-requested-with", "XMLHttpRequest")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Origin", "https://$HOST")
                .header("Referer", authorizeUrl)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            ctx.client.newCall(post).execute().use { r3 ->
                val body = r3.body?.string().orEmpty()
                Log.d(TAG, "auth http=${r3.code} body=${body.take(160)}")
                parseAuthResult(r3.code, body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "confirmAuth error: ${e.message}")
            Result.Failed(e.message ?: "网络错误")
        }
    }

    private fun parseAuthResult(code: Int, body: String): Result {
        if (code != 200) return Result.Failed("服务器返回 $code")
        return runCatching {
            val json = JSONObject(body)
            val c = json.optInt("code", -1)
            val success = json.optJSONObject("data")?.optBoolean("success", false) ?: false
            when {
                c == 0 && success -> Result.Success
                c == 0 -> Result.Success
                else -> Result.Failed(json.optString("message").ifBlank { "确认未成功" })
            }
        }.getOrElse { Result.Failed("响应解析失败") }
    }

    private fun baseUa(): String =
        System.getProperty("http.agent")?.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"
}
