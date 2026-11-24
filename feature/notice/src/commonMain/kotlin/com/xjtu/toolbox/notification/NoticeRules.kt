package com.xjtu.toolbox.notification

import com.xjtu.toolbox.platform.KeyValueStore

/**
 * 一个来源的通知规则，按标题过滤，列表和站内搜索都生效。
 * [only] 非空时标题须含其中任一词；含 [block] 任一词的一律不要。词用逗号、顿号或空格隔开，存原文方便回填编辑。
 */
internal data class NoticeRule(val only: String = "", val block: String = "") {
    val onlyWords: List<String> get() = noticeRuleWords(only)
    val blockWords: List<String> get() = noticeRuleWords(block)
    val isEmpty: Boolean get() = onlyWords.isEmpty() && blockWords.isEmpty()

    fun accepts(title: String): Boolean =
        blockWords.none { title.contains(it, ignoreCase = true) } &&
            onlyWords.let { words -> words.isEmpty() || words.any { title.contains(it, ignoreCase = true) } }
}

internal fun noticeRuleWords(raw: String): List<String> =
    raw.split(Regex("""[,，、;；\s]+""")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** 规则和来源选择存在同一个按账号分开的偏好里，每个来源两个键。 */
internal class NoticeRuleStore(private val preferences: KeyValueStore) {
    fun load(): Map<NotificationSource, NoticeRule> = NotificationSource.entries.mapNotNull { source ->
        NoticeRule(
            preferences.getString(onlyKey(source)).orEmpty(),
            preferences.getString(blockKey(source)).orEmpty(),
        ).takeUnless { it.isEmpty }?.let { source to it }
    }.toMap()

    fun save(rules: Map<NotificationSource, NoticeRule>) {
        NotificationSource.entries.forEach { source ->
            val rule = rules[source]?.takeUnless { it.isEmpty }
            preferences.putString(onlyKey(source), rule?.only?.trim())
            preferences.putString(blockKey(source), rule?.block?.trim())
        }
    }

    private fun onlyKey(source: NotificationSource) = "rule_only_${source.name}"
    private fun blockKey(source: NotificationSource) = "rule_block_${source.name}"
}
