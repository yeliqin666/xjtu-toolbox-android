package com.xjtu.toolbox.schedule

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.xjtu.toolbox.util.DataCache
import com.xjtu.toolbox.util.XjtuTime

/**
 * 学期可读名：与日程页同一来源。
 * 优先教务学期列表/当前学期接口里的 `MC`，没有才用代码末位译名。
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

    fun read(dataCache: DataCache, gson: Gson): Map<String, String> {
        val json = dataCache.get(CACHE_KEY, Long.MAX_VALUE) ?: return emptyMap()
        return runCatching {
            val obj = gson.fromJson(json, JsonObject::class.java) ?: return emptyMap()
            obj.entrySet().mapNotNull { e ->
                val name = usableName(e.key, e.value?.asString)
                if (name == null) null else e.key to name
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun merge(dataCache: DataCache, gson: Gson, names: Map<String, String>) {
        val incoming = names.mapNotNull { (code, raw) ->
            usableName(code, raw)?.let { code to it }
        }
        if (incoming.isEmpty()) return
        val merged = read(dataCache, gson).toMutableMap()
        merged.putAll(incoming)
        dataCache.put(CACHE_KEY, gson.toJson(merged))
    }

    fun display(code: String, dataCache: DataCache, gson: Gson, api: ScheduleApi?): String =
        display(code, api?.termNames().orEmpty(), read(dataCache, gson))
}
