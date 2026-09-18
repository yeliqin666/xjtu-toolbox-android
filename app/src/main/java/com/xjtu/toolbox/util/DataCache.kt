package com.xjtu.toolbox.util

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.account.AccountContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DataCache"

/**
 * [RC] 轻量级 JSON 文件缓存（线程安全 + 原子写入）
 * 用于缓存日程、成绩等学期内稳定的数据，二次打开 0ms
 *
 * 缓存目录: `context.cacheDir/data_cache${AccountContext.safeSuffix()}/`
 * 文件名: `{key}.json`
 * 过期策略: 手动失效 + TTL（默认 7 天）
 *
 * 账号隔离：缓存目录随 [AccountContext.activeAccountId] 变化，
 * 切换账号后 get/put 自动落到新账号目录，旧账号数据不会被读到。
 *
 * 线程安全: per-key 锁，不同 key 之间无竞争
 * 原子写入: 先写 .tmp 再 rename，避免写入中途 crash 损坏文件
 */
class DataCache(context: Context) {
    private val appContext = context.applicationContext

    /** 当前账号对应的缓存目录，每次调用动态解析以响应账号切换。 */
    private val cacheDir: File
        get() = File(appContext.cacheDir, "data_cache${AccountContext.safeSuffix()}").apply { mkdirs() }

    /** 兼容旧调用：返回账号无关的默认目录，仅迁移时使用。 */
    private val legacyCacheDir: File
        get() = File(appContext.cacheDir, "data_cache")

    /** per-key 锁对象，不同 key 之间互不阻塞 */
    private val locks = ConcurrentHashMap<String, Any>()

    companion object {
        /** 默认 TTL: 7 天（日程/成绩在学期内基本稳定） */
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000L
        /** 短 TTL: 30 分钟（座位等实时数据） */
        const val SHORT_TTL_MS = 30L * 60 * 1000L
        /** 学期内稳定数据 TTL: 90 天，足以覆盖最长学期；过期后自然重新拉新学期数据。 */
        const val TERM_TTL_MS = 90L * 24 * 60 * 60 * 1000L

        private const val META_PREFS = "data_cache_meta"
        private const val KEY_VERSION_CODE = "version_code"

        /**
         * versionCode 变了就把全部账号的 `data_cache*` 目录清空，必须在任何读缓存之前调用
         * （[com.xjtu.toolbox.XjtuApp.onCreate]）。
         *
         * 缓存里的模型类没在 proguard 里 keep，字段名由 R8 每次构建各自决定。换了版本
         * 还按新名字去读老文件，Gson 会把对不上的非空字段悄悄置成 null，4.9.4 就这样崩过
         * 一轮（#51）。`sanitized()` 只能逐个类补，漏一个就又是 NPE；缓存本来就能重新拉，
         * 换版本时整体丢掉最省心。只动 cacheDir 里的 DataCache，SharedPreferences 与
         * filesDir 里的持久状态（账号、会话、考勤、校园卡）已 keep，不受影响。
         */
        fun clearIfVersionChanged(context: Context) {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            val current = com.xjtu.toolbox.BuildConfig.VERSION_CODE
            if (prefs.getInt(KEY_VERSION_CODE, -1) == current) return
            app.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("data_cache") }
                ?.forEach { dir ->
                    runCatching { dir.deleteRecursively() }
                        .onFailure { Log.w(TAG, "clear ${dir.name} failed", it) }
                }
            Log.i(TAG, "versionCode -> $current, data_cache cleared")
            prefs.edit().putInt(KEY_VERSION_CODE, current).apply()
        }
    }

    /** 获取指定 key 的锁对象 */
    private fun lockFor(key: String): Any = locks.getOrPut(key) { Any() }

    /**
     * 读取缓存
     * @param key 缓存键（如 "schedule_2024-2025-2"）
     * @param ttlMs 最大有效期（毫秒），超时返回 null
     * @return JSON 字符串，或 null（未缓存/已过期）
     */
    fun get(key: String, ttlMs: Long = DEFAULT_TTL_MS): String? {
        synchronized(lockFor(key)) {
            val file = File(cacheDir, "${key.sanitize()}.json")
            if (!file.exists()) return null
            val age = System.currentTimeMillis() - file.lastModified()
            if (age > ttlMs) {
                Log.d(TAG, "get($key): expired (age=${age / 1000}s > ttl=${ttlMs / 1000}s)")
                file.delete()
                return null
            }
            return try {
                file.readText().also {
                    Log.d(TAG, "get($key): hit (age=${age / 1000}s, size=${it.length})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "get($key): read error", e)
                null
            }
        }
    }

    /**
     * 写入缓存（原子写入：先写 .tmp 再 rename）
     * @param key 缓存键
     * @param json JSON 字符串
     */
    fun put(key: String, json: String) {
        synchronized(lockFor(key)) {
            try {
                val sanitized = key.sanitize()
                val file = File(cacheDir, "${sanitized}.json")
                val tmpFile = File(cacheDir, "${sanitized}.json.tmp")
                // 先写临时文件
                tmpFile.writeText(json)
                // 原子重命名（Android/Linux rename 是原子操作）
                if (!tmpFile.renameTo(file)) {
                    // renameTo 失败时回退到直接写
                    file.writeText(json)
                    tmpFile.delete()
                }
                Log.d(TAG, "put($key): written ${json.length} bytes")
            } catch (e: Exception) {
                Log.w(TAG, "put($key): write error", e)
            }
        }
    }

    /**
     * 返回指定 key 缓存的年龄（毫秒），即「距上次写入过去了多久」。
     * 未缓存返回 null。不受 TTL 限制——即使已过 TTL，只要文件还在就返回真实年龄，
     * 供调用方（如 Agent）在联网失败回退缓存时如实告知数据新鲜度。
     */
    fun ageMs(key: String): Long? {
        synchronized(lockFor(key)) {
            val file = File(cacheDir, "${key.sanitize()}.json")
            if (!file.exists()) return null
            return System.currentTimeMillis() - file.lastModified()
        }
    }

    /** 读取缓存内容，忽略 TTL（只要文件存在就返回）。用于联网失败时的兜底回退。 */
    fun getStale(key: String): String? {
        synchronized(lockFor(key)) {
            val file = File(cacheDir, "${key.sanitize()}.json")
            if (!file.exists()) return null
            return try {
                file.readText()
            } catch (e: Exception) {
                Log.w(TAG, "getStale($key): read error", e)
                null
            }
        }
    }

    /**
     * 使指定缓存失效
     */
    fun invalidate(key: String) {
        synchronized(lockFor(key)) {
            val file = File(cacheDir, "${key.sanitize()}.json")
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "invalidate($key)")
            }
        }
    }

    /**
     * 清除所有缓存
     */
    fun clearAll() {
        // clearAll 需要全局锁，避免与单 key 操作冲突
        synchronized(this) {
            cacheDir.listFiles()?.forEach { it.delete() }
            locks.clear()
            Log.d(TAG, "clearAll()")
        }
    }

    /** 安全化文件名 */
    private fun String.sanitize(): String = this.replace(Regex("[^a-zA-Z0-9_-]"), "_")
}
