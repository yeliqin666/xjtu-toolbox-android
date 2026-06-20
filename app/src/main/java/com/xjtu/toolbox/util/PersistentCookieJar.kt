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
class PersistentCookieJar(context: Context, prefsName: String = PREFS_NAME) : CookieJar {

    companion object {
        private const val TAG = "PersistentCookieJar"
        private const val PREFS_NAME = "xjtu_cookies"
        private const val KEY_ALL_COOKIES = "all_cookies"
    }

    private val appContext = context.applicationContext
    private val prefsFileName = prefsName

    private val prefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                prefsFileName,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            appContext.getSharedPreferences("${prefsFileName}_fallback", Context.MODE_PRIVATE)
        }
    }

    // domain -> list of cookies（内存缓存）
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    // loadFromDisk 延迟到首次使用时触发
    @Volatile private var loaded = false
    private fun ensureLoaded() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    loadFromDisk()
                    loaded = true
                }
            }
        }
    }

    // 防抖写盘：避免 CAS 登录链路中 5-10 次重定向每次都触发加密写入
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var savePending = false
    private val SAVE_DEBOUNCE_MS = 500L

    private fun scheduleSaveToDisk() {
        if (!savePending) {
            savePending = true
            saveHandler.postDelayed({
                savePending = false
                saveToDisk()
            }, SAVE_DEBOUNCE_MS)
        }
    }

    /** 立即写盘（用于 clear / 应用退出前） */
    fun flushToDisk() {
        saveHandler.removeCallbacksAndMessages(null)
        savePending = false
        saveToDisk()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        ensureLoaded()
        for (cookie in cookies) {
            val domain = cookie.domain
            val list = cookieStore.getOrPut(domain) { mutableListOf() }
            synchronized(list) {
                // 移除同名旧 cookie
                list.removeAll { it.name == cookie.name && it.path == cookie.path }
                // 只保留未过期的
                if (cookie.expiresAt > System.currentTimeMillis()) {
                    list.add(cookie)
                }
            }
        }
        scheduleSaveToDisk()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val result = mutableListOf<Cookie>()
        for ((domain, cookies) in cookieStore) {
            if (domainMatch(url.host, domain)) {
                synchronized(cookies) {
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
        }
        return result
    }

    /** 清空所有 cookie（登出时使用） */
    fun clear() {
        cookieStore.clear()
        saveHandler.removeCallbacksAndMessages(null)
        savePending = false
        prefs.edit().clear().apply()
    }

    /** 获取指定域名下的所有 cookie */
    fun getCookiesForDomain(domain: String): List<Cookie> {
        return cookieStore[domain]?.toList() ?: emptyList()
    }

    /**
     * 导出全部 cookie 为原始管道串（迁移账号命名空间用）。
     * 触发 ensureLoaded 后读 cookieStore 当前内容。
     */
    fun exportRaw(): String {
        ensureLoaded()
        val sb = StringBuilder()
        for ((_, cookies) in cookieStore) {
            synchronized(cookies) {
                for (c in cookies) {
                    if (c.expiresAt <= System.currentTimeMillis()) continue
                    sb.append(encodeCookie(c)).append('\n')
                }
            }
        }
        return sb.toString()
    }

    /** 从原始管道串导入 cookie（覆盖式追加，按 name+path 去重）。迁移用。 */
    fun importRaw(raw: String) {
        ensureLoaded()
        val now = System.currentTimeMillis()
        for (line in raw.lines()) {
            if (line.isBlank()) continue
            val cookie = decodeCookie(line) ?: continue
            if (cookie.expiresAt <= now) continue
            val list = cookieStore.getOrPut(cookie.domain) { mutableListOf() }
            synchronized(list) {
                list.removeAll { it.name == cookie.name && it.path == cookie.path }
                list.add(cookie)
            }
        }
        flushToDisk()
    }

    /**
     * 清掉某个域（精确 + 前导点变体）下的所有 cookie。
     *
     * 用途：JWAPP 业务 401 时，旧的 `sk` session cookie 会导致 OAuth 重定向链上 server 返回
     * 同一个过期 token（probe 实证 prefix changed=false）。必须先清掉 jwapp 域 cookie，
     * 让服务端重建 session 才能拿到新 token。
     */
    fun clearForDomain(domain: String) {
        ensureLoaded()
        val variants = listOf(domain, ".$domain")
        var removed = 0
        for (d in variants) {
            cookieStore.remove(d)?.let { removed += it.size }
        }
        if (removed > 0) {
            Log.d(TAG, "clearForDomain($domain): removed $removed cookies")
            scheduleSaveToDisk()
        }
    }

    /**
     * 跨所有 domain 查找指定名称的 cookie。
     * WebVPN 模式下子站点 cookie 实际存在 webvpn.xjtu.edu.cn 域，
     * 直接 loadForRequest(子站点 URL) 拿不到，需要用此方法兜底。
     */
    fun findCookieByName(name: String): Cookie? {
        ensureLoaded()
        val now = System.currentTimeMillis()
        for ((_, cookies) in cookieStore) {
            synchronized(cookies) {
                cookies.find { it.name == name && it.expiresAt > now }?.let { return it }
            }
        }
        return null
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
