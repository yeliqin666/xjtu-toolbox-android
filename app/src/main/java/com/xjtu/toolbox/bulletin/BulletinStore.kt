package com.xjtu.toolbox.bulletin

import android.content.Context

/**
 * 公告本地状态：上次成功的 JSON 缓存、已关闭 / 已确认的 id。
 * force_update 的「稍后」只记在进程内，下次冷启动再出现。
 */
class BulletinStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var cachedJson: String?
        get() = prefs.getString(KEY_CACHE, null)
        set(value) {
            prefs.edit().putString(KEY_CACHE, value).apply()
        }

    val dismissedIds: Set<String>
        get() = prefs.getStringSet(KEY_DISMISSED, emptySet())?.toSet().orEmpty()

    val ackedIds: Set<String>
        get() = prefs.getStringSet(KEY_ACKED, emptySet())?.toSet().orEmpty()

    fun dismiss(id: String) {
        val next = dismissedIds + id
        prefs.edit().putStringSet(KEY_DISMISSED, next).apply()
    }

    fun ack(id: String) {
        val next = ackedIds + id
        prefs.edit().putStringSet(KEY_ACKED, next).apply()
    }

    fun snooze(id: String) {
        snoozedIds += id
    }

    fun peekCached(): List<Bulletin> {
        val json = cachedJson ?: return emptyList()
        return BulletinRules.parsePayload(json)
    }

    companion object {
        private const val PREFS = "bulletin_store"
        private const val KEY_CACHE = "cached_json"
        private const val KEY_DISMISSED = "dismissed_ids"
        private const val KEY_ACKED = "acked_ids"

        private val snoozedIds: MutableSet<String> = mutableSetOf()

        fun sessionSnoozedIds(): Set<String> = snoozedIds.toSet()
    }
}
