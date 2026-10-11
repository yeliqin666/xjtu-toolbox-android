package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.platform.deviceInstallId
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

/**
 * 反馈的本地账本。存在键值存储（"feedback"），不进 cacheDir，
 * "清除缓存"不该把用户自己提过的工单清掉——那样他就再也找不回回复了。
 *
 * ## 它为什么在 `:core`（搬进共享内核时改了什么）
 *
 * 搬迁前它在 `:app`，每个方法都要一个 `android.content.Context`（只为开 `SharedPreferences`）。
 * 现在走 `:core` 的平台键值缝 [keyValueStore]：**Android 侧的 actual 就是同一个文件名的
 * `SharedPreferences`**（`feedback`，键名 `anon_id` / `tickets` / `read_replies` /
 * `prompt_last_at` / `prompt_done_version` / `reply_fetch_at` / `reply_cache` /
 * `public_qa` / `public_qa_at` 逐个相同）⇒ 老工单、已读标记、"问过一次"的记账一个不丢，
 * 不需要迁移代码。
 *
 * 两处非 JVM 写法的替代（语义等价，都在注释里点名）：
 *  - `System.currentTimeMillis()` → `Clock.System.now().toEpochMilliseconds()`（同一时刻、
 *    同一 epoch 毫秒，与 `DormPowerStore` / `InboxStore` 等既有先例同一句写法）；
 *  - `Section.Secure.ANDROID_ID` 那一段（Android 专属）→ 平台缝
 *    [deviceInstallId]（Android actual 与搬迁前逐字同构）；桌面/Web 该缝给 null ⇒
 *    退回本机随机 ID，与搬迁前"读不到设备标识"那条兜底路径**是同一段代码**。
 */
object FeedbackStore {
    private const val PREFS = "feedback"
    private const val K_ANON_ID = "anon_id"
    private const val K_TICKETS = "tickets"
    private const val K_READ = "read_replies"
    private const val K_PROMPT_AT = "prompt_last_at"
    private const val K_PROMPT_DONE = "prompt_done_version"

    /**
     * `by lazy` 而不是直接初始化：Android 侧 `keyValueStore` 要 `Application.onCreate` 里那次
     * `initAndroidPlatform` 先把 `Context` 交进来，而这里可能在任何时刻被触碰。
     */
    private val store by lazy { keyValueStore(PREFS) }

    /**
     * 匿名标识，用来把同一个人的多条反馈串起来，不含学号。
     *
     * 用 [deviceInstallId]（Android = `Settings.Secure.ANDROID_ID` 哈希，不用 IMEI / 序列号）。
     * Android 8 起那个值按「应用签名 + 用户 + 设备」隔离：
     * 更新、清数据、卸载再装（同一签名）都不变；换签名、刷机、恢复出厂会变。
     * 读不到时才退回本地随机 ID。
     */
    fun anonId(): String {
        val fromDevice = deviceInstallId("feedback")
        if (fromDevice != null) {
            if (store.getString(K_ANON_ID) != fromDevice) {
                store.putString(K_ANON_ID, fromDevice)
            }
            return fromDevice
        }
        store.getString(K_ANON_ID)?.let { return it }
        val id = randomHex(8)
        store.putString(K_ANON_ID, id)
        return id
    }

    /**
     * 工单号：10 位大写十六进制。
     *
     * 搬迁前是 `UUID.randomUUID().toString().replace("-", "").take(10).uppercase()` ——
     * 去掉横线之后前 10 位全是十六进制数字，所以这里直接取 10 位随机十六进制，**同一个字母表、
     * 同一个长度**（`TICKET_RE` 认的就是 `T[A-Z0-9]{8,16}`，一条都不影响）。
     */
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

    /**
     * 距上次拉回复是否已经够久。飞书基础免费版是 1 万次/月的接口总量配额，
     * 用户反复进出反馈页不该每次都打一发请求。[force] 供手动刷新绕过。
     *
     * 间隔分两档，取决于手上还有没有没被回复的工单（[hasPending]）：
     * 还在等回复的人隔十几分钟就该能看到新回复，等一个 6 小时的窗口
     * 等于"提交完当天别想看到回应"；而全都已回复的人再查纯属浪费配额。
     * 别把这里改回单一间隔——第一版就是这么写的，结果用户永远刷不出刚写的回复。
     */
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

    /**
     * 是否该弹一次轻量评价。三重闸门，避免变成骚扰：
     * 每个版本最多问一次、两次间隔至少 [MIN_GAP_MS]、且用户得真的用过一阵子。
     *
     * [isConfigured] 就是搬迁前那句 `FeedbackApi.isConfigured`（后端凭据没配全时整个反馈功能
     * 降级回外链，也就不该弹问卷）—— 从 `:core` 看不见 `:data` 的实现，所以由调用方（屏）
     * 从取数端口上读出来传进来，判据本身一字未改。
     */
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

    /** 键值缝只有 `getString` 一档 `Long`/`Int` 的写法（搬迁前 `.getLong` / `.getInt`）⇒ 逐字存回来。 */
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    /** 与本机时间无关的随机串（[secureRandomInt] 是三端都有的那条缝）。 */
    private fun randomHex(length: Int): String =
        buildString(length) {
            repeat(length) { append(HEX[secureRandomInt(HEX.length)]) }
        }

    private const val HEX = "0123456789abcdef"

    /** 取字段：缺字段、JSON null 都当空串（与搬迁前 `optString(key)` 同一条宽缺口径）。 */
    private fun JsonObject.text(key: String): String {
        val v = this[key] ?: return ""
        if (v is JsonNull) return ""
        return (v as? JsonPrimitive)?.content.orEmpty()
    }
}
