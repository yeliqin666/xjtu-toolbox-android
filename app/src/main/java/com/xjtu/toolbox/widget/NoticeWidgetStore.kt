package com.xjtu.toolbox.widget

import android.content.Context

/**
 * NoticeWidget 的轻量缓存：最多 3 条标题 + 更新时间戳。
 *
 * 写入方：通知页加载成功，以及 [com.xjtu.toolbox.home.HomeStatsRefresher] 后台抓取。
 * 读取方：[NoticeWidgetUpdater]。
 *
 * 不放入 Auto Backup 白名单：通知标题是临时信息，重装后由应用重新拉取，
 * 没必要把缓存一起还原。
 */
internal object NoticeWidgetStore {
    private const val PREFS = "notice_widget_cache"
    private val TITLE_KEYS = listOf("title_0", "title_1", "title_2")
    private const val KEY_TIME = "updated_at"

    data class Snapshot(val titles: List<String>, val updatedAt: Long)

    fun read(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val titles = TITLE_KEYS.mapNotNull { p.getString(it, null) }
            .filter { it.isNotBlank() }
        return Snapshot(titles, p.getLong(KEY_TIME, 0L))
    }

    fun write(context: Context, titles: List<String>) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = p.edit()
        // 多余的槽位清空，避免上次写满 3 条后下次只写 1 条残留旧数据
        for (i in 0..2) {
            val title = titles.getOrNull(i)
            if (title != null) {
                editor.putString(TITLE_KEYS[i], title)
            } else {
                editor.remove(TITLE_KEYS[i])
            }
        }
        editor.putLong(KEY_TIME, System.currentTimeMillis())
        editor.apply()
    }
}