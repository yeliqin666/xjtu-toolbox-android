package com.xjtu.toolbox.data

import com.xjtu.toolbox.util.AppJson
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
 * 缓存目录: `context.cacheDir/data_cache${AccountContext.suffixFor(accountId)}/`
 * 文件名: `{key}.json`
 * 过期策略: 手动失效 + TTL（默认 7 天）
 *
 * 账号隔离：账号在**构造时**定下（默认取当前激活账号），之后不再变。
 * 以前每次 get/put 都现读 [AccountContext.activeAccountId]，一个请求发出去时是账号 A、
 * 回来时已切到账号 B，结果就被写进了 B 的目录（串号）。现在调用方在发请求前建好实例，
 * 写回时自然落在发起时的账号下。长期持有实例的地方（页面 remember、ViewModel）
 * 必须在账号变化时重建实例，否则会一直读写旧账号。
 *
 * 线程安全: per-key 锁，不同 key 之间无竞争
 * 原子写入: 先写 .tmp 再 rename，避免写入中途 crash 损坏文件
 */
class DataCache(
    context: Context,
    accountId: String? = AccountContext.activeAccountId,
) {
    private val appContext = context.applicationContext
    private val dirName = "data_cache${AccountContext.suffixFor(accountId)}"

    /**
     * 本实例所属账号的缓存目录。读路径不建目录（不存在即未命中）；只有 [put] 写之前
     * 才 mkdirs——「清除缓存」或换包清理随时可能把它删掉，所以每次写都要确认一次。
     */
    private val cacheDir: File = File(appContext.cacheDir, dirName)

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
        private const val KEY_INSTALL_STAMP = "install_stamp"
        private val UNSAFE_FILE_CHARS = Regex("[^a-zA-Z0-9_-]")

        /**
         * 安装包变了（升级、同版本号重新发包后覆盖安装）就把全部账号的 `data_cache*` 目录清空，
         * 必须在任何读缓存之前调用，放在 [com.xjtu.toolbox.XjtuApp.attachBaseContext]：
         * 它早于所有 ContentProvider（含 WorkManager 的自动初始化）执行，升级后重新调度的
         * Worker 不可能抢在清理之前跑起来；放 onCreate 则有这个窗口。
         *
         * 缓存本来就能重新拉，换包时整体丢掉最省心：旧版（Gson）写的缓存字段名被 R8 混淆过，
         * 新版读不懂；以后模型改了结构也不用考虑缓存兼容。
         *
         * 判据用 versionCode + lastUpdateTime 而不是只看 versionCode：同一个版本号重新打包
         * 发布（4.9.4、4.9.5 都发生过）时 mapping 也可能变，只有安装时间能区分。
         *
         * 只动 cacheDir 里的 DataCache，SharedPreferences 与 filesDir 里的持久状态
         * （账号、会话、考勤、校园卡、课表变更快照）已 keep，不受影响。
         */
        fun clearIfPackageChanged(context: Context) {
            // attachBaseContext 阶段 applicationContext 还是 null，直接用传进来的 base context
            val app = context.applicationContext ?: context
            val prefs = app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            val installedAt = runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
            val stamp = "${com.xjtu.toolbox.BuildConfig.VERSION_CODE}@$installedAt"
            if (prefs.getString(KEY_INSTALL_STAMP, null) == stamp) return
            // listFiles 返回 null 表示 cacheDir 本身读不了（I/O 错误），不能当"没有目录"处理
            val dirs = app.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("data_cache") }
            // deleteRecursively 失败只返回 false、不一定抛异常，必须看返回值
            val allCleared = dirs != null && dirs.all { dir ->
                runCatching { dir.deleteRecursively() }
                    .onFailure { Log.w(TAG, "clear ${dir.name} failed", it) }
                    .getOrDefault(false)
            }
            if (!allCleared) {
                // 不写标记：旧格式缓存还在，下次启动再清一次，而不是就此放过
                Log.w(TAG, "package changed -> $stamp, data_cache NOT fully cleared, will retry next launch")
                return
            }
            Log.i(TAG, "package changed -> $stamp, data_cache cleared")
            prefs.edit().putString(KEY_INSTALL_STAMP, stamp).apply()
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
                cacheDir.mkdirs()
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
    /** 按类型读缓存；过期、不存在或读不回来（旧格式损坏）都返回 null，调用方重新拉。 */
    inline fun <reified T> read(key: String, ttlMs: Long = DEFAULT_TTL_MS): T? =
        get(key, ttlMs)?.let { runCatching { AppJson.decodeFromString<T>(it) }.getOrNull() }

    /** 不看过期时间，网络失败时兜底用。 */
    inline fun <reified T> readStale(key: String): T? =
        getStale(key)?.let { runCatching { AppJson.decodeFromString<T>(it) }.getOrNull() }

    inline fun <reified T> write(key: String, value: T) = put(key, AppJson.encodeToString(value))

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
    private fun String.sanitize(): String = replace(UNSAFE_FILE_CHARS, "_")
}
