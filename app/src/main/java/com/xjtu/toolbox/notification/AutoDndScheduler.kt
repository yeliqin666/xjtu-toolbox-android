package com.xjtu.toolbox.notification

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.xjtu.toolbox.schedule.AutoDndAccess

/**
 * 自动勿扰：上课（课前、课后各留 3 分钟）和在图书馆入座（预约状态「使用中」）时把系统勿扰切到「仅优先」，
 * 两者都不满足时还原。只还原本应用切过的那一次：用户中途手动改过勿扰，就不去动它。
 * 排程挂在 [DynamicScheduleScheduler.refresh] 里，与通知共用同一个精确闹钟。
 */
internal object AutoDndScheduler {
    private const val PREFS = "auto_dnd"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROMPT_DISMISSED = "prompt_dismissed"
    private const val KEY_APPLIED = "applied"
    private const val KEY_SAVED_FILTER = "saved_filter"
    private const val KEY_LIBRARY_INSIDE = "library_inside"
    private const val MARGIN_MS = 3 * 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun hasAccess(context: Context): Boolean = manager(context)?.isNotificationPolicyAccessGranted == true

    fun isPromptDismissed(context: Context): Boolean = prefs(context).getBoolean(KEY_PROMPT_DISMISSED, false)

    fun dismissPrompt(context: Context) {
        prefs(context).edit().putBoolean(KEY_PROMPT_DISMISSED, true).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) restore(context)
    }

    /** 图书馆预约状态变了就记一笔（入座 = 「使用中」，暂离、离馆都不算），变了才重排。 */
    fun setLibraryInside(context: Context, inside: Boolean) {
        val p = prefs(context)
        if (p.getBoolean(KEY_LIBRARY_INSIDE, false) == inside) return
        p.edit().putBoolean(KEY_LIBRARY_INSIDE, inside).apply()
        DynamicScheduleScheduler.requestRefresh(context)
    }

    /**
     * 按今天的课表切勿扰；返回下一个切换时刻（没有则 null）。
     * 没开或没有授权时只做还原，不排闹钟。
     */
    fun apply(context: Context, items: List<DynamicScheduleItem>, nowMs: Long): Long? {
        val nm = manager(context)
        if (!isEnabled(context) || nm == null || !hasAccess(context)) {
            restore(context)
            return null
        }
        val windows = items.map { it.startAt - MARGIN_MS to it.endAt + MARGIN_MS }
        val p = prefs(context)
        val inside = windows.any { nowMs in it.first until it.second } || p.getBoolean(KEY_LIBRARY_INSIDE, false)
        if (inside && !p.getBoolean(KEY_APPLIED, false)) {
            p.edit()
                .putInt(KEY_SAVED_FILTER, nm.currentInterruptionFilter)
                .putBoolean(KEY_APPLIED, true)
                .apply()
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else if (!inside) {
            restore(context)
        }
        return windows.flatMap { listOf(it.first, it.second) }.filter { it > nowMs }.minOrNull()
    }

    /** 窗口结束或功能关闭时调用；若用户已在窗口内手动改过勿扰，只清标记。 */
    fun restore(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_APPLIED, false)) return
        val nm = manager(context)
        if (nm != null && hasAccess(context) &&
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
        ) {
            nm.setInterruptionFilter(p.getInt(KEY_SAVED_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL))
        }
        p.edit().putBoolean(KEY_APPLIED, false).remove(KEY_SAVED_FILTER).apply()
    }
}

/** 日程页用的那一格：开关状态、授权引导与首次提示。 */
internal class AndroidAutoDnd(private val context: Context) : AutoDndAccess {
    override val enabled: Boolean get() = AutoDndScheduler.isEnabled(context)
    override val granted: Boolean get() = AutoDndScheduler.hasAccess(context)
    override fun shouldPrompt(): Boolean = !enabled && !AutoDndScheduler.isPromptDismissed(context)
    override fun dismissPrompt() = AutoDndScheduler.dismissPrompt(context)

    override fun setEnabled(on: Boolean) {
        AutoDndScheduler.setEnabled(context, on)
        DynamicScheduleScheduler.requestRefresh(context)
    }

    override fun openAccessSettings() {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
