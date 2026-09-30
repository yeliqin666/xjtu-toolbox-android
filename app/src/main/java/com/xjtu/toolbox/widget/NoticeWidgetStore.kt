package com.xjtu.toolbox.widget

import android.content.Context

/**
 * NoticeWidget 的轻量缓存：最近几条通知（标题 + 发布日期）和更新时间戳。
 *
 * 写入方：[com.xjtu.toolbox.notification.NoticeWatchSync]（通知页加载、首页刷新、后台抓取都走它）。
 * 读取方：[NoticeWidgetUpdater]。
 *
 * 不放入 Auto Backup 白名单：通知标题是临时信息，重装后由应用重新拉取，
 * 没必要把缓存一起还原。
 */
internal object NoticeWidgetStore {
    private const val PREFS = "notice_widget_cache"
    const val MAX_ENTRIES = 4
    private const val KEY_TIME = "updated_at"

    data class Entry(val title: String, /** 发布日期（epochDay），不知道为 null。 */ val day: Long?)

    data class Snapshot(val entries: List<Entry>, val updatedAt: Long)

    fun read(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = (0 until MAX_ENTRIES).mapNotNull { i ->
            p.getString("title_$i", null)?.takeIf { it.isNotBlank() }
                ?.let { Entry(it, p.getLong("day_$i", -1L).takeIf { d -> d >= 0 }) }
        }
        return Snapshot(entries, p.getLong(KEY_TIME, 0L))
    }

    fun write(context: Context, entries: List<Entry>) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        // 多余的槽位清空，避免上次写满后下次写得少时残留旧数据
        for (i in 0 until MAX_ENTRIES) {
            val entry = entries.getOrNull(i)
            if (entry != null) {
                editor.putString("title_$i", entry.title)
                if (entry.day != null) editor.putLong("day_$i", entry.day) else editor.remove("day_$i")
            } else {
                editor.remove("title_$i").remove("day_$i")
            }
        }
        editor.putLong(KEY_TIME, System.currentTimeMillis())
        editor.apply()
    }
}
