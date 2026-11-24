package com.xjtu.toolbox.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 西交学生智慧社区 ss.xjtu.edu.cn（活动报名，金智 EMAP）。公网可直连。
 * 应用首页 → ss 发起 CAS OAuth2（client_id=1707）→ org 中转 → pubauthapp 落地写 `_WEU` → 回应用首页，
 * 之后还要像页面脚本那样调 getSelRoleConfig + getMenuInfo 把角色写进会话，否则要身份的接口一律 403。
 */
class SsSession : CasSiteSession(SITE_KEY, "智慧社区", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        SsLogin(client, visitorId, cachedRsaKey)

    /** EMAP 会话失效时接口被 302 到 CAS 或回一张 HTML 页。 */
    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean =
        super.isAuthFailureResponse(response, bodyPreview) ||
            (response.request.url.encodedPath.endsWith(".do") && response.request.url.encodedPath.contains("Controller") &&
                response.header("Content-Type")?.contains("text/html", ignoreCase = true) == true)

    /** 首页匿名或未选角色时也是 200，探活必须打一个要身份的接口。 */
    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        post(client, "$APP_BASE/myInfoController/getWdbmInfo.do", """{"PAGENUM":1,"PAGESIZE":1,"querySetting":"[]"}""")
    }

    private class SsLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?) :
        XJTULogin(LOGIN_URL, client, visitorId, cachedRsaKey) {
        override fun postLogin(response: Response) {
            val appId = client.newCall(Request.Builder().url(APP_INDEX).get().build()).execute().use { resp ->
                val body = resp.body.string()
                if (XJTULogin.isSafetyVerifyPage(body)) throw SafetyVerifyRequiredException(resp, body)
                if (resp.request.url.host != HOST) throw IOException("智慧社区登录没有完成，请重新登录")
                APP_ID_IN_PAGE.find(body)?.groupValues?.get(1) ?: DEFAULT_APP_ID
            }
            // 页面脚本（spriteFrame.js）启动时的两步：先取角色配置，再取菜单——后者把默认角色写进会话
            val app = """{"APPID":"$appId","APPNAME":"$APP_NAME"}"""
            post(client, "$PUB_BASE/MobileCommon/getSelRoleConfig.do", app)
            if (!post(client, "$PUB_BASE/MobileCommon/getMenuInfo.do", app)) {
                throw IOException("智慧社区没有分配到身份，请稍后重试")
            }
        }
    }

    companion object {
        const val SITE_KEY = "ss"
        const val HOST = "ss.xjtu.edu.cn"
        const val APP_NAME = "swmsyxsfzapp"
        const val APP_BASE = "https://ss.xjtu.edu.cn/xsfw/sys/$APP_NAME"
        const val APP_INDEX = "$APP_BASE/*default/index.do"
        private const val PUB_BASE = "https://ss.xjtu.edu.cn/xsfw/sys/swpubapp"
        /** 首页 `WIS_CONFIG.APPID`；页面里取不到时用这个（2026-11 实测值）。 */
        private const val DEFAULT_APP_ID = "6474976736708742"
        private val APP_ID_IN_PAGE = Regex("""APPID\s*:\s*"(\d+)"""")
        private val OK_CODE = Regex(""""code"\s*:\s*"?0"?\s*[,}]""")

        /**
         * 从应用首页进，让 ss 自己发起 OAuth（client_id=1707）。直接从 CAS authorize 进的话，
         * ss 收到不是自己发起的 code 会重走一遍授权，登完再跳回最初那个用过的 code → 404。
         */
        const val LOGIN_URL = APP_INDEX

        /** EMAP 表单 `data=<JSON>`；回 JSON 且 code 为 0 才算成功（403、登录页、「未获取到角色」都是 false）。 */
        private fun post(client: OkHttpClient, url: String, data: String): Boolean {
            val req = Request.Builder()
                .url(url)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", APP_INDEX)
                .post(FormBody.Builder().add("data", data).build())
                .build()
            return client.newCall(req).execute().use { resp ->
                resp.code == 200 && resp.request.url.host == HOST && OK_CODE.containsMatchIn(resp.body.string())
            }
        }
    }
}
