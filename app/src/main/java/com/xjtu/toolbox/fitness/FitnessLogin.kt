package com.xjtu.toolbox.fitness

import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class FitnessLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
) : XJTULogin(LOGIN_URL, session, visitorId, cachedRsaKey) {

    private var sessionReady = false
    var refererUrl: String = H5_HOME_URL
        private set

    override fun postLogin(response: Response) {
        val callbackUrl = response.request.url
        if ("tyxylp.xjtu.edu.cn" !in callbackUrl.host) {
            throw RuntimeException("体测登录回调异常")
        }
        refererUrl = callbackUrl.toString()

        // 真实链路是：xjtuLogin 先建立 PHPSESSID 并进入 H5 首页；随后前端页面
        // 在带 token/sign 的详情页里再调用 checkLogin。若回调没有这些参数，
        // 说明 CAS 会话已建立，后续 fitnessYear/getStudentScore 可直接用该会话。
        val params = callbackUrl.queryParameterNames.associateWith {
            callbackUrl.queryParameter(it).orEmpty()
        }.filterKeys { it in CHECK_LOGIN_FIELDS }

        if (params["token"].isNullOrBlank() || params["sign"].isNullOrBlank()) {
            sessionReady = true
            return
        }

        val form = FormBody.Builder().apply {
            CHECK_LOGIN_FIELDS.forEach { add(it, params[it].orEmpty()) }
        }.build()
        client.newCall(
            Request.Builder()
                .url(CHECK_LOGIN_URL)
                .header("Origin", ORIGIN)
                .header("Referer", callbackUrl.toString())
                .header("X-Requested-With", "XMLHttpRequest")
                .post(form)
                .build()
        ).execute().use {
            val body = it.body?.string().orEmpty()
            val ok = runCatching {
                body.safeParseJsonObject().get("status")?.asInt == 1
            }.getOrDefault(false)
            if (!it.isSuccessful || !ok) {
                throw RuntimeException("体测会话初始化失败")
            }
        }
        sessionReady = true
    }

    override fun validateLogin(): Boolean = sessionReady

    companion object {
        const val LOGIN_URL =
            "https://tyxylp.xjtu.edu.cn/bdlp_h5_fitness_test/public/index.php/index/login/xjtuLogin"
        const val API_ROOT =
            "https://tyxylp.xjtu.edu.cn/bdlp_h5_fitness_test/public/index.php/index"
        const val H5_HOME_URL =
            "https://tyxylp.xjtu.edu.cn/bdlp_h5_fitness_test/view/h5xajt/#/pages/index/index"
        const val ORIGIN = "https://tyxylp.xjtu.edu.cn"
        private const val CHECK_LOGIN_URL = "$API_ROOT/Index/checkLogin"
        private val CHECK_LOGIN_FIELDS = listOf(
            "timestamp", "nonce", "course_id", "uid", "card_id", "login_type",
            "type", "school_id", "student_num", "user_type", "token", "sign", "term_id", "id"
        )
    }
}
