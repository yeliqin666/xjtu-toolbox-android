package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.platform.KeyValueStore
import kotlin.time.Clock

/**
 * 键值缝上的 [ScheduleCacheStore] —— 给没有 `cacheDir` 的端用（桌面端）。
 *
 * `:app` 不用它：那一端是 `DataCache`（`cacheDir/data_cache<账号后缀>/<key>.json`，按文件最后
 * 修改时间判 TTL、先写 `.tmp` 再 rename），老用户的缓存逐字节都在那边。
 *
 * ## 口径
 *
 * 键名与值**逐字相同**（`schedule_<学期>` / `exams_<学期>` / …，值就是 `ScheduleCache` 写出来
 * 的那段 JSON）⇒ 两端的缓存文件互换也能读。TTL 靠另存一个 `<key>#written_at` 的时间戳算
 * （键值缝没有「文件最后修改时间」这一档）。
 *
 * ## 如实降级
 *
 * - **没有原子写**：键值缝只有 `putString`，写一半被打断可能留下半段 JSON。后果被
 *   `ScheduleCache` 那几处 `runCatching { decode }` 兜住（解不出来 = 当作没有缓存，下次重拉），
 *   不会把半段 JSON 当成数据用；但那份文件里会短暂留一条坏值。`DataCache` 那边靠
 *   `.tmp` + rename 避免了这件事 —— 等到键值缝长出「原子写」再补，现在不假装有。
 * - **`ageMsOfJson` 对时钟回拨是安全的**：负数夹到 0（当成刚写过），只会多留一会儿缓存。
 */
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
