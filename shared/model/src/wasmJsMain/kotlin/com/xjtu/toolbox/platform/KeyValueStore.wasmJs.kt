package com.xjtu.toolbox.platform

import kotlinx.browser.localStorage

/** Web：`localStorage`。 */
const val PREFIX = "xjtu-toolbox."

private class WebKeyValueStore(private val name: String) : KeyValueStore {
    private fun k(key: String) = "$PREFIX$name.$key"

    override fun getString(key: String): String? = localStorage.getItem(k(key))
    override fun putString(key: String, value: String?) {
        if (value == null) localStorage.removeItem(k(key)) else localStorage.setItem(k(key), value)
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        when (localStorage.getItem(k(key))) {
            "true" -> true
            "false" -> false
            else -> default
        }

    override fun putBoolean(key: String, value: Boolean) {
        localStorage.setItem(k(key), value.toString())
    }

    override fun getInt(key: String, default: Int): Int = localStorage.getItem(k(key))?.toIntOrNull() ?: default
    override fun putInt(key: String, value: Int) {
        localStorage.setItem(k(key), value.toString())
    }

    /** 集合那一档：`localStorage` 没有集合类型，存的是逗号分隔的字符串。 */
    override fun getStringSet(key: String): Set<String>? =
        localStorage.getItem(k(key))
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()

    override fun putStringSet(key: String, value: Set<String>) {
        localStorage.setItem(k(key), value.joinToString(","))
    }

    override fun remove(key: String) {
        localStorage.removeItem(k(key))
    }

    override fun contains(key: String): Boolean = localStorage.getItem(k(key)) != null

    /** 与本文件里 `clear()` 同一套前缀扫法，去掉前缀交回去。 */
    override fun keys(): Set<String> {
        val prefix = "$PREFIX$name."
        val out = mutableSetOf<String>()
        for (i in 0 until localStorage.length) {
            localStorage.key(i)?.takeIf { it.startsWith(prefix) }?.let { out += it.removePrefix(prefix) }
        }
        return out
    }

    override fun clear() {
        // localStorage 没有「按前缀清空」的 API，只能自己扫。键不多，代价可接受。
        val doomed = mutableListOf<String>()
        for (i in 0 until localStorage.length) {
            localStorage.key(i)?.takeIf { it.startsWith("$PREFIX$name.") }?.let { doomed += it }
        }
        doomed.forEach { localStorage.removeItem(it) }
    }
}

actual fun keyValueStore(name: String): KeyValueStore = WebKeyValueStore(name)
