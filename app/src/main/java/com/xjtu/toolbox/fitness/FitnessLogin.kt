package com.xjtu.toolbox.fitness

import com.xjtu.toolbox.auth.XJTULogin
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class FitnessLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
) : XJTULogin(LOGIN_URL, session, visitorId, cachedRsaKey) {

    /**
     * 不能写成带初始化器的字段：超类构造里就会调 [postLogin]，随后子类初始化器会把值清掉。
     */
    val launch: FitnessLaunch?
        get() = lastLaunch

    val refererUrl: String
        get() = lastLaunch?.referer ?: FitnessProtocol.H5_HOME_URL

    override fun postLogin(response: Response) {
        val extracted = extractOrRetry(response.request.url.toString())
        lastLaunch = extracted
        val userInfo = try {
            FitnessProtocol.requestUserInfo(client, extracted.session, extracted.referer)
        } catch (e: Exception) {
            lastLaunch = null
            throw e
        }
        if (userInfo == null) {
            lastLaunch = null
            throw RuntimeException("体测会话初始化失败")
        }
    }

    override fun validateLogin(): Boolean {
        val current = lastLaunch ?: return false
        return FitnessProtocol.requestUserInfo(client, current.session, current.referer) != null
    }

    private fun extractOrRetry(callbackUrl: String): FitnessLaunch {
        runCatching { FitnessProtocol.extractLaunch(callbackUrl) }.getOrNull()?.let { return it }
        val retry = client.newCall(Request.Builder().url(LOGIN_URL).get().build()).execute()
        retry.body?.use { it.string() }
        return FitnessProtocol.extractLaunch(retry.request.url.toString())
    }

    companion object {
        const val TARGET_HOST = FitnessProtocol.TARGET_HOST
        const val LOGIN_URL = FitnessProtocol.LOGIN_URL
        const val H5_HOME_URL = FitnessProtocol.H5_HOME_URL
        const val ORIGIN = FitnessProtocol.ORIGIN

        @Volatile
        var lastLaunch: FitnessLaunch? = null
            internal set
    }
}
