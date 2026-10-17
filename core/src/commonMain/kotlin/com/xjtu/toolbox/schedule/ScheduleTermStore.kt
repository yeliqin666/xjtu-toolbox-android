package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.util.AppJson
/** 学期可读名：与日程页同一来源。 */
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

    private fun ScheduleCacheStore.readNames(): Map<String, String>? =
        readJson(CACHE_KEY, Long.MAX_VALUE)
            ?.let { runCatching { AppJson.decodeFromString<Map<String, String>>(it) }.getOrNull() }

    private fun ScheduleCacheStore.writeNames(names: Map<String, String>) {
        writeJson(CACHE_KEY, AppJson.encodeToString(names))
    }
}
