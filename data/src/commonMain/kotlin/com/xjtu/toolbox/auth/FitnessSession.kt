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

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val session = FitnessProtocol.sessionFromTokens(localToken) ?: return@withContext false
        val referer = localToken["referer_url"] ?: FitnessProtocol.H5_HOME_URL
        FitnessProtocol.requestUserInfo(client, session, referer) != null
    }
}
