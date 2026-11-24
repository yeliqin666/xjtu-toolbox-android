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

    /** 这台设备在某个投票里选了什么（反馈页显示「你投了」）；没投过返回 null。 */
    fun myVote(id: String): List<String>? =
        prefs.getString(KEY_VOTE_PREFIX + id)?.split(VOTE_SEP)?.filter { it.isNotEmpty() }

    /** 记下投票并标为已答（首页不再提示）。 */
    fun recordVote(id: String, selected: List<String>) {
        prefs.putString(KEY_VOTE_PREFIX + id, selected.joinToString(VOTE_SEP))
        ack(id)
    }

    companion object {
        private const val PREFS = "bulletin_store"
        private const val KEY_CACHE = "cached_json"
        private const val KEY_DISMISSED = "dismissed_ids"
        private const val KEY_ACKED = "acked_ids"
        private const val KEY_VOTE_PREFIX = "vote_"
        private const val VOTE_SEP = "\u001F"

        private val snoozedIds: MutableSet<String> = mutableSetOf()

        fun sessionSnoozedIds(): Set<String> = snoozedIds.toSet()
    }
}
