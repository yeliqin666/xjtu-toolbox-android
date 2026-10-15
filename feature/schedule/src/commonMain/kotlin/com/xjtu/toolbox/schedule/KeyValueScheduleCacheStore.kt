package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.platform.KeyValueStore
import kotlin.time.Clock

/** 键值缝上的 [ScheduleCacheStore] —— 给没有 `cacheDir` 的端用（桌面端）。 */
class KeyValueScheduleCacheStore(
    private val store: KeyValueStore,
    /** 取当前毫秒时间戳。可注入，便于测试 TTL。 */
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ScheduleCacheStore {

    override fun readJson(key: String, ttlMs: Long): String? {
        val age = ageMsOfJson(key) ?: return null
        if (ttlMs != NEVER && age > ttlMs) {
            dropJson(key)
            return null
        }
        return store.getString(key)
    }

    override fun writeJson(key: String, json: String) {
        store.putString(key, json)
        store.putString(ageKeyOf(key), nowMs().toString())
    }

    override fun readJsonStale(key: String): String? = store.getString(key)

    override fun dropJson(key: String) {
        store.remove(key)
        store.remove(ageKeyOf(key))
    }

    override fun ageMsOfJson(key: String): Long? =
        store.getString(ageKeyOf(key))?.toLongOrNull()?.let { (nowMs() - it).coerceAtLeast(0L) }

    private fun ageKeyOf(key: String) = "$key$AGE_SUFFIX"

    private companion object {
        /** 与 `ScheduleCache` 的 `FOREVER` 同值：`ttlMs == Long.MAX_VALUE` 表示永不过期。 */
        const val NEVER = Long.MAX_VALUE

        /** 时间戳键的后缀。`#` 不会出现在课表族任何键名里（都是 `schedule_*` / `exams_*` 这类）。 */
        const val AGE_SUFFIX = "#written_at"
    }
}
