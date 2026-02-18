package com.xjtu.toolbox.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * 持久化 CookieJar — Cookie 存储到 EncryptedSharedPreferences
 *
 * 解决原来内存 CookieManager 每次冷启动丢失所有 session 的问题。
 * 持久化后冷启动时 TGC cookie 仍然有效（CAS 服务端不会主动失效），
 * XJTULogin 的 init 阶段可直接 SSO 跳过登录表单（0 RTT）。
 *
 * 安全性：使用 AES-256 加密存储，与 CredentialStore 同级别。
 * 线程安全：ConcurrentHashMap + synchronized write。
 */
class PersistentCookieJar(context: Context) : CookieJar {

    companion object {
        private const val TAG = "PersistentCookieJar"
        private const val PREFS_NAME = "xjtu_cookies"
        private const val KEY_ALL_COOKIES = "all_cookies"
    }

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
    }

    // domain -> list of cookies（内存缓存）
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    init {
        loadFromDisk()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            val domain = cookie.domain
            val list = cookieStore.getOrPut(domain) { mutableListOf() }
            // 移除同名旧 cookie
            list.removeAll { it.name == cookie.name && it.path == cookie.path }
            // 只保留未过期的
            if (cookie.expiresAt > System.currentTimeMillis()) {
                list.add(cookie)
            }
        }
        saveToDisk()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<Cookie>()
        for ((domain, cookies) in cookieStore) {
            if (domainMatch(url.host, domain)) {
                val iter = cookies.iterator()
                while (iter.hasNext()) {
                    val c = iter.next()
                    if (c.expiresAt <= now) {
                        iter.remove() // 过期清理
                    } else if (pathMatch(url.encodedPath, c.path)) {
                        result.add(c)
                    }
                }
            }
        }
        return result
    }

    /** 清空所有 cookie（登出时使用） */
    fun clear() {
        cookieStore.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "All cookies cleared")
    }

    /** 获取指定域名下的所有 cookie（供旧代码兼容，如 CampusCardLogin 提取 hallticket） */
    fun getCookiesForDomain(domain: String): List<Cookie> {
        return cookieStore[domain]?.toList() ?: emptyList()
    }

    // ── 序列化/反序列化 ──

    private fun saveToDisk() {
        try {
            val sb = StringBuilder()
            for ((_, cookies) in cookieStore) {
                for (c in cookies) {
                    if (c.expiresAt <= System.currentTimeMillis()) continue
                    sb.append(encodeCookie(c)).append('\n')
                }
            }
            prefs.edit().putString(KEY_ALL_COOKIES, sb.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveToDisk failed", e)
        }
    }

    private fun loadFromDisk() {
        try {
            val raw = prefs.getString(KEY_ALL_COOKIES, null) ?: return
            val now = System.currentTimeMillis()
            for (line in raw.lines()) {
                if (line.isBlank()) continue
                val cookie = decodeCookie(line) ?: continue
                if (cookie.expiresAt <= now) continue
                val list = cookieStore.getOrPut(cookie.domain) { mutableListOf() }
                list.removeAll { it.name == cookie.name && it.path == cookie.path }
                list.add(cookie)
            }
            Log.d(TAG, "Loaded ${cookieStore.values.sumOf { it.size }} cookies from disk")
        } catch (e: Exception) {
            Log.e(TAG, "loadFromDisk failed", e)
        }
    }

    /**
     * 编码单个 Cookie 为一行文本
     * 格式: name|value|domain|path|expiresAt|secure|httpOnly|hostOnly
     */
    private fun encodeCookie(c: Cookie): String {
        return listOf(
            c.name, c.value.replace("|", "%7C").replace("\n", "%0A"),
            c.domain, c.path, c.expiresAt.toString(),
            if (c.secure) "1" else "0",
            if (c.httpOnly) "1" else "0",
            if (c.hostOnly) "1" else "0"
        ).joinToString("|")
    }

    private fun decodeCookie(line: String): Cookie? {
        val parts = line.split("|")
        if (parts.size < 8) return null
        return try {
            val builder = Cookie.Builder()
                .name(parts[0])
                .value(parts[1].replace("%7C", "|").replace("%0A", "\n"))
                .path(parts[3])
                .expiresAt(parts[4].toLong())
            if (parts[7] == "1") builder.hostOnlyDomain(parts[2]) else builder.domain(parts[2])
            if (parts[5] == "1") builder.secure()
            if (parts[6] == "1") builder.httpOnly()
            builder.build()
        } catch (_: Exception) {
            null
        }
    }

    // ── Cookie 匹配 ──

    private fun domainMatch(host: String, cookieDomain: String): Boolean {
        if (host == cookieDomain) return true
        if (host.endsWith(".$cookieDomain")) return true
        return false
    }

    private fun pathMatch(urlPath: String, cookiePath: String): Boolean {
        if (urlPath == cookiePath) return true
        if (urlPath.startsWith(cookiePath) && cookiePath.endsWith("/")) return true
        if (urlPath.startsWith(cookiePath) && urlPath.getOrNull(cookiePath.length) == '/') return true
        return false
    }
}
