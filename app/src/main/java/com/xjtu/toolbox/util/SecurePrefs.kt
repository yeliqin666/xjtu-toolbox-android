package com.xjtu.toolbox.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.concurrent.ConcurrentHashMap

/**
 * 加密 SharedPreferences 的进程级缓存。
 *
 * **为什么需要它**：`EncryptedSharedPreferences.create()` 每次都要做 Keystore 密钥
 * 派生。实测（模拟器，同一文件）：首次约 137ms，之后每次约 20ms。而
 * [CredentialStore] / [com.xjtu.toolbox.account.AccountStore] / [PersistentCookieJar]
 * 在全项目有十几处构造点，且每个实例的 prefs 都是各自的 `by lazy`——也就是说同一份
 * 文件会被反复 create，这段成本被重复支付了很多次。
 *
 * 另外它修掉一个潜在的**正确性**问题：同一 cookies 文件若存在两个 jar 实例，各自持有
 * 独立的内存映射，写入会互相覆盖。共享同一个 prefs 实例后这类分叉不存在了。
 *
 * [AgentConfigStore][com.xjtu.toolbox.agent.AgentConfigStore] 早有同样的按账号缓存，
 * 只是各自实现了一份；这里把公共逻辑收拢成一处。
 *
 * 线程安全：`get` 全程持锁。这不是保守，而是有意为之——调用方本来就非等到实例不可，
 * 让后到者阻塞在锁上，正好让「后台预热」与「前台首次使用」自然串行：预热带走的活越多，
 * 前台剩下的等待就越短，无需额外的 latch 协调。
 */
internal object SecurePrefs {

    private const val TAG = "SecurePrefs"

    private val cache = ConcurrentHashMap<String, SharedPreferences>()

    /** MasterKey 只取一次：它自身也要做一次 Keystore 查询。 */
    private val masterKey: String by lazy { MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC) }

    /**
     * 取（必要时创建）加密 prefs。
     *
     * [fallback] 是加密彻底失败时的兜底——各调用方语义不同，必须由调用方给：
     * 凭据存储退到**内存**（宁可这次不持久化，也不能把密码落成明文），
     * 账号与 cookie 则退到普通 prefs。把这条差异统一掉会变成安全问题。
     */
    @Synchronized
    fun get(
        context: Context,
        name: String,
        fallback: (Context, String) -> SharedPreferences,
    ): SharedPreferences = cache.getOrPut(name) {
        create(context.applicationContext, name, fallback)
    }

    /**
     * 后台预热指定文件。[names] 应覆盖冷启动路径上真正会用到的文件。
     *
     * 失败不影响调用方：这些文件的创建本来就会在首次使用时重试一遍。
     */
    fun preload(context: Context, names: List<String>, fallback: (Context, String) -> SharedPreferences) {
        val app = context.applicationContext
        for (name in names) {
            runCatching { get(app, name, fallback) }
                .onFailure { Log.w(TAG, "preload failed: $name", it) }
        }
    }

    private fun create(
        appContext: Context,
        name: String,
        fallback: (Context, String) -> SharedPreferences,
    ): SharedPreferences {
        try {
            return open(appContext, name)
        } catch (e: Exception) {
            // 加密文件可能损坏：删掉重建一次（与各 store 原来的恢复逻辑一致）
            Log.e(TAG, "EncryptedSharedPreferences init failed for $name, attempting recovery", e)
            return try {
                val dir = java.io.File(appContext.applicationInfo.dataDir, "shared_prefs")
                dir.listFiles()?.filter { it.name.startsWith(name) }?.forEach { it.delete() }
                open(appContext, name)
            } catch (_: Exception) {
                Log.e(TAG, "Recovery failed for $name, using fallback")
                fallback(appContext, name)
            }
        }
    }

    private fun open(appContext: Context, name: String): SharedPreferences =
        EncryptedSharedPreferences.create(
            name,
            masterKey,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
