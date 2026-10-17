package com.xjtu.toolbox.bulletin

import com.xjtu.toolbox.platform.keyValueStore
/** 公告本地状态：上次成功的 JSON 缓存、已关闭 / 已确认的 id。 */
class BulletinStore {
    private val prefs = keyValueStore(PREFS)

    var cachedJson: String?
        get() = prefs.getString(KEY_CACHE)
        set(value) {
            prefs.putString(KEY_CACHE, value)
        }

    val dismissedIds: Set<String>
        get() = prefs.getStringSet(KEY_DISMISSED) ?: emptySet()

    val ackedIds: Set<String>
        get() = prefs.getStringSet(KEY_ACKED) ?: emptySet()

    fun dismiss(id: String) {
        val next = dismissedIds + id
        prefs.putStringSet(KEY_DISMISSED, next)
    }

    fun ack(id: String) {
        val next = ackedIds + id
        prefs.putStringSet(KEY_ACKED, next)
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
