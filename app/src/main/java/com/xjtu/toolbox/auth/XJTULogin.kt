package com.xjtu.toolbox.auth

import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.brotli.BrotliInterceptor
import org.jsoup.Jsoup
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import com.google.gson.Gson
import com.xjtu.toolbox.util.safeParseJsonObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.io.IOException
import java.util.UUID
import java.security.MessageDigest

/**
 * 业务请求因认证失效且重认证失败时抛出。
 *
 * 调用方需捕获此异常，调用 `AppLoginState.handleAuthExpired(...)` 静默触发
 * 重新登录（含 MFA）；不要直接将 message 展示给用户。
 */
class AuthExpiredException(
    val siteName: String = "",
    message: String = if (siteName.isEmpty()) "登录态已失效" else "${siteName}登录态已失效"
) : IOException(message)

/**
 * postLogin/SSO 路径上遇到 CAS 「Safety Verify」二次安全验证页面时抛出。
 *
 * 触发场景：
 * - JWXT (`client_id=1675`) OAuth2 授权链路被 CAS 强制二次手机验证（probe 实证：
 *   webvpn session 已建立、TGC 已下发，访问 jwxt 仍触发此页）。
 * - 其他高敏感 OAuth client 也可能命中（如 `org.xjtu` 部分 appId）。
 *
 * 调用方约定：`XJTULogin` 状态机会在 `init` SSO 与 `processLoginResponse → postLogin`
 * 调用上 try-catch 该异常，转 `LoginState.REQUIRE_MFA` 让 UI 弹出验证码对话框，
 * 用户输入后 `MFAContext.verifyCode()` 自动 POST 隐藏表单回 `response.request.url`，
 * 完成 CAS 二次校验。**业务子类禁止吞掉此异常。**
 */
class SafetyVerifyRequiredException(
    val response: okhttp3.Response,
    val responseBody: String,
) : RuntimeException("CAS Safety Verify required")

/**
 * 登录状态枚举
 */
enum class LoginState {
    REQUIRE_MFA,            // 需要 MFA 两步验证
    REQUIRE_CAPTCHA,        // 需要验证码
    SUCCESS,                // 登录成功
    FAIL,                   // 登录失败
    REQUIRE_ACCOUNT_CHOICE  // 需要选择账户（本科/研究生）
}

/**
 * 两步验证流程类型
 * - MFA_DETECT：旧式，登录前主动检测（POST /cas/mfa/detect → state），手机号/验证码接口路径用 /cas/mfa/...
 * - SAFETY_VERIFY：2026-05 新版「Safety Verify」二次认证页面（任意请求都可能返回），手机号/验证码接口路径用 /cas/sec/...，
 *   验证完成后需把 secState/execution/_eventId/geolocation/fpVisitorId/submit 提交回触发该页面的原始 URL
 */
enum class MFAFlow(val pathSegment: String) {
    MFA_DETECT("mfa"),
    SAFETY_VERIFY("sec")
}

/**
 * 登录结果
 */
data class LoginResult(
    val state: LoginState,
    val message: String = "",
    val session: OkHttpClient? = null,
    val mfaContext: MFAContext? = null,
    val accountChoices: List<AccountChoice>? = null
)

data class AccountChoice(
    val name: String,
    val label: String
)

/**
 * MFA 两步验证上下文。
 *
 * 上游 XJTUToolBox（Python）于 2026-05 新增 SAFETY_VERIFY 流程：任意业务请求都可能被 CAS
 * 拦截到「Safety Verify」二次认证页面，页面中含 `secState/execution/_eventId/submit` 隐藏字段；
 * 验证完成后，必须把这些字段 POST 回触发该页面的「原 URL」（即 safety_response.url），
 * 而不是构造一个 cas/login?service= 链接（旧实现的错误来源）。
 *
 * @param flow 区分 MFA_DETECT（登录前主动 detect）与 SAFETY_VERIFY（任意页面拦截）
 * @param state 用于 initByType/securephone 查询的 state（detect 返回 / Safety 页面 secState）
 * @param safetyTriggerUrl 触发 Safety Verify 页面的原始 URL（仅 SAFETY_VERIFY 流程需要）
 */
