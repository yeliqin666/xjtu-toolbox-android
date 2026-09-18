package com.xjtu.toolbox.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File

/**
 * 存密码、cookie、API Key 的 EncryptedSharedPreferences 统一入口。
 *
 * 以前各处自己 try/catch，失败后多数退回 `getSharedPreferences("xxx_fallback")`——那是
 * **明文文件**，注释却写着「仅内存」：Keystore 出问题的机器上，账号密码、CAS TGC、
 * API Key 就明文躺在 shared_prefs 里。现在统一成：
 *
 * 1. 正常创建加密 prefs；
 * 2. 失败则删掉同名文件（keyset 损坏最常见）重建一次；
 * 3. 仍失败只用进程内内存，不落盘，重启后要重新登录——宁可麻烦也不明文存凭据。
 *
 * 历史遗留的 `legacyFallbackName` 明文文件：能加密时把内容迁进来，然后无论如何都删掉。
 *
 * **按文件名做进程级缓存**：`EncryptedSharedPreferences.create()` 每次都要向 keystore2
 * 取主密钥、解密钥集，是一串跨进程 binder 调用。CredentialStore/AccountStore 在各处被
 * 反复 new，以前每个实例各 create 一次——实测冷启动首帧里主线程因此向 keystore2 发了
 * 146 次 binder、干等约 300ms。实例本身线程安全，全进程共用一份即可；内存兜底模式下
 * 共用也保证了各处读到的是同一份数据。
 */
object SecurePrefs {
    private const val TAG = "SecurePrefs"

    private val opened = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>()

    fun open(context: Context, name: String, legacyFallbackName: String = "${name}_fallback"): SharedPreferences =
        // computeIfAbsent：并发首次打开同一文件时只 create 一次，后到的线程等它完成
        opened.computeIfAbsent(name) { openUncached(context, name, legacyFallbackName) }

    private fun openUncached(context: Context, name: String, legacyFallbackName: String): SharedPreferences {
        val app = context.applicationContext
        val secure = runCatching { create(app, name) }.recoverCatching { e ->
            Log.e(TAG, "$name: init failed, recreating", e)
            File(app.applicationInfo.dataDir, "shared_prefs")
                .listFiles()?.filter { it.name == "$name.xml" }?.forEach { it.delete() }
            create(app, name)
        }.getOrNull()

        val legacyFile = File(File(app.applicationInfo.dataDir, "shared_prefs"), "$legacyFallbackName.xml")
        val legacy = if (legacyFile.exists()) {
            app.getSharedPreferences(legacyFallbackName, Context.MODE_PRIVATE).all
        } else emptyMap()

        val target: SharedPreferences = secure ?: run {
            Log.e(TAG, "$name: encryption unavailable, using in-memory prefs (will not persist)")
            InMemorySharedPreferences()
        }
        if (legacy.isNotEmpty()) {
            val editor = target.edit()
            for ((k, v) in legacy) {
                if (target.contains(k)) continue
                @Suppress("UNCHECKED_CAST")
                when (v) {
                    is String -> editor.putString(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Boolean -> editor.putBoolean(k, v)
                    is Float -> editor.putFloat(k, v)
                    is Set<*> -> editor.putStringSet(k, v as Set<String>)
                }
            }
            editor.commit()
        }
        if (legacyFile.exists()) {
            runCatching { app.deleteSharedPreferences(legacyFallbackName) }
            Log.w(TAG, "$name: removed plaintext fallback $legacyFallbackName (${legacy.size} keys)")
        }
        return target
    }

    private fun create(app: Context, name: String): SharedPreferences = EncryptedSharedPreferences.create(
        name,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        app,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

/** 仅内存、线程安全的 SharedPreferences，加密存储彻底不可用时的兜底。 */
internal class InMemorySharedPreferences : SharedPreferences {
    private val map = HashMap<String, Any?>()
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    @Synchronized override fun getAll(): MutableMap<String, *> = HashMap(map)
    @Synchronized override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    @Synchronized override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    @Synchronized override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    @Synchronized override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    @Synchronized override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    @Synchronized override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private val removes = HashSet<String>()
        private var clear = false
        private fun put(key: String?, value: Any?) = apply { if (key != null) { puts[key] = value; removes.remove(key) } }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun remove(key: String?) = apply { if (key != null) { removes += key; puts.remove(key) } }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            val changed: Set<String>
            synchronized(this@InMemorySharedPreferences) {
                if (clear) map.clear()
                removes.forEach { map.remove(it) }
                // 与系统实现一致：put(null) 等同 remove
                puts.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                changed = removes + puts.keys
            }
            changed.forEach { k -> listeners.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, k) } }
            return true
        }
        override fun apply() { commit() }
    }

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (l != null) listeners += l
    }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (l != null) listeners -= l
    }
}
