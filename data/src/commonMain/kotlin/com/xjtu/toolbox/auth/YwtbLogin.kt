package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.Base64

private const val TAG = "YwtbLogin"

/**
 * 新师生综合服务大厅 (ywtb.xjtu.edu.cn) 专用登录
 * 登录后从重定向 URL 中提取 CAS ticket（JWT），解码提取 idToken。
 * 过期后由站点会话层（[SiteSession.executeWithReAuth]）整体重登。
 *
 * 它从 `:app/auth/` 搬进同包的 `:data/auth/`（桌面端第 12 条真数据路由：消息收纳 —— 桌面要自己登
 * 一网通办才拿得到那四路数据），**类名与包路径一字未改**，被替换的写法只有两处：
 *
 *  1. `android.util.Log` → `:core` 的 [Log]；
 *  2. `android.util.Base64.decode(…, URL_SAFE or NO_WRAP)` → `java.util.Base64.getUrlDecoder()`
 *     —— 同一个解码器语义（JWT payload 是 base64url），补齐 padding 那两句照旧；`:data` 只发
 *     JVM / Android 两个目标，所以这里用 JDK 那份（与 `:data` 的 [Jwt] 同一个写法）。
 */
class YwtbLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(YWTB_LOGIN_URL, session, visitorId, cachedRsaKey) {

    var idToken: String? = null
        private set

    override fun postLogin(response: Response) {
        // 使用 OkHttp HttpUrl 解析 query parameter，自动处理 URL 编码
        val ticketJwt = response.request.url.queryParameter("ticket")
            ?.takeIf { it.isNotEmpty() }
            ?: throw RuntimeException("登录失败：无法获取 YWTB ticket")

        val parts = ticketJwt.split(".")
        if (parts.size < 2) throw RuntimeException("登录信息无效，请重新登录")

        // JWT payload 使用 Base64url 编码（可能有或无 padding）
        // 先补齐 padding 再解码，兼容所有格式
        val payload64 = parts[1].let { p ->
            when (p.length % 4) {
                2 -> p + "=="
                3 -> p + "="
                else -> p
            }
        }
        val payloadJson = String(Base64.getUrlDecoder().decode(payload64))
        val payload = payloadJson.safeParseJsonObject()
        idToken = payload.get("idToken")?.stringValue
            ?: throw RuntimeException("JWT 中未找到 idToken")

        Log.d(TAG, "postLogin: idToken obtained")
    }

    companion object {
        const val YWTB_LOGIN_URL =
            "https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fywtb.xjtu.edu.cn%2F%3Fpath%3Dhttps%253A%252F%252Fywtb.xjtu.edu.cn%252Fmain.html%2523%252FIndex"
    }
}
