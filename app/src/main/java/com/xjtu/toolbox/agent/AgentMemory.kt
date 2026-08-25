package com.xjtu.toolbox.agent

import android.content.Context

/**
 * 屁岱记住的用户偏好。
 *
 * 纯本地 key-value，不上传任何地方，不参与账号同步。
 *
 * ## 边界
 *
 * 偏好只影响**怎么说**和**先查哪个**，绝不影响**查不查**。
 *
 * 举例：记住"我一般在兴庆校区"，于是查空教室时先看兴庆——这是合理的；
 * 但不能因为记住了"不关心体测"，就在用户明确问体测时不去查。
 * 前者是省事，后者是替用户做决定。这条界线写进了工具描述，也写进系统提示。
 *
 * 条数封顶 [MAX_ITEMS]：偏好会整段进系统提示，无限增长会挤占上下文，
 * 而且越长模型越容易记串。满了之后新的挤掉最旧的。
 */
object AgentMemory {

    private const val PREFS = "agent_memory"
    private const val KEY_ORDER = "__order"
    const val MAX_ITEMS = 20

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun order(ctx: Context): List<String> =
        prefs(ctx).getString(KEY_ORDER, "").orEmpty()
            .split('\u0001').filter { it.isNotBlank() }

    /** 返回 (key, value) 列表，最近写入的在后。 */
    fun all(ctx: Context): List<Pair<String, String>> {
        val p = prefs(ctx)
        return order(ctx).mapNotNull { k -> p.getString(k, null)?.let { k to it } }
    }

    fun remember(ctx: Context, key: String, value: String): String {
        val k = key.trim().take(40)
        val v = value.trim().take(120)
        if (k.isEmpty() || v.isEmpty()) return "键和内容都不能为空。"
        val p = prefs(ctx)
        // 重写同一个键时把它挪到队尾，让"最近提过的"最后被淘汰。
        val next = (order(ctx) - k) + k
        val trimmed = if (next.size > MAX_ITEMS) next.takeLast(MAX_ITEMS) else next
        val dropped = next - trimmed.toSet()
        p.edit().apply {
            putString(k, v)
            dropped.forEach { remove(it) }
            putString(KEY_ORDER, trimmed.joinToString("\u0001"))
        }.apply()
        return if (dropped.isEmpty()) {
            "记住了：$k = $v"
        } else {
            "记住了：$k = $v（偏好上限 $MAX_ITEMS 条，已挤掉最旧的「${dropped.joinToString("、")}」）"
        }
    }

    fun forget(ctx: Context, key: String): String {
        val k = key.trim()
        val p = prefs(ctx)
        if (p.getString(k, null) == null) return "没有记过「$k」。"
        p.edit().remove(k).putString(KEY_ORDER, (order(ctx) - k).joinToString("\u0001")).apply()
        return "忘掉了「$k」。"
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    /** 拼进系统提示的那一段。没有偏好时返回空串，不留空标题。 */
    fun promptBlock(ctx: Context): String {
        val items = all(ctx)
        if (items.isEmpty()) return ""
        return buildString {
            append("\n# 记住的偏好\n")
            items.forEach { (k, v) -> append("- $k：$v\n") }
            append("只用来决定表达方式和查询顺序。用户明确问的事照查不误，不许因为偏好跳过。\n")
        }.trimEnd()
    }
}
