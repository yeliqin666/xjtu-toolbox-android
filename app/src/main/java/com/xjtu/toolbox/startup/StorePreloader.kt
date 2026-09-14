package com.xjtu.toolbox.startup

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.util.SecurePrefs
import kotlin.concurrent.thread

/**
 * 冷启动预热。
 *
 * 加密 SharedPreferences 的创建要做 Keystore 密钥派生，实测（模拟器）首次约 137ms、
 * 之后每次约 20ms。这些文件在首帧前就会被读到——`AppLoginStateViewModel` 的 init
 * 里构造凭据存储、账号存储、会话管理器，全在首帧路径上。而 splash 一直挡到首帧
 * 之后才放开，所以这段时间用户是**看得见**的。
 *
 * 做法：在 [android.app.Application.onCreate] 起一个后台线程，把冷启动路径上确定
 * 会用到的几个文件先打开。前台稍后真正需要时，[SecurePrefs.get] 会阻塞在同一把锁
 * 上——预热带走的活越多，前台剩下的等待越短，不需要额外的 latch 协调。
 *
 * 为什么不猜 cookies 文件名：账号后缀是在 `restoreActiveAccount` 里才确定的，在那里
 * 猜错反而白开一个文件。凭据与账号两个文件是固定的，它们才是启动路径上的大头。
 */
object StorePreloader {

    /** 账号存储文件名，与 [com.xjtu.toolbox.account.AccountStore] 保持一致。 */
    private const val FILE_ACCOUNTS = "xjtu_accounts"

    /** 凭据存储文件名，与 [com.xjtu.toolbox.util.CredentialStore] 保持一致。 */
    private const val FILE_CREDENTIALS = "xjtu_credentials"

    /**
     * 起后台线程预热。[onDone] 在完成后回调（测试用，业务不传）。
     */
    fun preloadInBackground(context: Context, onDone: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        thread(name = "secure-prefs-preload", isDaemon = true) {
            try {
                SecurePrefs.preload(appContext, listOf(FILE_CREDENTIALS, FILE_ACCOUNTS)) { ctx, name ->
                    ctx.getSharedPreferences("${name}_fallback", Context.MODE_PRIVATE)
                }
                preloadAccountCookieJars(appContext)
            } finally {
                onDone?.invoke()
            }
        }
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
