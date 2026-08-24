package com.xjtu.toolbox.feedback

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 反馈的本地账本。存在 SharedPreferences（"feedback"），不进 cacheDir，
 * "清除缓存"不该把用户自己提过的工单清掉——那样他就再也找不回回复了。
 */
object FeedbackStore {
    private const val PREFS = "feedback"
    private const val K_ANON_ID = "anon_id"
    private const val K_TICKETS = "tickets"
    private const val K_READ = "read_replies"
    private const val K_PROMPT_AT = "prompt_last_at"
    private const val K_PROMPT_DONE = "prompt_done_version"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 匿名标识，用来把同一个人的多条反馈串起来，不含学号。
     *
     * 用 [Settings.Secure.ANDROID_ID] 哈希，不用 IMEI / 序列号。
     * Android 8 起这个值按「应用签名 + 用户 + 设备」隔离：
     * 更新、清数据、卸载再装（同一签名）都不变；换签名、刷机、恢复出厂会变。
     * 读不到时才退回本地随机 ID。
     */
    fun anonId(context: Context): String {
        val p = prefs(context)
        val fromDevice = androidIdHash(context)
        if (fromDevice != null) {
            if (p.getString(K_ANON_ID, null) != fromDevice) {
                p.edit().putString(K_ANON_ID, fromDevice).apply()
            }
            return fromDevice
        }
        p.getString(K_ANON_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().take(8)
        p.edit().putString(K_ANON_ID, id).apply()
        return id
    }

    /** 旧模拟器上 ANDROID_ID 会固定成这个值，当无效。 */
    private const val BUG_ANDROID_ID = "9774d56d682e549c"

    private fun androidIdHash(context: Context): String? {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim().orEmpty()
        if (androidId.isEmpty() || androidId.equals(BUG_ANDROID_ID, ignoreCase = true)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest("feedback|$androidId".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)
    }

    fun newTicket(): String = "T" + UUID.randomUUID().toString().replace("-", "").take(10).uppercase()

    fun tickets(context: Context): List<Ticket> {
        val raw = prefs(context).getString(K_TICKETS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Ticket(
                id = o.optString("id"),
                category = o.optString("category"),
                content = o.optString("content"),
                createdAt = o.optLong("at"),
            )
        }.sortedByDescending { it.createdAt }
    }

    fun addTicket(context: Context, ticket: Ticket) {
        // 只留最近 20 条：本地账本是给用户查回复用的，不是归档
        val next = (listOf(ticket) + tickets(context)).take(20)
        val arr = JSONArray()
        next.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("category", it.category)
                    .put("content", it.content)
                    .put("at", it.createdAt)
            )
        }
        prefs(context).edit().putString(K_TICKETS, arr.toString()).apply()
    }

    /** 已读过的回复，用来算"有新回复"的红点。 */
    fun readTickets(context: Context): Set<String> =
        prefs(context).getStringSet(K_READ, emptySet()).orEmpty()

    fun markRead(context: Context, ids: Collection<String>) {
        prefs(context).edit()
            .putStringSet(K_READ, readTickets(context) + ids)
            .apply()
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
    fun shouldFetchReplies(context: Context, hasPending: Boolean, force: Boolean = false): Boolean {
        if (force) return true
        val last = prefs(context).getLong(K_REPLY_FETCH_AT, 0L)
        val gap = if (hasPending) REPLY_FETCH_GAP_PENDING_MS else REPLY_FETCH_GAP_MS
        return System.currentTimeMillis() - last >= gap
    }

    fun markRepliesFetched(context: Context) {
        prefs(context).edit().putLong(K_REPLY_FETCH_AT, System.currentTimeMillis()).apply()
    }

    /** 上次拉到的回复，节流命中时直接用它渲染，避免页面空着。 */
    fun cachedReplies(context: Context): Map<String, Pair<String, String>> {
        val raw = prefs(context).getString(K_REPLY_CACHE, null) ?: return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            obj.keys().forEach { k ->
                val o = obj.optJSONObject(k) ?: return@forEach
                put(k, o.optString("text") to o.optString("time"))
            }
        }
    }

    fun cacheReplies(context: Context, replies: Map<String, Pair<String, String>>) {
        val obj = JSONObject()
        replies.forEach { (k, v) ->
            obj.put(k, JSONObject().put("text", v.first).put("time", v.second))
        }
        prefs(context).edit().putString(K_REPLY_CACHE, obj.toString()).apply()
    }

    fun shouldFetchPublicQa(context: Context): Boolean {
        val last = prefs(context).getLong(K_PUBLIC_QA_AT, 0L)
        return last == 0L || System.currentTimeMillis() - last >= REPLY_FETCH_GAP_MS
    }

    fun cachedPublicQa(context: Context): List<FeedbackApi.PublicQa> {
        val raw = prefs(context).getString(K_PUBLIC_QA, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val q = o.optString("q")
            val a = o.optString("a")
            if (q.isBlank() || a.isBlank()) null
            else FeedbackApi.PublicQa(q, a, o.optString("c"))
        }
    }

    fun cachePublicQa(context: Context, items: List<FeedbackApi.PublicQa>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("q", it.question).put("a", it.answer).put("c", it.category))
        }
        prefs(context).edit()
            .putString(K_PUBLIC_QA, arr.toString())
            .putLong(K_PUBLIC_QA_AT, System.currentTimeMillis())
            .apply()
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
     */
    fun shouldPrompt(context: Context, versionCode: Int, usageCount: Int): Boolean {
        if (!FeedbackApi.isConfigured) return false
        if (usageCount < MIN_USAGE) return false
        val p = prefs(context)
        if (p.getInt(K_PROMPT_DONE, -1) == versionCode) return false
        val last = p.getLong(K_PROMPT_AT, 0L)
        return System.currentTimeMillis() - last >= MIN_GAP_MS
    }

    /** 弹过就记账，无论用户是填了还是划走——划走也是一种回答。 */
    fun markPrompted(context: Context, versionCode: Int) {
        prefs(context).edit()
            .putLong(K_PROMPT_AT, System.currentTimeMillis())
            .putInt(K_PROMPT_DONE, versionCode)
            .apply()
    }

    private const val MIN_USAGE = 8
    private const val MIN_GAP_MS = 14L * 24 * 60 * 60 * 1000

    data class Ticket(
        val id: String,
        val category: String,
        val content: String,
        val createdAt: Long,
    )
}
