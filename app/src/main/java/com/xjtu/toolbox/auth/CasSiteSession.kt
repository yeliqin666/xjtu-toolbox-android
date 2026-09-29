package com.xjtu.toolbox.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * 通过 [XJTULogin] 状态机完成 CAS 认证的站点会话基类。
 *
 * 大多数业务子系统的登录形态相同——访问站点 loginUrl，被重定向到 login.xjtu.edu.cn，
 * 走 CAS 验证后回调到本站 service URL 完成 SSO 并写入本站 cookies。差异点：
 * - loginUrl 不同 → [createLogin] 提供。
 * - 本科/研究生身份选择 → [accountType] 覆盖。
 * - 局部 token 提取（JWT / Authorization header 等）→ [onLoginSuccess] 处理。
 *
 * 子类一般 20-50 行。
 */
abstract class CasSiteSession(
    siteKey: String,
    siteName: String,
    mustUseWebVpn: Boolean = true,
) : SiteSession(siteKey, siteName, mustUseWebVpn) {

    /** 子类提供本站的 [XJTULogin] 实例工厂。OkHttpClient 已绑定 backend cookieJar，子类直接传入即可。 */
    protected abstract fun createLogin(
        client: OkHttpClient,
        visitorId: String?,
        cachedRsaKey: String?,
    ): XJTULogin

    /** 若账户存在多重身份，本站选择哪一种。默认研究生。 */
    protected open val accountType: XJTULogin.AccountType
        get() = manager?.accountType ?: XJTULogin.AccountType.UNDERGRADUATE

    /** 登录成功后回调。子类可在此提取本站局部 token，写入 [localToken]。 */
    protected open fun onLoginSuccess(login: XJTULogin) {}

    /**
     * 全局串行：所有站点的 CAS 登录（含 TGC 已建好后的 SSO 直通）都排在同一把锁后面。
     * 真机上多个站点并发登录时，考勤拿不到重定向回来的 token，服务端风控还会连续弹出
     * 多条独立的 MFA 短信；同一时刻只让一个站点跟 CAS 打交道，牺牲一点并发速度换正确性。
     */
    override suspend fun runLogin(username: String, password: String) {
        casLoginLock.withLock { runCasLogin(username, password) }
    }

    private suspend fun runCasLogin(username: String, password: String) {
        val backend = checkNotNull(backend) { "[$siteKey] backend not bound" }
        val xl = createLogin(
            client = backend.client,
            visitorId = manager?.fpVisitorId,
            cachedRsaKey = manager?.cachedRsaKey,
        )

        var result = xl.login(username, password)
        loop@ while (true) {
            when (result.state) {
                LoginState.SUCCESS -> {
                    manager?.adoptFromLogin(xl)
                    onLoginSuccess(xl)
                    break@loop
                }
                LoginState.FAIL -> {
                    val msg = result.message
                    if (isCredentialFailure(msg)) {
                        throw PasswordInvalidatedException(siteName, msg)
                    }
                    throw IOException("$siteName 登录失败：${msg.ifBlank { "未知错误" }}")
                }
                LoginState.REQUIRE_MFA -> {
                    val ctx = result.mfaContext
                        ?: throw IOException("$siteName 未返回 MFA 上下文")
                    // 静默流程（后台预热/保活）到此为止：不弹窗、不发短信，交回给用户下次主动进入时处理。
                    if (silentLogin) {
                        throw MfaRequiredException(siteName)
                    }
                    // SAFETY_VERIFY：落到 Safety Verify 页时 CAS 已经下发过短信，
                    // 再调 sendVerifyCode 会重复发。MFA_DETECT 才需要主动 POST /send。
                    if (ctx.flow == MFAFlow.MFA_DETECT) {
                        try {
                            ctx.sendVerifyCode()
                        } catch (e: Exception) {
                            throw IOException("$siteName 发送验证码失败：${e.message}", e)
                        }
                    }
                    val mgr = manager ?: throw IOException("$siteName SessionManager unavailable")
                    if (!mgr.verifyMfaWithUser(siteKey, siteName, ctx)) throw MfaCancelledException(siteName)
                    result = xl.login()
                }
                LoginState.REQUIRE_CAPTCHA -> {
                    throw IOException("$siteName 需要图形验证码")
                }
                LoginState.REQUIRE_ACCOUNT_CHOICE -> {
                    result = xl.login(accountType = accountType)
                }
            }
        }
    }

    private fun isCredentialFailure(msg: String): Boolean =
        msg.contains("用户名或密码", ignoreCase = true) ||
            msg.contains("密码错误", ignoreCase = true) ||
            msg.contains("账号或密码", ignoreCase = true) ||
            msg.contains("401")

    companion object {
        /** 全局唯一：所有 CAS 站点共用的登录锁，见 [runLogin] 上的说明。 */
        private val casLoginLock = Mutex()
    }
}
