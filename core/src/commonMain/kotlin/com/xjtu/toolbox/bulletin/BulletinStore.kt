package com.xjtu.toolbox.bulletin

import com.xjtu.toolbox.platform.keyValueStore
/**
 * 公告本地状态：上次成功的 JSON 缓存、已关闭 / 已确认的 id。
 * force_update 的「稍后」只记在进程内，下次冷启动再出现。
 *
 * ## 搬进 `:core` 时只换了一件事：存储
 *
 * `Context` + `SharedPreferences` → 平台键值缝 [keyValueStore]。**store 名与三个键名一字未改**
 * （`bulletin_store` / `cached_json` / `dismissed_ids` / `acked_ids`）：Android 那份实现
 * （`KeyValueStore.android.kt`）就是 `getSharedPreferences(name)`，所以已有的缓存、用户关掉的
 * 公告、确认过的投票**一个不丢**。此处与 `empty_room` / `inbox` / `venue_favorites` 那几个
 * 搬过来的本地状态同一条规矩。
 *
 * 于是这个类不再需要 `Context`：`:app` 那边 `BulletinStore(context)` 的那一行变成 `BulletinStore()`，
 * 其余（拉取失败读缓存、冷启动弹一条、关掉算已读）都归宿主，**本批没有动它**。
 */
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
        // store 名与 :app 时代那个 SharedPreferences 文件名逐字相同（含三个键）
        private const val PREFS = "bulletin_store"
        private const val KEY_CACHE = "cached_json"
        private const val KEY_DISMISSED = "dismissed_ids"
        private const val KEY_ACKED = "acked_ids"

        private val snoozedIds: MutableSet<String> = mutableSetOf()

        fun sessionSnoozedIds(): Set<String> = snoozedIds.toSet()
    }
}
