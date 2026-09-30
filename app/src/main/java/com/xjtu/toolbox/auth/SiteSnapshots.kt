package com.xjtu.toolbox.auth

import android.content.SharedPreferences
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 站点会话快照：每个站点登录成功时的 [SiteSession.localToken]，按 backend 加密存盘，和 cookie 同命名空间、同生命周期。
 *
 * cookie 本来就落盘，但「哪些站点登着」和本地令牌只在内存，冷启动时每个站点都要重走一遍登录链。
 * 有了快照，冷启动恢复成「待确认」：有探活的站点第一次用时探一次，没有的直接用，失效由
 * [SiteSession.executeWithReAuth] 重登自愈。
 *
 * 读写都在 [SiteSession.ensureLogin] 里（IO 线程），加密存储的首次打开也落在那里。
 */
class SiteSnapshots(openPrefs: () -> SharedPreferences) {
    private val prefs by lazy(openPrefs)

    fun save(siteKey: String, tokens: Map<String, String>) {
        val raw = encode(tokens)
        if (prefs.getString(siteKey, null) != raw) prefs.edit().putString(siteKey, raw).apply()
    }

    /** 没有、读不出或令牌已过期时返回 null。 */
    fun load(siteKey: String, now: Long = System.currentTimeMillis()): Map<String, String>? =
        prefs.getString(siteKey, null)?.let { decode(it, now) }

    fun remove(siteKey: String) {
        if (prefs.contains(siteKey)) prefs.edit().remove(siteKey).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        internal fun encode(tokens: Map<String, String>): String =
            buildJsonObject { tokens.toSortedMap().forEach { (k, v) -> put(k, v) } }.toString()

        /** 有令牌是已过期的 JWT 就不恢复：拿它发请求必败。 */
        internal fun decode(raw: String, now: Long): Map<String, String>? {
            val json = runCatching { raw.safeParseJsonObject() }.getOrNull() ?: return null
            val tokens = json.mapValues { (it.value as? JsonPrimitive)?.content ?: return null }
            return tokens.takeIf { t -> t.values.none { Jwt.isExpired(it, now) } }
        }
    }
}
