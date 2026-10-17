package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore

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

    /** 拼进系统提示的那一段。没有偏好时返回空串，不留空标题。 */
    fun promptBlock(): String {
        val items = all()
        if (items.isEmpty()) return ""
        return buildString {
            append("\n# 用户偏好\n")
            items.forEach { (k, v) -> append("- $k：$v\n") }
            append("偏好只影响表达方式和查询顺序，不影响查不查。\n")
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
