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

    override fun remove(key: String) {
        localStorage.removeItem(k(key))
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
