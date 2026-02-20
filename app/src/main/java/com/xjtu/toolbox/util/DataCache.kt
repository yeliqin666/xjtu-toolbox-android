package com.xjtu.toolbox.util

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DataCache"

/**
 * [RC] 轻量级 JSON 文件缓存（线程安全 + 原子写入）
 * 用于缓存课表、成绩等学期内稳定的数据，二次打开 0ms
 *
 * 缓存目录: `context.cacheDir/data_cache/`
 * 文件名: `{key}.json`
 * 过期策略: 手动失效 + TTL（默认 7 天）
 *
 * 线程安全: per-key 锁，不同 key 之间无竞争
 * 原子写入: 先写 .tmp 再 rename，避免写入中途 crash 损坏文件
 */
class DataCache(context: Context) {
    private val cacheDir = File(context.cacheDir, "data_cache").apply { mkdirs() }

    /** per-key 锁对象，不同 key 之间互不阻塞 */
    private val locks = ConcurrentHashMap<String, Any>()

    companion object {
        /** 默认 TTL: 7 天（课表/成绩在学期内基本稳定） */
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000L
        /** 短 TTL: 30 分钟（座位等实时数据） */
        const val SHORT_TTL_MS = 30L * 60 * 1000L
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
