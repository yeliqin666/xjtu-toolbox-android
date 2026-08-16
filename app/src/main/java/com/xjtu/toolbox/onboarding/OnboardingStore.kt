package com.xjtu.toolbox.onboarding

import android.content.Context

/**
 * Onboarding 完成标志。
 *
 * 用单独 prefs 而非挂到 [com.xjtu.toolbox.util.CredentialStore] 下：
 * 切账号不应让 onboarding 重新出现，账号无关。
 *
 * **不放入 Auto Backup 白名单**：onboarding 是一次性引导，重装/换机后
 * 自然重看——这就是预期行为。
 */
internal object OnboardingStore {
    private const val PREFS = "onboarding"
    private const val KEY_DONE = "done"

    /** 完成当前版本 onboarding（写入 done=1）。 */
    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE, true)
            .apply()
    }

    /** 是否需要展示 onboarding（false = 已完成 / true = 首次启动）。 */
    fun needsToShow(context: Context): Boolean {
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)
    }
}