class MFAContext(
    private val login: XJTULogin,
    val state: String,
    val required: Boolean = true,
    val flow: MFAFlow = MFAFlow.MFA_DETECT
) {
    var gid: String? = null
    private var phoneNumber: String? = null

    /** Safety Verify 页面中提取的 secState（最终免密提交用） */
    var secState: String? = null
    /** Safety Verify 页面中的 execution */
    var mfaExecution: String? = null
    /** Safety Verify 页面中的 _eventId，默认 submit */
    var safetyEventId: String = "submit"
    /** Safety Verify 页面中的 submit value，默认 Login1 */
    var safetySubmitValue: String = "Login1"
    /** 触发 Safety Verify 页面的原始请求 URL（POST 完成提交回该 URL） */
    var safetyTriggerUrl: String? = null

    /**
     * 获取绑定的手机号（中间四位屏蔽）。
     * 路径按 flow 切换：MFA_DETECT → /cas/mfa/...，SAFETY_VERIFY → /cas/sec/...
     */
    fun getPhoneNumber(): String {
        phoneNumber?.let { return it }

        val queryState = secState ?: state
        val url = "https://login.xjtu.edu.cn/cas/${flow.pathSegment}/initByType/securephone?state=$queryState"
        val request = Request.Builder().url(url).get().build()

        val response = login.client.newCall(request).execute()
        val json = response.body?.string().safeParseJsonObject()
        if (json.get("code").asInt == 0) {
            val data = json.getAsJsonObject("data")
            gid = data.get("gid").asString
            phoneNumber = data.get("securePhone").asString
            return phoneNumber!!
        } else {
            throw RuntimeException("获取手机号失败: ${json.get("message")?.asString ?: "未知错误"}")
        }
    }

    /** 发送验证码到手机 */
    fun sendVerifyCode(): String {
        val phone = getPhoneNumber()
        val json = Gson().toJson(mapOf("gid" to gid))
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://login.xjtu.edu.cn/attest/api/guard/securephone/send")
            .post(body)
            .build()

        val response = login.client.newCall(request).execute()
        val result = response.body?.string().safeParseJsonObject()
        if (result.get("code").asInt == 0) {
            return phone
        } else {
            throw RuntimeException(result.get("message").asString)
        }
    }

    /**
     * 验证手机验证码。
     * - MFA_DETECT：仅校验 code，登录主流程会继续 POST 登录表单。
     * - SAFETY_VERIFY：校验 code 通过后，立即把 Safety 表单 POST 回触发该页的原 URL，让 CAS 完成跳转/下发 TGC。
     */
    fun verifyCode(code: String) {
        if (gid == null) throw RuntimeException("必须先发送验证码")

        val json = Gson().toJson(mapOf("gid" to gid, "code" to code))
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://login.xjtu.edu.cn/attest/api/guard/securephone/valid")
            .post(body)
            .build()

        val response = login.client.newCall(request).execute()
        val result = response.body?.string().safeParseJsonObject()
        if (result.get("code").asInt != 0) {
            throw RuntimeException(result.get("message").asString)
        }
        // status 字段进一步确认
        result.getAsJsonObject("data")?.get("status")?.asString?.let { status ->
            if (status != "2") {
                throw RuntimeException(result.get("message")?.asString ?: "验证码验证失败")
            }
        }

        // SAFETY_VERIFY 流程：完成 secState 表单回提，让 CAS 跳转到原业务 URL 并下发 TGC
        if (flow == MFAFlow.SAFETY_VERIFY) {
            val secStateVal = secState ?: return
            val mfaExec = mfaExecution ?: return
            val triggerUrl = safetyTriggerUrl
                ?: run {
                    // 兜底（不应走到这里）：构造 cas/login?service=...
                    val encodedService = java.net.URLEncoder.encode(
                        login.serviceUrl.ifEmpty { "https://webvpn.xjtu.edu.cn" }, "UTF-8"
                    )
                    "https://login.xjtu.edu.cn/cas/login?service=$encodedService"
                }

            val formBody = FormBody.Builder()
                .add("secState", secStateVal)
                .add("execution", mfaExec)
                .add("_eventId", safetyEventId)
                .add("geolocation", "")
                .add("fpVisitorId", login.fpVisitorId)
                .add("submit", safetySubmitValue)
                .build()

            val secRequest = Request.Builder()
                .url(triggerUrl)
                .header("Referer", triggerUrl)
                .post(formBody)
                .build()

            val secResponse = login.client.newCall(secRequest).execute()
            android.util.Log.d("XJTULogin", "Safety verify final submit → ${secResponse.code} ${secResponse.request.url}")
            // 缓存最终响应，供 XJTULogin.consumeSafetyVerifyFinalResponse() 读取
            login.lastSafetyVerifyResponse = secResponse
        }
    }
}

/**
 * 西安交通大学统一身份认证登录
 * 适用于 2025 年 7 月 17 日后的新认证系统 (login.xjtu.edu.cn)
 */
