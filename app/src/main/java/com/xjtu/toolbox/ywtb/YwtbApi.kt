package com.xjtu.toolbox.ywtb

import com.xjtu.toolbox.util.requireObj
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.Request

data class UserInfo(
    val userName: String,
    val userUid: String,
    val identityTypeName: String,
    val organizationName: String
)

class YwtbApi(private val site: SiteSession) {

    /**
     * 构建带通用 header 的请求 Builder（不含 x-id-token，由 executeWithReAuth 注入）
     */
    private fun baseRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("x-device-info", "PC")
            .header("x-terminal-info", "PC")
            .header("Referer", "https://ywtb.xjtu.edu.cn/main.html")
    }

    suspend fun getUserInfo(): UserInfo {
        val request = baseRequest("https://authx-service.xjtu.edu.cn/personal/api/v1/personal/me/user")
            .get()
        val (responseCode, body) = site.executeWithReAuth(request.build()).use { response ->
            response.code to (response.body?.string() ?: throw RuntimeException("空响应"))
        }
        val json = body.safeParseJsonObject()

        if (responseCode != 200) {
            throw RuntimeException(json.get("message")?.stringValue ?: "服务器错误")
        }

        val data = json.requireObj("data")
        val attributes = data.requireObj("attributes")

        return UserInfo(
            userName = attributes.get("userName")?.stringValue ?: data.get("username")?.stringValue ?: "",
            userUid = attributes.get("userUid")?.stringValue ?: "",
            identityTypeName = attributes.get("identityTypeName")?.stringValue ?: "",
            organizationName = attributes.get("organizationName")?.stringValue ?: ""
        )
    }
}
