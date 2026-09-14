package com.xjtu.toolbox.startup

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.agent.bot.warmUpEyeFit
import com.xjtu.toolbox.util.SecurePrefs
import kotlin.concurrent.thread

/**
 * 冷启动预热：把首帧路径上确定会发生的一次性重活挪到后台线程。
 *
 * 两件事：
 *
 * 1. **加密存储**。创建要做 Keystore 密钥派生，实测（模拟器）首次约 137ms、之后每次
 *    约 20ms。这些文件在首帧前就会被读到——`AppLoginStateViewModel` 的 init 里构造
 *    凭据存储、账号存储、会话管理器，全在首帧路径上，而 splash 一直挡到首帧之后才
 *    放开，所以这段时间用户是**看得见**的。
 *
 * 2. **屁岱形象的眼睛贴合表**。它在类加载时构建，实测约 80ms，而底栏是首帧就渲染的，
 *    `sample()` 会无条件读到它。见 [warmUpEyeFit]。
 *
 * 做法：在 [android.app.Application.onCreate] 起一个后台线程依次做掉这两件。前台稍后
 * 真正需要时，[SecurePrefs.get] 与类初始化都会阻塞在同一处——预热带走的活越多，前台
 * 剩下的等待越短，不需要额外的 latch 协调。
 *
 * 为什么不猜 cookies 文件名：账号后缀是在 `restoreActiveAccount` 里才确定的，在那里
 * 猜错反而白开一个文件。凭据与账号两个文件是固定的，它们才是启动路径上的大头。
 */
object ColdStartPreloader {

    /** 账号存储文件名，与 [com.xjtu.toolbox.account.AccountStore] 保持一致。 */
    private const val FILE_ACCOUNTS = "xjtu_accounts"

    /** 凭据存储文件名，与 [com.xjtu.toolbox.util.CredentialStore] 保持一致。 */
    private const val FILE_CREDENTIALS = "xjtu_credentials"

    /**
     * 起后台线程预热。[onDone] 在完成后回调（测试用，业务不传）。
     *
     * 每步各自 runCatching：预热失败不该影响本次启动——前台首次使用时本来就会重试一遍。
     */
    fun preloadInBackground(context: Context, onDone: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        thread(name = "cold-start-preload", isDaemon = true) {
            try {
                runCatching { preloadSecureStores(appContext) }
                runCatching { warmUpEyeFit() }
            } finally {
                onDone?.invoke()
            }
        }
    }

    private fun preloadSecureStores(appContext: Context) {
        SecurePrefs.preload(appContext, listOf(FILE_CREDENTIALS, FILE_ACCOUNTS)) { ctx, name ->
            ctx.getSharedPreferences("${name}_fallback", Context.MODE_PRIVATE)
        }
        preloadAccountCookieJars(appContext)
    }

    /**
     * 预热当前激活账号的两份 cookie 文件。
     *
     * 账号 id 在冷启动时还读不到（它自己就在账号存储里），所以这里只做一次**便宜的**
     * 尝试：读默认后缀。真正带账号后缀的文件留给前台首次使用时打开——那时它已经在
     * 主线程上，省不掉，但至少不再叠加上面两个固定文件的成本。
     */
    private fun preloadAccountCookieJars(appContext: Context) {
        val suffix = runCatching { AccountContext.safeSuffix() }.getOrDefault("default")
        SecurePrefs.preload(
            appContext,
            listOf("cookies_normal$suffix", "cookies_webvpn$suffix"),
        ) { ctx, name -> ctx.getSharedPreferences("${name}_fallback", Context.MODE_PRIVATE) }
    }
}
