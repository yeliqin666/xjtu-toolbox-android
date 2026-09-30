package com.xjtu.toolbox.data

import kotlin.math.pow

/**
 * 常用站点：每次打开时，分数先按时间衰减（半衰期 [HALF_LIFE_MS]）再加 1，一个数同时反映「最近」和「常用」。
 * 编码成 `siteKey:分数:时刻` 逗号串存盘。
 */
internal object SiteUsage {
    const val HALF_LIFE_MS = 7 * 24 * 60 * 60 * 1000L
    private const val MAX_SITES = 12

    data class Entry(val score: Double, val at: Long)

    fun decode(raw: String?): Map<String, Entry> = raw.orEmpty().split(',').mapNotNull { item ->
        val parts = item.split(':')
        val score = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
        val at = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
        parts[0].takeIf { it.isNotBlank() }?.let { it to Entry(score, at) }
    }.toMap()

    fun encode(entries: Map<String, Entry>): String =
        entries.entries.joinToString(",") { (k, e) -> "$k:${"%.3f".format(java.util.Locale.ROOT, e.score)}:${e.at}" }

    fun scoreAt(entry: Entry, now: Long): Double =
        entry.score * 0.5.pow((now - entry.at).coerceAtLeast(0) / HALF_LIFE_MS.toDouble())

    fun record(entries: Map<String, Entry>, siteKey: String, now: Long): Map<String, Entry> {
        val next = entries + (siteKey to Entry((entries[siteKey]?.let { scoreAt(it, now) } ?: 0.0) + 1, now))
        return next.entries.sortedByDescending { scoreAt(it.value, now) }.take(MAX_SITES).associate { it.key to it.value }
    }

    fun top(entries: Map<String, Entry>, n: Int, now: Long): List<String> =
        entries.entries.sortedByDescending { scoreAt(it.value, now) }.take(n).map { it.key }
}
