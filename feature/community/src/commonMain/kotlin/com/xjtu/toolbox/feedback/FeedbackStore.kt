package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.platform.AnonymousId
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.secureRandomInt
import com.xjtu.toolbox.util.AppJson
import kotlin.time.Clock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 反馈的本地账本。 */
object FeedbackStore {
    private const val PREFS = "feedback"
    private const val K_TICKETS = "tickets"
    private const val K_READ = "read_replies"
    private const val K_PROMPT_AT = "prompt_last_at"
    private const val K_PROMPT_DONE = "prompt_done_version"

    /** `by lazy`：Android 的 `keyValueStore` 要等 `initAndroidPlatform` 交进 `Context`。 */
    private val store by lazy { keyValueStore(PREFS) }

    /** 匿名标识，用来把同一个人的多条反馈串起来，不含学号。 */
    fun anonId(): String = AnonymousId.get()

    /** 工单号：10 位大写十六进制。 */
    fun newTicket(): String = "T" + randomHex(10).uppercase()

    fun tickets(): List<Ticket> {
        val raw = store.getString(K_TICKETS) ?: return emptyList()
        val arr = runCatching { AppJson.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Ticket(
                id = o.text("id"),
                category = o.text("category"),
                content = o.text("content"),
                createdAt = o.text("at").toLongOrNull() ?: 0L,
            )
        }.sortedByDescending { it.createdAt }
    }

    fun addTicket(ticket: Ticket) {
        // 只留最近 20 条：本地账本是给用户查回复用的，不是归档
        val next = (listOf(ticket) + tickets()).take(20)
        val arr = buildJsonArray {
            next.forEach {
                add(
                    buildJsonObject {
                        put("id", it.id)
                        put("category", it.category)
                        put("content", it.content)
                        put("at", it.createdAt)
                    },
                )
            }
        }
        store.putString(K_TICKETS, arr.toString())
    }

    /** 已读过的回复，用来算"有新回复"的红点。 */
    fun readTickets(): Set<String> = store.getStringSet(K_READ).orEmpty()

    fun markRead(ids: Collection<String>) {
        store.putStringSet(K_READ, readTickets() + ids)
    }

    // ---- 拉取回复的节流 ----

    /** 距上次拉回复是否已经够久。 */
    fun shouldFetchReplies(hasPending: Boolean, force: Boolean = false): Boolean {
        if (force) return true
        val last = store.getString(K_REPLY_FETCH_AT)?.toLongOrNull() ?: 0L
        val gap = if (hasPending) REPLY_FETCH_GAP_PENDING_MS else REPLY_FETCH_GAP_MS
        return now() - last >= gap
    }

    fun markRepliesFetched() {
        store.putString(K_REPLY_FETCH_AT, now().toString())
    }

    /** 上次拉到的回复，节流命中时直接用它渲染，避免页面空着。 */
    fun cachedReplies(): Map<String, Pair<String, String>> {
        val raw = store.getString(K_REPLY_CACHE) ?: return emptyMap()
        val obj = runCatching { AppJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
        return buildMap {
            obj.keys.forEach { k ->
                val o = obj[k] as? JsonObject ?: return@forEach
                put(k, o.text("text") to o.text("time"))
            }
        }
    }

    fun cacheReplies(replies: Map<String, Pair<String, String>>) {
        val obj = buildJsonObject {
            replies.forEach { (k, v) ->
                put(k, buildJsonObject { put("text", v.first); put("time", v.second) })
            }
        }
        store.putString(K_REPLY_CACHE, obj.toString())
    }

    fun shouldFetchPublicQa(): Boolean {
        val last = store.getString(K_PUBLIC_QA_AT)?.toLongOrNull() ?: 0L
        return last == 0L || now() - last >= REPLY_FETCH_GAP_MS
    }

    fun cachedPublicQa(): List<FeedbackPublicQa> {
        val raw = store.getString(K_PUBLIC_QA) ?: return emptyList()
        val arr = runCatching { AppJson.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val q = o.text("q")
            val a = o.text("a")
            if (q.isBlank() || a.isBlank()) null else FeedbackPublicQa(q, a, o.text("c"))
        }
    }

    fun cachePublicQa(items: List<FeedbackPublicQa>) {
        val arr = buildJsonArray {
            items.forEach {
                add(buildJsonObject { put("q", it.question); put("a", it.answer); put("c", it.category) })
            }
        }
        store.putString(K_PUBLIC_QA, arr.toString())
        store.putString(K_PUBLIC_QA_AT, now().toString())
    }

    private const val K_REPLY_FETCH_AT = "reply_fetch_at"
    private const val K_REPLY_CACHE = "reply_cache"
    private const val K_PUBLIC_QA = "public_qa"
    private const val K_PUBLIC_QA_AT = "public_qa_at"
    private const val REPLY_FETCH_GAP_MS = 6L * 60 * 60 * 1000
    private const val REPLY_FETCH_GAP_PENDING_MS = 10L * 60 * 1000

    // ---- 定点弹问卷的节流 ----

    /** 是否该弹一次轻量评价。 */
    fun shouldPrompt(versionCode: Int, usageCount: Int, isConfigured: Boolean): Boolean {
        if (!isConfigured) return false
        if (usageCount < MIN_USAGE) return false
        if (store.getString(K_PROMPT_DONE)?.toIntOrNull() == versionCode) return false
        val last = store.getString(K_PROMPT_AT)?.toLongOrNull() ?: 0L
        return now() - last >= MIN_GAP_MS
    }

    /** 弹过就记账，无论用户是填了还是划走——划走也是一种回答。 */
    fun markPrompted(versionCode: Int) {
        store.putString(K_PROMPT_AT, now().toString())
        store.putString(K_PROMPT_DONE, versionCode.toString())
    }

    private const val MIN_USAGE = 8
    private const val MIN_GAP_MS = 14L * 24 * 60 * 60 * 1000

    data class Ticket(
        val id: String,
        val category: String,
        val content: String,
        val createdAt: Long,
    )

    /** 键值缝只有字符串一档，`Long`/`Int` 存成字符串。 */
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    /** 与本机时间无关的随机串（[secureRandomInt] 是三端都有的那条缝）。 */
    private fun randomHex(length: Int): String =
        buildString(length) {
            repeat(length) { append(HEX[secureRandomInt(HEX.length)]) }
        }

    private const val HEX = "0123456789abcdef"

    /** 取字段：缺字段、JSON null 都当空串。 */
    private fun JsonObject.text(key: String): String {
        val v = this[key] ?: return ""
        if (v is JsonNull) return ""
        return (v as? JsonPrimitive)?.content.orEmpty()
    }
}
