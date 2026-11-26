package com.xjtu.toolbox.plugin

import android.content.Context

/**
 * 插件页自己的偏好。还没接索引，先只存「索引地址」和「只在 Wi-Fi 下加载」两件事。
 *
 * 用独立的 SharedPreferences 而不是 App 的设置存储：插件是可选功能，删掉插件目录
 * 和这份偏好不该牵连到主设置。
 */
internal object PluginSettings {
    private const val FILE = "plugin_settings"
    private const val KEY_INDEX_URL = "index_url"
    private const val KEY_WIFI_ONLY = "wifi_only"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 默认市场索引。留空就用这个。 */
    const val DEFAULT_INDEX_URL: String = "https://dzhz.top/plugin-market.json"

    /** 生效的索引地址：没配过就用默认市场。 */
    fun indexUrl(context: Context): String =
        prefs(context).getString(KEY_INDEX_URL, "").orEmpty().ifBlank { DEFAULT_INDEX_URL }

    fun setIndexUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_INDEX_URL, url.trim()).apply()
    }

    /** 只在 Wi-Fi 下拉索引和图标，默认开。 */
    fun wifiOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_WIFI_ONLY, true)

    fun setWifiOnly(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, on).apply()
    }
}
