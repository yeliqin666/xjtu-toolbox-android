package com.xjtu.toolbox.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile

/** 「推送记录」会话常量，以及点通知后要定位、闪烁的那条推送（#194）。 */
object AgentPushFocus {
    const val SESSION_ID = "pidai_push"
    const val SESSION_TITLE = "推送记录"

    /** 通知 Intent 里带的那条推送的时间戳（Long）。 */
    const val EXTRA_PUSH_AT = "extra_agent_push_at"

    /** 待定位的推送时间戳；屁岱页切到会话成功后才取走。 */
    @Volatile
    var pending: Long? = null
        private set

    var generation by mutableIntStateOf(0)
        private set

    /** 后台每追加一条就加一，开着这个会话的屁岱页据此重读。 */
    var appended by mutableIntStateOf(0)
        private set

    /** 当前要闪的那条消息的时间戳；闪完由聊天列表置回 null。 */
    var highlight by mutableStateOf<Long?>(null)

    fun set(timestamp: Long) {
        pending = timestamp
        generation++
    }

    fun consume() {
        pending = null
    }

    fun notifyAppended() {
        appended++
    }
}

/**
 * 把 [disk] 上有、[mem] 里没有的推送（带时间戳的 assistant 消息）按时间戳并回 [mem]；
 * 对应的 llmHistory 条目补在末尾。没有可并的返回 [mem] 本身。
 */
internal fun mergeAppendedPushes(disk: StoredConversation, mem: StoredConversation): StoredConversation {
    val known = mem.messages.mapNotNullTo(HashSet()) { it.timestamp }
    val extra = disk.messages.filter { it.role == "assistant" && it.timestamp != null && it.timestamp !in known }
    if (extra.isEmpty()) return mem
    val messages = mem.messages.toMutableList()
    extra.forEach { push ->
        val at = messages.indexOfFirst { (it.timestamp ?: Long.MIN_VALUE) > push.timestamp!! }
        if (at < 0) messages += push else messages.add(at, push)
    }
    fun parse(s: String) = runCatching { AppJson.parseToJsonElement(s).jsonArray.toList() }.getOrDefault(emptyList())
    // llmHistory 条目不带时间戳：同内容的推送按时间戳排序后，第 k 条对应历史里该内容的第 k 次出现
    val extraAt = extra.mapTo(HashSet()) { it.timestamp!! }
    val pushesByContent = disk.messages.filter { it.role == "assistant" && it.timestamp != null }
        .sortedBy { it.timestamp }.groupBy({ it.content }, { it.timestamp!! })
    val seen = HashMap<String, Int>()
    val extraHistory = parse(disk.llmHistory).filter { el ->
        val obj = el as? JsonObject ?: return@filter false
        if (obj["role"]?.jsonPrimitive?.contentOrNull != "assistant") return@filter false
        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@filter false
        val k = seen[content] ?: 0
        seen[content] = k + 1
        pushesByContent[content]?.getOrNull(k) in extraAt
    }
    return mem.copy(messages = messages, llmHistory = JsonArray(parse(mem.llmHistory) + extraHistory).toString())
}
