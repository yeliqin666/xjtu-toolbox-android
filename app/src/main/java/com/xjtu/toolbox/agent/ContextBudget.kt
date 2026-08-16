package com.xjtu.toolbox.agent

/**
 * Agent 本地缓存的「上下文预算」——按数据源分别限长，避免单源膨胀撑爆 prompt。
 *
 * 单位是「字符数」（中文 1 字 ≈ 2 字节）；超限时保留头尾、中间省略提示。
 */
object ContextBudget {

    const val DEFAULT_CAP = 4_000       // 4 KB 字符 ≈ 1300 中文字 ≈ 600 token
    const val HARD_CAP = 16_000         // 硬上限，超过即截断

    /**
     * 数据源 → 单数据项字符预算。
     */
    val DEFAULT_PER_SOURCE: Map<String, Int> = mapOf(
        "grades" to 4_000,
        "schedule" to 6_000,
        "attendance" to 3_000,
        "notifications" to 6_000,
        "card_balance" to 500,
        "exam" to 4_000,
        "emptyroom" to 3_000,
    )

    /**
     * 把原文截断到 [budget] 字符以内：保留前部 + 末尾摘要。
     */
    fun clip(text: String, budget: Int = DEFAULT_CAP): String {
        if (text.length <= budget) return text
        val head = budget * 2 / 3
        val tail = budget - head - 80   // 80 留给省略号说明
        val omitted = text.length - head - tail
        return text.take(head) +
            "\n\n[…省略 $omitted 字符，剩余内容请缩小查询范围或分多次询问。]\n\n" +
            text.takeLast(tail)
    }

    /**
     * 取一个数据源的预算（未在表里的用 [DEFAULT_CAP]）。
     */
    fun budgetFor(source: String): Int = DEFAULT_PER_SOURCE[source] ?: DEFAULT_CAP

    /**
     * 数据源文本的标准包装：缓存为空时显式声明，避免模型瞎补。
     *
     * 用法：
     * ```
     * val grades = scoreCache.getOrNull()
     * val payload = ContextBudget.payload("grades", grades?.let { buildGradesText(it) })
     * agent.submit(payload)
     * ```
     *
     * @param source 数据源名（见 [DEFAULT_PER_SOURCE]）
     * @param content 数据内容（null = 缓存为空/缺失）
     * @param extra 附加说明（如"已过期 2 天"）
     */
    fun payload(source: String, content: String?, extra: String? = null): String {
        val budget = budgetFor(source)
        if (content == null) {
            return buildString {
                append("【$source 缓存不可用】")
                if (extra != null) append("（$extra）")
                appendLine()
                append("本次问答将不包含此数据。请告诉用户该数据为空、缺失或损坏。")
            }
        }
        val clipped = clip(content, budget)
        return buildString {
            append("【$source】")
            if (extra != null) append("（$extra）")
            appendLine()
            append(clipped)
        }
    }
}
