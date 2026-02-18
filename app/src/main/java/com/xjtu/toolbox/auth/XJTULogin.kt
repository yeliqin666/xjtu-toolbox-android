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
import com.google.gson.JsonParser
import java.net.CookieManager
import java.net.CookiePolicy
import java.io.IOException
import java.util.UUID
import java.security.MessageDigest

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
 * MFA 两步验证上下文
 */
class MFAContext(
    private val login: XJTULogin,
    val state: String,
    val required: Boolean = true
) {
    var gid: String? = null
    private var phoneNumber: String? = null

    /**
     * 获取绑定的手机号（中间四位屏蔽）
     */
    fun getPhoneNumber(): String {
        phoneNumber?.let { return it }

        val request = Request.Builder()
            .url("https://login.xjtu.edu.cn/cas/mfa/initByType/securephone?state=$state")
            .get()
            .build()

        val response = login.client.newCall(request).execute()
        val json = JsonParser.parseString(response.body?.string()).asJsonObject
        if (json.get("code").asInt == 0) {
            val data = json.getAsJsonObject("data")
            gid = data.get("gid").asString
            phoneNumber = data.get("securePhone").asString
            return phoneNumber!!
        } else {
            throw RuntimeException("获取手机号失败")
        }
    }

    /**
     * 发送验证码到手机
     */
    fun sendVerifyCode(): String {
        val phone = getPhoneNumber()
        val json = Gson().toJson(mapOf("gid" to gid))
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://login.xjtu.edu.cn/attest/api/guard/securephone/send")
            .post(body)
            .build()

        val response = login.client.newCall(request).execute()
        val result = JsonParser.parseString(response.body?.string()).asJsonObject
        if (result.get("code").asInt == 0) {
            return phone
        } else {
            throw RuntimeException(result.get("message").asString)
        }
    }

    /**
     * 验证手机验证码
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
        val result = JsonParser.parseString(response.body?.string()).asJsonObject
        if (result.get("code").asInt != 0) {
            throw RuntimeException(result.get("message").asString)
        }
    }
}

/**
 * 西安交通大学统一身份认证登录
 * 适用于 2025 年 7 月 17 日后的新认证系统 (login.xjtu.edu.cn)
 * 从 XJTUToolBox 的 Python 实现翻译而来
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

        android.util.Log.d(TAG, "init: responseCode=${response.code}, postUrl=$postUrl")
        android.util.Log.d(TAG, "init: responseBodyLen=${responseBody.length}")

        // 提取 execution value（如果页面是登录表单）
        executionInput = extractExecutionValue(responseBody)

        android.util.Log.d(TAG, "init: executionInput.isEmpty=${executionInput.isEmpty()}, existingClient=${existingClient != null}")

        if (executionInput.isEmpty() && existingClient != null) {
            // SSO: 共享的 CookieManager 携带 TGC，CAS 自动完成认证并跳转到目标服务
            hasLogin = true
            mfaEnabled = false
            try {
                lastResponseBody = responseBody
                postLogin(response)
                android.util.Log.d(TAG, "init: SSO postLogin success")
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
                JsonParser.parseString(responseStr).asJsonObject
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

        if (loginResponse.code == 401) {
            failCount++
            return LoginResult(LoginState.FAIL, "用户名或密码错误")
        }

        // 检查错误消息
        val alertMessage = extractAlertMessage(loginBody)
        if (alertMessage != null) {
            failCount++
            return LoginResult(LoginState.FAIL, "登录失败: $alertMessage")
        }

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
        postLogin(loginResponse)
        return LoginResult(LoginState.SUCCESS, session = client)
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
     * 用于子类在会话过期时自动重新登录（如校园卡 hallticket 过期）
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
        return Pair(loginBody, loginFinalUrl)
    }

    /** 获取当前 RSA 公钥（供缓存用） */
    fun getRsaPublicKey(): String? = rsaPublicKey

    /**
     * RSA 加密密码
     */
    fun encryptPassword(password: String, publicKeyStr: String? = null): String {
        val pubKey = publicKeyStr ?: run {
            if (rsaPublicKey == null) {
                val request = Request.Builder()
                    .url("https://login.xjtu.edu.cn/cas/jwt/publicKey")
                    .header("Referer", postUrl)
                    .get()
                    .build()
                rsaPublicKey = client.newCall(request).execute().body?.string()
            }
            rsaPublicKey!!
        }

        // 解析 PEM 公钥
        val keyStr = pubKey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")

        val keyBytes = Base64.decode(keyStr, Base64.DEFAULT)
        val keySpec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicKey = keyFactory.generatePublic(keySpec)

        // RSA PKCS1 加密
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

    enum class AccountType {
        UNDERGRADUATE,
        POSTGRADUATE
    }

    companion object {
        // 常用登录地址
        /** 考勤系统 OAuth 登录（直连模式，经 org.xjtu.edu.cn 中转） */
        const val ATTENDANCE_URL = "http://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1372&redirectUri=http://bkkq.xjtu.edu.cn/berserker-auth/auth/attendance-pc/casReturn&responseType=code&scope=user_info&state=1234"
        /** 考勤系统直连登录（WebVPN 模式，直接访问 bkkq，更短的 CAS 链）*/
        const val ATTENDANCE_WEBVPN_URL = "http://bkkq.xjtu.edu.cn"
        const val JWXT_URL = "https://jwxt.xjtu.edu.cn/jwapp/sys/homeapp/index.do"
    }
}
