package com.xjtu.toolbox.platform

import kotlinx.browser.localStorage

/**
 * Web：`localStorage`。
 *
 * 加 `xjtu-toolbox.` 前缀：同源下可能还有别的页面（campus-web 等），不加前缀会串键。
 *
 * 关于「Web 端要不要持久化」这件事的立场（照实说明，避免以后有人以为是漏了）：
 * 浏览器里**不存任何凭据** —— CAS 会话与各域会话都在 campus-api 里，页面只走同源反代。
 * 这里存的是纯偏好（主题、周次视图、上次选的学期之类），丢了不影响功能。
 */
private const val PREFIX = "xjtu-toolbox."

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

    /**
     * 集合那一档：`localStorage` 没有集合类型，存的是**逗号分隔的字符串**。
     *
     * 为什么偏偏是逗号串而不是 JSON 数组：图书馆收藏**已经**是这个格式了 —— 原来 Web 端的
     * `CampusLibraryApi` 就把它写成 `joinToString(",")` 存到同一个键（`library_favorites` /
     * `favorite_seats`）。改成新格式等于把浏览器里已有的收藏丢掉。所以这里沿用老格式，
     * 与 Android 那份 `getStringSet` 语义对齐（同样是「一组 id」），只是落地形态不同。
     */
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
