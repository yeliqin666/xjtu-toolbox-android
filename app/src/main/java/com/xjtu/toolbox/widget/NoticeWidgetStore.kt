package com.xjtu.toolbox.widget

import android.content.Context

/**
 * NoticeWidget 的轻量缓存：最多 3 条标题 + 更新时间戳。
 *
 * 写入方：[com.xjtu.toolbox.notification.NotificationScreen] 在成功加载后写一份。
 * 读取方：[NoticeWidgetUpdater]。
 *
 * 不放入 Auto Backup 白名单：通知标题是临时信息，重装后由应用重新拉取，
 * 没必要把缓存一起还原。
 */
internal object NoticeWidgetStore {
    private const val PREFS = "notice_widget_cache"
    private const val KEY_T0 = "title_0"
    private const val KEY_T1 = "title_1"
    private const val KEY_T2 = "title_2"
    private const val KEY_TIME = "updated_at"

    data class Snapshot(val titles: List<String>, val updatedAt: Long)

    fun read(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val titles = listOfNotNull(
            p.getString(KEY_T0, null),
            p.getString(KEY_T1, null),
            p.getString(KEY_T2, null),
        ).filter { it.isNotBlank() }
        return Snapshot(titles, p.getLong(KEY_TIME, 0L))
    }

    fun write(context: Context, titles: List<String>) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = p.edit()
        editor.putString(KEY_T0, titles.getOrNull(0))
        editor.putString(KEY_T1, titles.getOrNull(1))
        editor.putString(KEY_T2, titles.getOrNull(2))
        editor.putLong(KEY_TIME, System.currentTimeMillis())
        editor.apply()
    }
}