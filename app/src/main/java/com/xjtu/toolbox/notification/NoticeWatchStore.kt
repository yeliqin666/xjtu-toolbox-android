package com.xjtu.toolbox.notification

import android.content.Context

/**
 * 教务通知「盯梢」偏好：开关、来源、已见链接。
 *
 * 系统通知栏和桌面小组件共用这一份，改来源两边一起变。
 * 已见链接不进备份——重装后第一次抓取当基线，避免把旧公告当新的推一遍。
 */
internal object NoticeWatchStore {
    private const val PREFS = "notice_watch"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SOURCES = "sources"
    private const val KEY_SOURCES_SET = "sources_configured"
    private const val KEY_SEEN = "seen_links"
    private const val KEY_BASELINED = "baselined_sources"
    private const val KEY_LAST_SYNC = "last_sync_at"
    private const val KEY_LAST_TITLES = "last_titles"
    private const val KEY_KEYWORDS = "push_keywords"

    const val DEFAULT_ENABLED = true
    val DEFAULT_SOURCES: Set<NotificationSource> = setOf(NotificationSource.JWC)

    /** 已见链接上限。超出从最旧的丢掉，避免 prefs 无限涨。 */
    private const val MAX_SEEN = 300

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun sources(context: Context): Set<NotificationSource> {
        val p = prefs(context)
        if (!p.getBoolean(KEY_SOURCES_SET, false)) return DEFAULT_SOURCES
        val raw = p.getStringSet(KEY_SOURCES, emptySet()) ?: emptySet()
        return raw.mapNotNull { name ->
            runCatching { NotificationSource.valueOf(name) }.getOrNull()
        }.toSet()
    }

    fun setSources(context: Context, sources: Set<NotificationSource>) {
        prefs(context).edit()
            .putBoolean(KEY_SOURCES_SET, true)
            .putStringSet(KEY_SOURCES, sources.map { it.name }.toSet())
            .apply()
    }

    fun sourceSummary(context: Context): String {
        val selected = sources(context)
        if (selected.isEmpty()) return "未选择来源"
        val names = selected.map { it.displayName }
        return when {
            names.size == 1 -> names.first()
            names.size <= 3 -> names.joinToString("、")
            else -> names.take(2).joinToString("、") + " 等 ${names.size} 个"
        }
    }

    fun seenLinks(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SEEN, "") ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun setSeenLinks(context: Context, links: List<String>) {
        val trimmed = links.filter { it.isNotBlank() }.distinct().takeLast(MAX_SEEN)
        prefs(context).edit().putString(KEY_SEEN, trimmed.joinToString("\n")).apply()
    }

    fun baselinedSources(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_BASELINED, emptySet())?.toSet() ?: emptySet()

    fun setBaselinedSources(context: Context, names: Set<String>) {
        prefs(context).edit().putStringSet(KEY_BASELINED, names).apply()
    }

    /**
     * 推送关键词，逗号 / 空格 / 顿号分隔。留空 = 不过滤，全推。
     *
     * 只过滤**推送**，不过滤列表和小组件：抓取一次照旧，抓回来的东西也照旧全都记进
     * seenLinks。所以它不增加任何请求，只减少推送条数——不匹配的公告仍然算"已见过"，
     * 不会攒着等某天关掉过滤时一次性糊你一脸。
     */
    fun keywords(context: Context): List<String> =
        prefs(context).getString(KEY_KEYWORDS, "").orEmpty()
            .split(Regex("""[,，、\s]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun keywordsRaw(context: Context): String =
        prefs(context).getString(KEY_KEYWORDS, "").orEmpty()

    fun setKeywords(context: Context, raw: String) {
        prefs(context).edit().putString(KEY_KEYWORDS, raw).apply()
    }

    fun lastSyncAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC, 0L)

    fun setLastSyncAt(context: Context, time: Long) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, time).apply()
    }

    fun lastTitles(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_LAST_TITLES, "") ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun setLastTitles(context: Context, titles: List<String>) {
        prefs(context).edit().putString(KEY_LAST_TITLES, titles.joinToString("\n")).apply()
    }
}
