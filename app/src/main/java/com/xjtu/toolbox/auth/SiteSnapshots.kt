package com.xjtu.toolbox.auth

import android.content.SharedPreferences
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.Executors

/**
 * 站点会话快照：每个站点登录成功（或探活通过）时的 [SiteSession.localToken]，按 backend 加密存盘，
 * 和 cookie 同命名空间、同生命周期。
 *
 * cookie 本来就落盘，但「哪些站点登着」和本地令牌只在内存，冷启动时每个站点都要重走一遍登录链。
 * 有了快照，冷启动恢复成「待确认」：有探活的站点第一次用时探一次，没有的直接用，失效由
 * [SiteSession.executeWithReAuth] 重登自愈。存了超过 [MAX_AGE_MS] 或令牌已过期的不恢复。
 *
 * 读写都排在一条专用线程上：顺序有保证，加密存储的打开和写盘也不会落到主线程。
 */
class SiteSnapshots(openPrefs: () -> SharedPreferences) {
    private val prefs by lazy(openPrefs)

    fun save(siteKey: String, tokens: Map<String, String>, now: Long = System.currentTimeMillis()) {
        val raw = encode(tokens, now)
        io.execute { runCatching { prefs.edit().putString(siteKey, raw).apply() } }
    }

    /** 没有、读不出、太旧或令牌已过期时返回 null。会阻塞到读完，只在后台线程调。 */
    fun load(siteKey: String, now: Long = System.currentTimeMillis()): Map<String, String>? =
        io.submit<String?> { prefs.getString(siteKey, null) }.get()?.let { decode(it, now) }

    fun remove(siteKey: String) {
        io.execute { runCatching { if (prefs.contains(siteKey)) prefs.edit().remove(siteKey).apply() } }
    }

    fun clear() {
        io.execute { runCatching { prefs.edit().clear().apply() } }
    }

    companion object {
        /**
         * 快照存于最近一次确认有效时（登录成功或探活通过）。没写探活的站点只在登录时存，
         * 恢复后直接信任，所以对它们这就是会话年龄上限：太旧的多半已被服务端回收，不如重走一次免密登录。
         */
        const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

        private val io = Executors.newSingleThreadExecutor { Thread(it, "site-snapshots").apply { isDaemon = true } }

        internal fun encode(tokens: Map<String, String>, savedAt: Long): String = buildJsonObject {
            put("at", savedAt)
            put("tokens", buildJsonObject { tokens.toSortedMap().forEach { (k, v) -> put(k, v) } })
        }.toString()

        internal fun decode(raw: String, now: Long): Map<String, String>? {
            val json = runCatching { raw.safeParseJsonObject() }.getOrNull() ?: return null
            val savedAt = (json["at"] as? JsonPrimitive)?.longOrNull ?: return null
            if (now - savedAt !in 0..MAX_AGE_MS) return null
            val tokens = (json["tokens"] as? JsonObject)?.mapValues { (_, v) ->
                (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: return null
            } ?: return null
            // 有令牌是已过期的 JWT 就不恢复：拿它发请求必败
            return tokens.takeIf { t -> t.values.none { Jwt.isExpired(it, now) } }
        }
    }
}
