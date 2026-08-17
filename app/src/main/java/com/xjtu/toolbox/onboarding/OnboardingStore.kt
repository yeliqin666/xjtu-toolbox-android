package com.xjtu.toolbox.onboarding

import android.content.Context

/**
 * 首启标记。
 *
 * 4.6 起不再有轮播引导：首启唯一要做的事，是把还没有凭据的新用户送到登录页一次。
 * 功能介绍交给首页本身——它就是功能总览，划完就能看见，比 chip 云真实。
 *
 * 用单独 prefs 而非挂到 [com.xjtu.toolbox.util.CredentialStore] 下：
 * 切账号不应让它重新触发，账号无关。
 *
 * **不放入 Auto Backup 白名单**：重装/换机后重新引导一次是预期行为。
 *
 * 这里刻意不做版本号比较——首启跳转对老用户没有意义，
 * 有变更要告知走 [com.xjtu.toolbox.util.AppChangelog] 的更新公告。
 */
internal object OnboardingStore {
    private const val PREFS = "onboarding"
    private const val KEY_DONE = "done"

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE, true)
            .apply()
    }

    fun needsFirstRunLogin(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)
}
