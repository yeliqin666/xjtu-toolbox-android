package com.xjtu.toolbox.util

import android.util.Log

/**
 * 教材中心（jiaocai.lib）与教材全文库（jiaocai1.lib）共用的 JS 跳转登录链。
 *
 * 这两个站会话未建立时不返数据，而是一张只有一行 `var logoutUrl = "…"` 的页面，靠浏览器
 * 执行 `location.href` 逐跳走完，典型链条：
 *
 *   业务接口 → jiaocai.lib/outer/lsp-proxy2 → jiaocai1/front/user/portalLogin
 *           → jiaocai.lib/login?fid=17071 → CAS(service=jiaocai.lib/sso/login/3rd/17071)
 *
 * OkHttp 不跑 JS，只能照着跟。末跳是 CAS，客户端已持有 TGC，跟到即可换票回来。
 */
object PortalRedirect {

    private const val TAG = "PortalRedirect"

    const val MAX_HOPS = 5

    /** 第一轮只是让 jiaocai.lib 拿到 CAS 票，portalLogin 那侧要第二轮才拿得到 uid/name */
    const val MAX_ROUNDS = 3

    /** 只跟这几个域名，不把页面里的任意 URL 都当登录链去访问 */
    val ALLOWED_HOSTS = setOf(
        "jiaocai.lib.xjtu.edu.cn",
        "jiaocai1.lib.xjtu.edu.cn",
        "login.xjtu.edu.cn",
    )

    /** 变量名是厂商起的，实际指向的是登录入口 */
    private val LOGOUT_URL_RE = Regex("var\\s+logoutUrl\\s*=\\s*\"([^\"]+)\"")

    private val JS_UNICODE_RE = Regex("""\\u([0-9a-fA-F]{4})""")

    fun needsLogin(body: String): Boolean = LOGOUT_URL_RE.containsMatchIn(body)

    /** 取下一跳；域名不在白名单内返回 null，链条就此停下 */
    fun nextHop(body: String): String? {
        val url = LOGOUT_URL_RE.find(body)?.groupValues?.get(1)?.let(::unescapeJs) ?: return null
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        if (host !in ALLOWED_HOSTS) {
            Log.w(TAG, "登录链指向意料外的域名，已停止: $host")
            return null
        }
        return url
    }

    /** 页面里的 URL 是 JS 字面量，`/` 和 `&`（\u0026）都被转义过 */
    fun unescapeJs(s: String): String =
        JS_UNICODE_RE.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("\\/", "/")
            .replace("\\\"", "\"")

    /**
     * 跟完整条链。[fetchPage] 负责发一次 GET 并返回页面文本。
     * 返回 true 表示链条自然走完（可以重发原请求），false 表示中途打转或失败。
     */
    fun follow(startPage: String, tag: String, fetchPage: (String) -> String): Boolean {
        var page = startPage
        val seen = mutableSetOf<String>()
        repeat(MAX_HOPS) { i ->
            val next = nextHop(page) ?: return true
            if (!seen.add(next)) {
                Log.w(TAG, "[$tag] 登录链打转，停在 $next")
                return false
            }
            page = runCatching { fetchPage(next) }.getOrElse {
                Log.w(TAG, "[$tag] 登录链第 ${i + 1} 跳失败: ${it.message}")
                return false
            }
        }
        Log.w(TAG, "[$tag] 登录链超过 $MAX_HOPS 跳仍未走完")
        return false
    }
}
