package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore
import kotlinx.serialization.json.JsonObject

/** 屁岱记住的用户偏好。 */
class AgentMemory(
    /** 当前账号命名空间的偏好存储，**每次调用重新解析**（切账号即切文件）。 */
    private val storeOf: () -> KeyValueStore = { keyValueStore(STORE + AccountContext.safeSuffix()) },
    /** 不分账号的旧版存储，只用于一次性迁移。 */
    private val legacyStoreOf: () -> KeyValueStore = { keyValueStore(STORE) },
) {

    /** 按账号分开存，和屁岱的配置、会话一致：偏好会写进系统提示，发给该账号配置的服务商。 */
    private fun prefs(): KeyValueStore {
        val p = storeOf()
        if (AccountContext.activeAccountId != null) {
            val legacy = legacyStoreOf()
            val legacyKeys = order(legacy)
            if (legacyKeys.isNotEmpty()) {
                if (order(p).isEmpty()) {
                    legacyKeys.forEach { k -> legacy.getString(k)?.let { p.putString(k, it) } }
                    p.putString(KEY_ORDER, legacyKeys.joinToString(SEP))
                }
                legacyKeys.forEach { legacy.remove(it) }
                legacy.remove(KEY_ORDER)
            }
        }
        return p
    }

    private fun order(store: KeyValueStore): List<String> =
        store.getString(KEY_ORDER).orEmpty().split(SEP).filter { it.isNotBlank() }

    /** 返回 (key, value) 列表，最近写入的在后。 */
    fun all(): List<Pair<String, String>> {
        val p = prefs()
        return order(p).mapNotNull { k -> p.getString(k)?.let { k to it } }
    }

    fun remember(key: String, value: String): String {
        val k = key.trim().take(40)
        val v = value.trim().take(120)
        if (k.isEmpty() || v.isEmpty()) return "键和内容都不能为空。"
        val p = prefs()
        // 重写同一个键时把它挪到队尾，让"最近提过的"最后被淘汰。
        val next = (order(p) - k) + k
        val trimmed = if (next.size > MAX_ITEMS) next.takeLast(MAX_ITEMS) else next
        val dropped = next - trimmed.toSet()
        p.putString(k, v)
        dropped.forEach { p.remove(it) }
        p.putString(KEY_ORDER, trimmed.joinToString(SEP))
        return if (dropped.isEmpty()) {
            "记住了：$k = $v"
        } else {
            "记住了：$k = $v（偏好上限 $MAX_ITEMS 条，已挤掉最旧的「${dropped.joinToString("、")}」）"
        }
    }

    fun forget(key: String): String {
        val k = key.trim()
        val p = prefs()
        if (p.getString(k) == null) return "没有记过「$k」。"
        p.remove(k)
        p.putString(KEY_ORDER, (order(p) - k).joinToString(SEP))
        return "忘掉了「$k」。"
    }

    fun clear() {
        prefs().clear()
    }

    /**
     * `preference` 工具的入口（各端共用）：action=list 列出；不给 value 删除；否则写入。
     * 模型改偏好时常换个说法（「所在校区」改「校区」），所以写入前把旧键（old_key 或与 key 互相包含的那条）
     * 一并收掉，避免改不动、反而多出一条；每次都回带当前清单，让模型在本轮对话里就看得到改后的样子。
     */
    fun handleTool(args: JsonObject): String {
        val key = args.argStr("key").orEmpty().trim()
        val value = args.argStr("value").orEmpty()
        val reply = when {
            args.argStr("action")?.trim() == "list" -> return listText()
            value.isBlank() -> forget(resolve(key) ?: key)
            else -> {
                val k = key.take(40)
                val replaced = listOfNotNull(args.argStr("old_key")?.let(::resolve), resolve(k)).distinct() - k
                replaced.forEach { forget(it) }
                val written = remember(k, value)
                if (replaced.isEmpty()) written else "$written（替换了「${replaced.joinToString("、")}」）"
            }
        }
        return "$reply\n${listText()}"
    }

    private fun listText(): String {
        val items = all()
        return if (items.isEmpty()) "当前没有记住的偏好。"
        else "当前偏好：" + items.joinToString("；") { (k, v) -> "$k = $v" }
    }

    /** 找已存的同一条：先精确匹配，否则取唯一一条与之互相包含的键（两字以上才算）。 */
    private fun resolve(key: String): String? {
        val k = key.trim()
        if (k.isEmpty()) return null
        val keys = all().map { it.first }
        if (k in keys) return k
        if (k.length < 2) return null
        return keys.filter { it.length >= 2 && (it in k || k in it) }.singleOrNull()
    }

    /** 拼进系统提示的那一段。没有偏好时返回空串，不留空标题。 */
    fun promptBlock(): String {
        val items = all()
        if (items.isEmpty()) return ""
        return buildString {
            append("\n# 用户让你记住的\n")
            items.forEach { (k, v) -> append("- $k：$v\n") }
            append("和上面的用户画像冲突时（校区、学院、专业、年级、书院等）以这里为准：学校系统常常没更新（校区搬迁、转专业）。")
            append("选通知来源、找教室食堂、排日程都按这里的说法来。\n")
        }.trimEnd()
    }

    companion object {
        /** 存储名，不能改。 */
        const val STORE = "agent_memory"
        const val MAX_ITEMS = 20

        private const val KEY_ORDER = "__order"

        /** 键序的分隔符 `\u0001`，不能改（老数据在用）。 */
        private const val SEP = "\u0001"
    }
}
