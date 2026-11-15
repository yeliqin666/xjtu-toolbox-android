package com.xjtu.toolbox.auth

import com.xjtu.toolbox.fitness.FitnessLogin
import com.xjtu.toolbox.fitness.FitnessProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** 体测站点会话，钉死直连：体测偶发 HTTP 502 是应用自身故障，走 WebVPN 也一样。 */
class FitnessSession : CasSiteSession("fitness", "体测查询", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        FitnessLogin(
            session = client,
            visitorId = visitorId,
            cachedRsaKey = cachedRsaKey
        )

    override fun onLoginSuccess(login: XJTULogin) {
        val fitness = login as? FitnessLogin ?: return
        val launch = fitness.launch ?: return
        FitnessProtocol.writeTokens(localToken, launch)
    }

    /**
     * legacy 接口回「登录信息失效」= PHP 会话没了或 token 过期，按认证失效处理：
     * 基类会强制重登（登录流程末尾用新 token 调 checkLogin 建会话）再重放请求。
     * 只认这一句原文，学校应用层故障时的其它含糊文案不算。
     */
    override fun isAuthFailureResponse(response: okhttp3.Response, bodyPreview: String?): Boolean =
        super.isAuthFailureResponse(response, bodyPreview) ||
            bodyPreview?.let { FitnessProtocol.isLegacySessionLost(it) } == true

    /**
     * v3 的 UserInfo 拿过期 token 也照样回成功，不能当探活；用 legacy 的 checkLogin：
     * token 过期它会明说，顺带把学年、成绩要用的 PHP 会话建好。
     */
    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        FitnessProtocol.sessionFromTokens(localToken) ?: return@withContext false
        FitnessProtocol.checkLogin(client, localToken)
    }
}
