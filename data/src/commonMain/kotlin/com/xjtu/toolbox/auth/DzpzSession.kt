package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.isNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 电子凭证系统（`dzpz.xjtu.edu.cn` 的工作流引擎）的站点会话。 */
class DzpzSession : CasSiteSession(SITE_KEY, "电子凭证", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        DzpzLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? DzpzLogin)?.userId?.takeIf { it.isNotEmpty() }?.let {
            localToken["user_id"] = it
        }
    }

    /** getOSinfo 登录态下返回 `resourceid`（loginidweaver），匿名访问时该字段缺失。 */
    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val resp = client.newCall(
            Request.Builder()
                .url("${DzpzLogin.OS_INFO_URL}?__random__=${System.currentTimeMillis()}")
                .header("Referer", "${DzpzLogin.BASE_URL}/wui/index.html")
                .get().build()
        ).execute()
        try {
            if (resp.code != 200) return@withContext false
            val id = (resp.body.string()).safeParseJsonObject()
                .get("resourceid")?.takeIf { !it.isNull }?.stringValue
                ?.takeIf { it.isNotBlank() && it != "0" } ?: return@withContext false
            localToken["user_id"] = id
            true
        } finally { resp.close() }
    }

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.DZPZ_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "dzpz"
    }
}