open class XJTULogin(
    loginUrl: String,
    existingClient: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
    cookieJar: okhttp3.CookieJar? = null
) {
    // Cookie 管理器（internal 供应用内浏览器同步 cookies 到 WebView）
    // 如果提供了自定义 CookieJar（如 PersistentCookieJar），则 cookieManager 仅作为兼容保留
    internal val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    // OkHttp 客户端（带 Cookie 管理和重定向）
    val client: OkHttpClient = existingClient ?: OkHttpClient.Builder()
        .addInterceptor(BrotliInterceptor)
        .cookieJar(cookieJar ?: JavaNetCookieJar(cookieManager))
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // 登录提交的 URL
    private var postUrl: String

    // CAS 认证的目标 Service URL（MFA 完成后获取 TGC 所需）
    internal var serviceUrl: String = ""

    // CAS execution 字段（防 CSRF）
    private var executionInput: String

    // 设备指纹 ID（公开以便跨系统复用，减少 MFA 触发）
    val fpVisitorId: String = visitorId ?: generateFpVisitorId()

    // 是否启用 MFA
    private var mfaEnabled: Boolean = true

    // 登录失败次数（决定是否需要验证码）
    private var failCount: Int = 0

    // RSA 公钥（从服务器获取或从缓存加载）
    private var rsaPublicKey: String? = cachedRsaKey

    // 是否已登录
    var hasLogin: Boolean = false
        private set

    // MFA 上下文
    var mfaContext: MFAContext? = null
        private set

    // 登录凭据
    private var username: String? = null
    private var encryptedPassword: String? = null
    private var jcaptcha: String = ""

    /** 明文密码（仅内存，供子类对旧 CAS 服务认证） */
    protected var rawPassword: String? = null
        private set

    /** 明文用户名（供子类访问） */
    protected val storedUsername: String? get() = username

    // 账户选择响应
    private var chooseAccountResponse: Response? = null
    private var chooseAccountBody: String? = null

    /** 最近一次 postLogin 调用前的 response body（供子类在 postLogin 中使用） */
    protected var lastResponseBody: String = ""

    /**
     * SAFETY_VERIFY 流程在 verifyCode() 提交 secState 表单后缓存的最终响应。
     * 下一次 login() 调用会消费该响应代替 loginResponse 走后续验证/账户选择/成功判定。
     */
    internal var lastSafetyVerifyResponse: okhttp3.Response? = null

    init {
        val TAG = "XJTULogin"
        android.util.Log.d(TAG, "init: loginUrl=$loginUrl, hasExistingClient=${existingClient != null}")

        // 访问登录页面，获取 postUrl 和 execution
        val request = Request.Builder()
            .url(loginUrl)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        postUrl = response.request.url.toString()
        serviceUrl = try {
            java.net.URLDecoder.decode(
                java.net.URI(loginUrl).query?.split("&")?.find { it.startsWith("service=") }?.substringAfter("service=") ?: "",
                "UTF-8"
            )
        } catch (_: Exception) { "" }

        android.util.Log.d(TAG, "init: responseCode=${response.code}, postUrl=$postUrl")
        android.util.Log.d(TAG, "init: responseBodyLen=${responseBody.length}")

        // 提取 execution value（如果页面是登录表单）
        executionInput = extractExecutionValue(responseBody)

        // 关键：Safety Verify 页面**也**含 `<input name="execution">`，仅靠 executionInput 判断不出来。
        // 必须用 title + secState 字段联合判定（与 upstream `is_safety_verify_page` 一致）。
        val initialSafetyVerify = isSafetyVerifyPage(responseBody)

        android.util.Log.d(TAG, "init: executionInput.isEmpty=${executionInput.isEmpty()}, initialSafetyVerify=$initialSafetyVerify, existingClient=${existingClient != null}")

        if (initialSafetyVerify) {
            // 入口页面直接就是 Safety Verify（webvpn session 已建立，CAS 跳转 OAuth2 1675 时强制二次认证）。
            // 跳过"正常登录"流程：标记 mfaContext，由主 login() 返回 REQUIRE_MFA，弹 MFA dialog。
            android.util.Log.w(TAG, "init: initial response is SAFETY_VERIFY page (bodyLen=${responseBody.length}), capturing context")
            hasLogin = false
            mfaEnabled = false
            captureSafetyVerify(response, responseBody)
        } else if (executionInput.isEmpty() && existingClient != null) {
            // SSO: 共享的 CookieManager 携带 TGC，CAS 自动完成认证并跳转到目标服务
            hasLogin = true
            mfaEnabled = false
            try {
                lastResponseBody = responseBody
                postLogin(response)
                android.util.Log.d(TAG, "init: SSO postLogin success")
            } catch (e: SafetyVerifyRequiredException) {
                // SSO 路径上撞 Safety Verify：把页面字段写入 mfaContext，由后续 login() 触发 REQUIRE_MFA
                android.util.Log.w(TAG, "init: SSO postLogin hit SAFETY_VERIFY, capturing context")
                hasLogin = false
                captureSafetyVerify(e.response, e.responseBody)
            } catch (e: Exception) {
                hasLogin = false
                android.util.Log.e(TAG, "init: SSO postLogin failed", e)
            }
        } else if (executionInput.isEmpty() && existingClient == null) {
            // 无 existingClient 但页面不是登录表单 → 可能是错误页面
            android.util.Log.w(TAG, "init: No execution found and no existingClient! Body preview: ${responseBody.take(500)}")
        } else {
            // 正常登录页面，需要用户输入凭据
            mfaEnabled = extractMfaEnabled(responseBody)
            android.util.Log.d(TAG, "init: Normal login page, mfaEnabled=$mfaEnabled")
        }
    }

    /**
     * 是否需要验证码
     */
    fun isShowCaptcha(): Boolean = failCount >= 3

    /**
     * 获取验证码图片
     */
    fun getCaptchaImage(): ByteArray {
        val request = Request.Builder()
            .url("https://login.xjtu.edu.cn/cas/captcha.jpg")
            .get()
            .build()
        val response = client.newCall(request).execute()
        return response.body?.bytes() ?: ByteArray(0)
    }

    /**
     * 执行登录（状态机驱动）
     */
    fun login(
        username: String? = null,
        password: String? = null,
        jcaptcha: String = "",
        accountType: AccountType = AccountType.POSTGRADUATE,
        trustAgent: Boolean = true
    ): LoginResult {
        // 如果需要选择账户
        chooseAccountBody?.let {
            return finishAccountChoice(accountType, trustAgent)
        }

        // 保存凭据（即使已通过 SSO 登录也要存储，供后续 reAuth 使用）
        if (username != null && password != null) {
            this.username = username
            this.encryptedPassword = encryptPassword(password)
            this.rawPassword = password
            this.jcaptcha = jcaptcha
        }

        // ── 挂起的 SAFETY_VERIFY：init SSO 阶段 postLogin 撞到 Safety Verify 时已把
        //    mfaContext 写好但 hasLogin=false。此时 lastSafetyVerifyResponse 还未填，
        //    需要返回 REQUIRE_MFA 让 UI 弹验证码、verifyCode() 提交表单后再次进入主流程。
        val pendingSafety = mfaContext
        if (!hasLogin && pendingSafety != null
            && pendingSafety.flow == MFAFlow.SAFETY_VERIFY
            && pendingSafety.safetyTriggerUrl != null
            && lastSafetyVerifyResponse == null) {
            android.util.Log.d("XJTULogin", "login: pending SAFETY_VERIFY context, requesting MFA")
            return LoginResult(LoginState.REQUIRE_MFA, mfaContext = pendingSafety)
        }

        if (hasLogin) {
            return LoginResult(LoginState.SUCCESS, "SSO 自动认证成功", session = client)
        }

        if (this.username == null || this.encryptedPassword == null) {
            return LoginResult(LoginState.FAIL, "请提供用户名和密码")
        }

        // 验证码检查
        if (isShowCaptcha() && jcaptcha.isEmpty() && this.jcaptcha.isEmpty()) {
            return LoginResult(LoginState.REQUIRE_CAPTCHA)
        }

        // ── 消费 SAFETY_VERIFY 最终提交响应（如果有）──
        // 调用者（UI/AppLoginState）在 mfa.verifyCode() 后会再调 login() 推进状态机；
        // 此时如果是 Safety Verify 流程，verifyCode 已提交表单到原 URL，响应被缓存于 lastSafetyVerifyResponse，
        // 这里直接拿这份响应走成功/账户选择/错误处理流程，避免重复 POST 登录。
        lastSafetyVerifyResponse?.let { safetyResp ->
            lastSafetyVerifyResponse = null
            val body = try { safetyResp.body?.string() ?: "" } catch (_: Exception) { "" }
            return processLoginResponse(safetyResp, body)
        }

        // MFA 检测
        if (mfaEnabled && !hasLogin && (mfaContext == null || !mfaContext!!.required)) {
            android.util.Log.d("XJTULogin", "login: MFA detect starting")
            val formBody = FormBody.Builder()
                .add("username", this.username!!)
                .add("password", this.encryptedPassword!!)
                .add("fpVisitorId", fpVisitorId)
                .build()

            val request = Request.Builder()
                .url("https://login.xjtu.edu.cn/cas/mfa/detect")
                .header("Referer", postUrl)
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: "{}"
            android.util.Log.d("XJTULogin", "login: MFA detect response code=${response.code}, body=$responseStr")
            val data = try {
                responseStr.safeParseJsonObject()
                    .getAsJsonObject("data")
            } catch (e: Exception) {
                android.util.Log.e("XJTULogin", "login: MFA detect parse error", e)
                throw RuntimeException("MFA 检测返回数据异常: $responseStr")
            }

            val state = data.get("state").asString
            val need = data.get("need").asBoolean
            mfaContext = MFAContext(this, state, need)

            if (need) {
                return LoginResult(LoginState.REQUIRE_MFA, mfaContext = mfaContext)
            }
        }

        // 构造登录表单
        val mfaState = mfaContext?.state ?: ""
        val trustAgentStr = if (mfaContext?.required == true) {
            if (trustAgent) "true" else "false"
        } else ""

        val formBody = FormBody.Builder()
            .add("username", this.username!!)
            .add("password", this.encryptedPassword!!)
            .add("execution", executionInput)
            .add("_eventId", "submit")
            .add("submit1", "Login1")
            .add("fpVisitorId", fpVisitorId)
            .add("captcha", this.jcaptcha)
            .add("currentMenu", "1")
            .add("failN", failCount.toString())
            .add("mfaState", mfaState)
            .add("geolocation", "")
            .add("trustAgent", trustAgentStr)
            .build()

        // 发送登录请求（禁用自动重定向以便处理 302）
        val noRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val request = Request.Builder()
            .url(postUrl)
            .post(formBody)
            .build()

        // 使用原始 client（自动重定向）
        android.util.Log.d("XJTULogin", "login: POST to $postUrl")
        val loginResponse = client.newCall(request).execute()
        val loginBody = loginResponse.body?.string() ?: ""
        android.util.Log.d("XJTULogin", "login: POST response code=${loginResponse.code}, finalUrl=${loginResponse.request.url}, bodyLen=${loginBody.length}")

        return processLoginResponse(loginResponse, loginBody)
    }

    /**
     * 处理登录返回响应（无论来自初始表单提交，还是 Safety Verify 提交）。
     * 根据响应 HTML/HTTP code 识别错误提示、Safety Verify 页面、账户选择页面与成功。
     */
    private fun processLoginResponse(
        loginResponse: Response,
        loginBody: String,
    ): LoginResult {
        if (loginResponse.code == 401) {
            failCount++
            return LoginResult(LoginState.FAIL, "用户名或密码错误")
        }

        val alertMessage = extractAlertMessage(loginBody)
        if (alertMessage != null) {
            failCount++
            return LoginResult(LoginState.FAIL, "登录失败: $alertMessage")
        }

        // ── 检测 Safety Verify / MFA 二验页面 ──
        // 页面中含 <input name="secState" value="..."/> 与新 execution；SAFETY_VERIFY 流程走 /cas/sec/...。
        captureSafetyVerify(loginResponse, loginBody)?.let { return it }

        failCount = 0
        hasLogin = true

        // 检查是否需要选择账户
        val choices = extractAccountChoices(loginBody)
        if (choices.isNotEmpty()) {
            chooseAccountBody = loginBody
            hasLogin = false
            return LoginResult(
                LoginState.REQUIRE_ACCOUNT_CHOICE,
                accountChoices = choices
            )
        }

        lastResponseBody = loginBody
        // postLogin 可能在 SSO 回访目标域时再次撞上 Safety Verify（JWXT 高敏感 OAuth client 已实证）。
        // 子类抛 SafetyVerifyRequiredException，状态机在此捕获后转 REQUIRE_MFA。
        try {
            postLogin(loginResponse)
        } catch (e: SafetyVerifyRequiredException) {
            android.util.Log.d("XJTULogin", "processLoginResponse: postLogin triggered SAFETY_VERIFY")
            captureSafetyVerify(e.response, e.responseBody)?.let { return it }
            // 解析失败兜底（不应该发生）：退化为 FAIL，避免误报 SUCCESS
            hasLogin = false
            return LoginResult(LoginState.FAIL, "二次认证页面解析失败")
        }
        return LoginResult(LoginState.SUCCESS, session = client)
    }

    /**
     * 把响应识别为 CAS Safety Verify 页时，构造 mfaContext 并返回 REQUIRE_MFA。
     * 若响应不是 Safety Verify 页或字段缺失则返回 null（调用方继续走原路径）。
     *
     * 副作用：mfaContext 写入 SAFETY_VERIFY 上下文；hasLogin 置 false。
     */
    private fun captureSafetyVerify(response: Response, body: String): LoginResult? {
        if (response.code != 200 || !isSafetyVerifyPage(body)) return null
        val secStateValue = extractHiddenInput(body, "secState")
        val mfaExecValue = extractHiddenInput(body, "execution")
        if (secStateValue.isEmpty() || mfaExecValue.isEmpty()) {
            android.util.Log.w("XJTULogin", "captureSafetyVerify: 页面缺 secState/execution，跳过")
            return null
        }
        val triggerUrl = response.request.url.toString()
        val eventIdValue = extractHiddenInput(body, "_eventId").ifEmpty { "submit" }
        val submitValue = extractHiddenInput(body, "submit").ifEmpty { "Login1" }
        android.util.Log.d("XJTULogin", "captureSafetyVerify: SAFETY_VERIFY captured, triggerUrl=$triggerUrl")
        mfaContext = MFAContext(this, secStateValue, required = true, flow = MFAFlow.SAFETY_VERIFY).also {
            it.secState = secStateValue
            it.mfaExecution = mfaExecValue
            it.safetyTriggerUrl = triggerUrl
            it.safetyEventId = eventIdValue
            it.safetySubmitValue = submitValue
        }
        hasLogin = false
        return LoginResult(LoginState.REQUIRE_MFA, mfaContext = mfaContext)
    }

    /**
     * 完成账户选择
     */
    private fun finishAccountChoice(
        accountType: AccountType,
        trustAgent: Boolean = true
    ): LoginResult {
        val body = chooseAccountBody ?: throw RuntimeException("不需要选择账户")
        val choices = extractAccountChoices(body)

        val selectedLabel = when (accountType) {
            AccountType.UNDERGRADUATE -> choices.find { "本科" in it.name }?.label
            AccountType.POSTGRADUATE -> choices.find { "研究" in it.name }?.label
        } ?: throw RuntimeException("未找到匹配的账户类型")

        val trustAgentStr = if (mfaContext?.required == true) {
            if (trustAgent) "true" else "false"
        } else ""

        val execution = extractExecutionValue(body)

        val formBody = FormBody.Builder()
            .add("execution", execution)
            .add("_eventId", "submit")
            .add("geolocation", "")
            .add("fpVisitorId", fpVisitorId)
            .add("trustAgent", trustAgentStr)
            .add("username", selectedLabel)
            .add("useDefault", "false")
            .build()

        val request = Request.Builder()
            .url("https://login.xjtu.edu.cn/cas/login")
            .post(formBody)
            .build()

        val response = client.newCall(request).execute()
        lastResponseBody = response.body?.string() ?: ""
        chooseAccountBody = null
        hasLogin = true
        postLogin(response)
        return LoginResult(LoginState.SUCCESS, session = client)
    }

    /**
     * 登录后钩子，子类可重写以注入自定义 Token/Header
     */
    open fun postLogin(response: Response) {
        // 默认不做任何处理，子类可重写
    }

    /**
     * 使用已存储的凭据对指定 service 重新进行 CAS 认证
     * 用于子类在会话过期时自动重新登录（如 JWT token 过期）
     * @param serviceUrl CAS service 参数（认证成功后的回调地址）
     * @return Pair(responseBody, finalUrl)，或 null 表示无法重新认证
     */
    protected fun casAuthenticate(serviceUrl: String): Pair<String, String>? {
        if (username == null || encryptedPassword == null) {
            android.util.Log.w("XJTULogin", "casAuthenticate: no stored credentials")
            return null
        }
        val casUrl = "https://login.xjtu.edu.cn/cas/login?service=${
            java.net.URLEncoder.encode(serviceUrl, "UTF-8")
        }"
        val casResp = client.newCall(Request.Builder().url(casUrl).get().build()).execute()
        val casBody = casResp.body?.string() ?: ""
        val casFinalUrl = casResp.request.url.toString()
        android.util.Log.d("XJTULogin", "casAuthenticate: GET $casUrl → code=${casResp.code}, finalUrl=$casFinalUrl")

        val execution = extractExecutionValue(casBody)
        if (execution.isEmpty()) {
            // CAS 没有显示登录页 → SSO 可能已直接成功（重定向到了 service）
            android.util.Log.d("XJTULogin", "casAuthenticate: no execution → SSO redirect OK")
            return Pair(casBody, casFinalUrl)
        }

        // CAS 显示了登录页（TGC 过期），用存储凭据重新认证
        android.util.Log.d("XJTULogin", "casAuthenticate: TGC expired, re-posting credentials")
        val formBody = FormBody.Builder()
            .add("username", username!!)
            .add("password", encryptedPassword!!)
            .add("execution", execution)
            .add("_eventId", "submit")
            .add("submit1", "Login1")
            .add("fpVisitorId", fpVisitorId)
            .add("currentMenu", "1")
            .add("failN", "0")
            .add("mfaState", "")
            .add("geolocation", "")
            .build()
        val loginResp = client.newCall(
            Request.Builder().url(casResp.request.url.toString()).post(formBody).build()
        ).execute()
        val loginBody = loginResp.body?.string() ?: ""
        val loginFinalUrl = loginResp.request.url.toString()
        android.util.Log.d("XJTULogin", "casAuthenticate: POST → code=${loginResp.code}, finalUrl=$loginFinalUrl")
        // 检测 MFA 页面：若返回含 secState 则说明 TGC 过期后重新登录触发了 MFA，
        // 静默重认证无法处理 MFA，返回 null 让调用方回退到完整登录流程
        if (loginBody.contains("name=\"secState\"")) {
            android.util.Log.w("XJTULogin", "casAuthenticate: MFA triggered during re-auth, cannot proceed silently")
            return null
        }
        return Pair(loginBody, loginFinalUrl)
    }

    /** 获取当前 RSA 公钥（供缓存用） */
    fun getRsaPublicKey(): String? = rsaPublicKey

    /**
     * 从服务器获取 RSA 公钥
     */
    private fun fetchRsaPublicKeyFromServer(): String {
        val request = Request.Builder()
            .url("https://login.xjtu.edu.cn/cas/jwt/publicKey")
            .header("Referer", postUrl)
            .get()
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string()
            ?: throw IOException("RSA 公钥接口返回空响应 (HTTP ${response.code})")
        // 检测 HTML 错误页面 / 非 PEM 响应
        if (body.contains("<html", ignoreCase = true) || body.contains("<HTML", ignoreCase = true)) {
            throw IOException("RSA 公钥接口返回 HTML 错误页面，可能是网络代理拦截")
        }
        android.util.Log.d("XJTULogin", "fetchRsaPublicKey: ${body.take(80)}...")
        return body
    }

    /**
     * 将 PEM/原始 Base64 字符串解析为 RSA PublicKey
     */
    private fun parseRsaPublicKey(pemStr: String): java.security.PublicKey {
        // 去除 PEM 头尾和空白
        val keyStr = pemStr
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("-----END RSA PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")

        if (keyStr.isEmpty()) {
            throw java.security.spec.InvalidKeySpecException("公钥内容为空")
        }

        val keyBytes = Base64.decode(keyStr, Base64.DEFAULT)
        val keyFactory = KeyFactory.getInstance("RSA")

        // 优先尝试 X.509 (PKCS#8) 格式
        return try {
            keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))
        } catch (e: java.security.spec.InvalidKeySpecException) {
            // 回退：尝试 PKCS#1 格式 → 手动包装为 X.509
            android.util.Log.w("XJTULogin", "X.509 解析失败，尝试 PKCS#1 包装: ${e.message}")
            try {
                val pkcs1Header = byteArrayOf(
                    0x30.toByte(), 0x82.toByte(), 0x00.toByte(), 0x00.toByte(), // SEQUENCE (placeholder)
                    0x30.toByte(), 0x0D.toByte(),                               // SEQUENCE
                    0x06.toByte(), 0x09.toByte(),                               // OID
                    0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(), // 1.2.840.113549.1.1.1 (rsaEncryption)
                    0xF7.toByte(), 0x0D.toByte(), 0x01.toByte(), 0x01.toByte(),
                    0x01.toByte(),
                    0x05.toByte(), 0x00.toByte(),                               // NULL
                    0x03.toByte(), 0x82.toByte(), 0x00.toByte(), 0x00.toByte()  // BIT STRING (placeholder)
                )
                val bitStringContent = byteArrayOf(0x00.toByte()) + keyBytes // prepend unused-bits byte
                val bitStringLen = bitStringContent.size
                // BIT STRING length (2 bytes)
                pkcs1Header[pkcs1Header.size - 2] = ((bitStringLen shr 8) and 0xFF).toByte()
                pkcs1Header[pkcs1Header.size - 1] = (bitStringLen and 0xFF).toByte()
                val inner = pkcs1Header.sliceArray(4 until pkcs1Header.size) + bitStringContent
                val totalLen = inner.size
                val x509Bytes = byteArrayOf(
                    0x30.toByte(), 0x82.toByte(),
                    ((totalLen shr 8) and 0xFF).toByte(),
                    (totalLen and 0xFF).toByte()
                ) + inner
                keyFactory.generatePublic(X509EncodedKeySpec(x509Bytes))
            } catch (e2: Exception) {
                android.util.Log.e("XJTULogin", "PKCS#1 包装也失败: ${e2.message}")
                throw java.security.spec.InvalidKeySpecException(
                    "无法解析 RSA 公钥（X.509 和 PKCS#1 均失败）。密钥前 60 字符: ${keyStr.take(60)}", e
                )
            }
        }
    }

    /**
     * RSA 加密密码
     * 如果缓存的公钥解析失败，自动清除缓存并重新获取
     */
    fun encryptPassword(password: String, publicKeyStr: String? = null): String {
        // 1. 确定公钥字符串
        val pubKey = publicKeyStr ?: run {
            if (rsaPublicKey == null) {
                rsaPublicKey = fetchRsaPublicKeyFromServer()
            }
            rsaPublicKey!!
        }

        // 2. 尝试解析公钥，如果缓存的失败则重新获取
        val publicKey = try {
            parseRsaPublicKey(pubKey)
        } catch (e: Exception) {
            if (publicKeyStr != null) throw e // 外部传入的 key，不可重试
            android.util.Log.w("XJTULogin", "缓存的 RSA 公钥解析失败，重新获取: ${e.message}")
            rsaPublicKey = null // 清除坏缓存
            val freshKey = fetchRsaPublicKeyFromServer()
            rsaPublicKey = freshKey
            try {
                parseRsaPublicKey(freshKey)
            } catch (e2: Exception) {
                android.util.Log.e("XJTULogin", "重新获取的 RSA 公钥仍然解析失败", e2)
                throw RuntimeException("RSA 公钥解析失败，请检查网络或稍后重试", e2)
            }
        }

        // 3. RSA PKCS1 加密
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(password.toByteArray())
        val base64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)

        return "__RSA__$base64"
    }

    /**
     * 生成设备指纹 ID
     */
    private fun generateFpVisitorId(): String {
        val fingerprint = "${System.getProperty("os.name")}|${System.getProperty("os.arch")}|${UUID.randomUUID()}"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(fingerprint.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(32)
    }

    // ---- HTML 解析辅助函数 ----

    private fun extractExecutionValue(html: String): String {
        val doc = Jsoup.parse(html)
        val input = doc.select("input[name=execution]").first()
        return input?.attr("value") ?: ""
    }

    private fun extractHiddenInput(html: String, name: String): String {
        val doc = Jsoup.parse(html)
        val input = doc.select("input[name=$name]").first()
        return input?.attr("value") ?: ""
    }


    private fun extractMfaEnabled(html: String): Boolean {
        // 方式1: 从 globalConfig 中提取 mfaEnabled（支持 eval 和直接赋值格式）
        // 不尝试解析整个 JSON（可能含有难以转义的嵌套内容），直接用正则提取字段
        val mfaRegex = """["']?mfaEnabled["']?\s*[:=]\s*["']?(true|false)["']?""".toRegex()
        val mfaMatch = mfaRegex.find(html)
        if (mfaMatch != null) {
            val value = mfaMatch.groupValues[1]
            android.util.Log.d("XJTULogin", "extractMfaEnabled: found $value in HTML")
            return value == "true"
        }

        // 默认启用 MFA（安全兜底）
        android.util.Log.w("XJTULogin", "extractMfaEnabled: no mfaEnabled found, defaulting to true")
        return true
    }

    private fun extractAlertMessage(html: String): String? {
        val doc = Jsoup.parse(html)
        // 新版 CAS 使用 Vue <el-alert> 组件
        val elAlert = doc.select("el-alert").first()
        if (elAlert != null) {
            val title = elAlert.attr("title")
            if (title.isNotBlank()) return title
            val text = elAlert.text().takeIf { it.isNotBlank() }
            if (text != null) return text
        }
        // 旧版 CAS 使用 Bootstrap 错误样式
        val alert = doc.select(".alert-danger, .errors, #errorMessage").first()
        return alert?.text()?.takeIf { it.isNotBlank() }
    }

    private fun extractAccountChoices(html: String): List<AccountChoice> {
        val doc = Jsoup.parse(html)
        val choices = mutableListOf<AccountChoice>()

        // 新版: account-wrap div + el-radio 组件 (与 Python extract_account_choices 一致)
        doc.select("div.account-wrap").forEach { wrap ->
            val name = wrap.select("div.name").first()?.text()?.trim() ?: ""
            val label = wrap.select("el-radio.checkbox-radio").first()?.attr("label") ?: ""
            if (label.isNotBlank()) {
                choices.add(AccountChoice(name.ifEmpty { label }, label))
            }
        }
        if (choices.isNotEmpty()) return choices

        // 旧版: input radio 方式
        doc.select("input[name=username][type=radio], input[name=username][type=hidden]").forEach { input ->
            val label = input.attr("value")
            val name = input.parent()?.text() ?: label
            if (label.isNotBlank()) {
                choices.add(AccountChoice(name, label))
            }
        }
        return choices
    }

    /**
     * 保活状态。
     */
    enum class KeepAliveStatus {
        VALID,           // 登录态仍然有效
        AUTH_INVALID,    // 登录态已失效
        NETWORK_ERROR,   // 网络异常（无法判断）
        REAUTH_OK,       // 原态失效但已成功重认证
        ERROR            // 其他错误
    }

    /**
     * 验证当前子系统登录态是否仍然可信。
     * 基类默认返回 false（保守策略），子类应覆写。
     */
    open fun validateLogin(): Boolean = false

    /**
     * 保活一次：先 validate，失效则 reAuth。
     * 返回 KeepAliveStatus 供 SessionKeepAlive 汇总报告。
     */
    open fun keepAlive(): KeepAliveStatus {
        return try {
            if (validateLogin()) KeepAliveStatus.VALID
            else KeepAliveStatus.AUTH_INVALID
        } catch (_: java.io.IOException) {
            KeepAliveStatus.NETWORK_ERROR
        } catch (_: Exception) {
            KeepAliveStatus.ERROR
        }
    }

    enum class AccountType {
        UNDERGRADUATE,
        POSTGRADUATE
    }

    companion object {
        /**
         * 判断 HTML 是否为统一认证返回的「Safety Verify」二次认证页面。
         * 检测条件：fm1 表单含 secState/execution/_eventId 三个字段，
         * 且文档标题含 "Safety Verify" 或文档体含 "/cas/sec/initByType"、「选择安全认证」、「二次认证」。
         */
        @JvmStatic
        fun isSafetyVerifyPage(html: String): Boolean {
            if (html.isBlank()) return false
            return try {
                val doc = Jsoup.parse(html)
                val form = doc.selectFirst("#fm1") ?: return false
                val hasVerifyForm = form.selectFirst("input[name=secState]")?.attr("value")?.isNotEmpty() == true
                val hasExecution = form.selectFirst("input[name=execution]")?.attr("value")?.isNotEmpty() == true
                val hasSubmitEvent = form.selectFirst("input[name=_eventId]")?.attr("value")?.isNotEmpty() == true
                val title = doc.selectFirst("title")?.text() ?: ""
                val hasSafetyTitle = "Safety Verify" in title
                val hasSecInitApi = "/cas/sec/initByType" in html || "\\/cas\\/sec\\/initByType" in html
                val hasSafetyText = "选择安全认证" in html || "二次认证" in html
                hasVerifyForm && hasExecution && hasSubmitEvent && (hasSafetyTitle || hasSecInitApi || hasSafetyText)
            } catch (_: Exception) {
                html.contains("name=\"secState\"") && html.contains("name=\"execution\"")
            }
        }

        /**
         * 判断响应 HTML 是否表明当前业务站点的登录态已失效。
         *
         * 检测两种场景：Safety Verify 页面和统一身份认证登录页。
         *
         * 用法：在各子系统的 executeWithReAuth 中，对响应 body 调用此方法，
         *       若返回 true 则 reAuthenticate + 重放请求。
         */
        @JvmStatic
        fun isAuthFailureResponse(html: String): Boolean {
            if (html.isBlank()) return false
            if (isSafetyVerifyPage(html)) return true
            // 统一身份认证登录页（fm1 表单 + CAS 标识）
            val hasLoginForm = "id=\"fm1\"" in html && "name=\"execution\"" in html
            val hasLoginMarker = "login.xjtu.edu.cn" in html ||
                    "cas/login" in html ||
                    "统一身份认证" in html
            return hasLoginForm && hasLoginMarker
        }

        // 常用登录地址
        /** 本科生考勤系统 OAuth 登录（直连模式，经 org.xjtu.edu.cn 中转） */
        const val ATTENDANCE_URL = "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1372&redirectUri=https://bkkq.xjtu.edu.cn/berserker-auth/auth/attendance-pc/casReturn&responseType=code&scope=user_info&state=1234"
        /** 本科生考勤系统直连登录（WebVPN 模式，直接访问 bkkq，更短的 CAS 链） */
        const val ATTENDANCE_WEBVPN_URL = "http://bkkq.xjtu.edu.cn"
        /** 研究生考勤系统 OAuth 登录（appId=1245，redirect 到 yjskq；上游 4757a093 已切 https） */
        const val POSTGRADUATE_ATTENDANCE_URL = "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1245&redirectUri=https://yjskq.xjtu.edu.cn/berserker-auth/auth/attendance-pc/casReturn&responseType=code&scope=user_info&state=1234"
        /** 研究生考勤系统直连登录（WebVPN 模式） */
        const val POSTGRADUATE_ATTENDANCE_WEBVPN_URL = "http://yjskq.xjtu.edu.cn"
        const val JWXT_URL = "https://jwxt.xjtu.edu.cn/jwapp/sys/homeapp/index.do"
    }
}
