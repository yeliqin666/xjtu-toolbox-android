package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.util.AppJson
/**
 * 学期可读名：与日程页同一来源。
 * 优先教务学期列表/当前学期接口里的 `MC`，没有才用代码末位译名。
 *
 * 从 `:app` 的 `schedule/ScheduleTermStore.kt` 搬进 `:data`（同包同名，`:app` 调用点一行未改）。
 * 两处只换了类型：存储参数从 `DataCache` 换成 `:core` 的 [ScheduleCacheStore] 缝
 * （`:app` 的 `DataCache` 直接实现它）、取数参数从 `ScheduleApi` 换成
 * [JwxtScheduleSource]（实现还是 `:data` 的 `JwxtScheduleApi`）。
 * 键名 `schedule_term_names` 与存进去的 `Map<String,String>` 逐字未动 ⇒ 老数据照旧读得出来。
 */
object ScheduleTermStore {
    const val CACHE_KEY = "schedule_term_names"

    fun usableName(code: String, raw: String?): String? {
        if (code.isBlank()) return null
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || name == code) return null
        return name
    }

    fun officialName(code: String, live: Map<String, String>, disk: Map<String, String>): String? {
        if (code.isBlank()) return null
        return usableName(code, live[code]) ?: usableName(code, disk[code])
    }

    fun display(code: String, live: Map<String, String>, disk: Map<String, String>): String {
        if (code.isBlank()) return ""
        return officialName(code, live, disk) ?: XjtuTime.displayTerm(code)
    }

    fun read(cache: ScheduleCacheStore): Map<String, String> =
        cache.readNames()?.mapNotNull { (code, raw) -> usableName(code, raw)?.let { code to it } }.orEmpty().toMap()

    fun merge(cache: ScheduleCacheStore, names: Map<String, String>) {
        val incoming = names.mapNotNull { (code, raw) -> usableName(code, raw)?.let { code to it } }
        if (incoming.isEmpty()) return
        cache.writeNames(read(cache) + incoming)
    }

    fun display(code: String, cache: ScheduleCacheStore, api: JwxtScheduleSource?): String =
        display(code, api?.termNames().orEmpty(), read(cache))

    // 与搬迁前 `DataCache.read/write` 同一个口径（同一个 `AppJson`、同一个 `Long.MAX_VALUE` TTL）。
    private fun ScheduleCacheStore.readNames(): Map<String, String>? =
        readJson(CACHE_KEY, Long.MAX_VALUE)
            ?.let { runCatching { AppJson.decodeFromString<Map<String, String>>(it) }.getOrNull() }

    private fun ScheduleCacheStore.writeNames(names: Map<String, String>) {
        writeJson(CACHE_KEY, AppJson.encodeToString(names))
    }
}
