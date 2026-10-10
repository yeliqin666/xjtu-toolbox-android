package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 校园卡会话。流程独立于标准 CAS：访问入口 → org.xjtu.edu.cn → login.xjtu.edu.cn → ticket → JWT。
 * 用 CasSiteSession 套壳——XJTULogin 状态机仍负责走完 CAS 部分，[CampusCardLogin.postLogin] 接管 ticket 兑换。
 *
 * 与它的 [CampusCardLogin] 一起从 `:app/auth/Sites.kt` 剪出来搬进 `:data`（桌面端第 9 条真数据路由：
 * 桌面要自己登 ncard，用 `:data` 的 [com.xjtu.toolbox.card.AppCampusCardSource] 取卡面与流水）。
 * 类名与包路径都没变 ⇒ `:app` 的 `AppLoginState` 里那处 `register(CampusCardSession())` 一行不用改。
 *
 * 搬出来时被替换的写法只有一处：`Sites.kt` 那个文件私有的 `withIo { }`
 * （= `withContext(Dispatchers.IO)`）换成本文件的 `withContext(Dispatchers.IO)` ——
 * 与 `JwxtSession` / `GsteSession` / `FitnessSession` 搬迁时同一条做法。
 */
class CampusCardSession : CasSiteSession("campus_card", "校园卡", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        CampusCardLogin(existingClient = client, visitorId = visitorId)

    override fun onLoginSuccess(login: XJTULogin) {
        val cc = login as? CampusCardLogin ?: return
        cc.accessToken?.let { localToken["access_token"] = it }
        cc.cardAccount?.let { localToken["card_account"] = it }
        cc.userName?.let { localToken["user_name"] = it }
        cc.studentNo?.let { localToken["student_no"] = it }
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["access_token"]?.let { builder.header("Synjones-Auth", "bearer $it") }
        builder.header("synAccessSource", "h5")
        return builder
    }

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val body = bodyPreview ?: return false
        return com.xjtu.toolbox.card.CampusCardContract.isAuthFailureBody(body)
    }

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val token = localToken["access_token"] ?: return@withContext false
        val resp = client.newCall(
            Request.Builder()
                .url("https://ncard.xjtu.edu.cn/berserker-app/ykt/tsm/queryCard?synAccessSource=h5")
                .header("Synjones-Auth", "bearer $token")
                .header("synAccessSource", "h5")
                .get()
                .build()
        ).execute()
        try {
            if (!resp.isSuccessful) return@withContext false
            val body = resp.body.string()
            if (isAuthFailureResponse(resp, body)) return@withContext false
            val root = runCatching { body.safeParseJsonObject() }.getOrNull() ?: return@withContext false
            if (com.xjtu.toolbox.card.CampusCardContract.businessCode(root) != "200") return@withContext false
            if (listOf("user_name", "student_no", "card_account").any { localToken[it].isNullOrBlank() }) {
                runCatching { reloadCampusCardProfile() }.getOrElse { return@withContext false }
            }
            true
        } finally {
            resp.close()
        }
    }

    private fun reloadCampusCardProfile() {
        val token = localToken["access_token"] ?: return
        val resp = client.newCall(
            Request.Builder()
                .url("https://ncard.xjtu.edu.cn/berserker-base/user?synAccessSource=h5")
                .header("Synjones-Auth", "bearer $token")
                .header("synAccessSource", "h5")
                .get()
                .build()
        ).execute()
        resp.use {
            val body = it.body.string()
            if (!it.isSuccessful) throw RuntimeException("校园卡用户资料请求失败")
            val root = body.safeParseJsonObject()
            com.xjtu.toolbox.card.CampusCardContract.requireSuccess(root, "校园卡用户资料")
            val data = com.xjtu.toolbox.card.CampusCardContract.requireDataObject(root, "校园卡用户资料")
            localToken["user_name"] = com.xjtu.toolbox.card.CampusCardContract.requiredText(data, "name", "校园卡用户资料")
            localToken["student_no"] = com.xjtu.toolbox.card.CampusCardContract.requiredText(data, "sno", "校园卡用户资料")
            localToken["card_account"] = com.xjtu.toolbox.card.CampusCardContract.requiredText(data, "cardAccount", "校园卡用户资料")
        }
    }
}
