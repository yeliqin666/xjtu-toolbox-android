package com.xjtu.toolbox.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * 凭据安全存储（使用 EncryptedSharedPreferences）
 * 密码使用 AES-256-GCM 加密存储，密钥由 Android Keystore 管理
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "xjtu_credentials",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // 加密失败：尝试清除损坏文件后重建
        android.util.Log.e("CredentialStore", "EncryptedSharedPreferences init failed, attempting recovery", e)
        try {
            // 删除可能损坏的文件
            val prefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
            prefsDir.listFiles()?.filter { it.name.startsWith("xjtu_credentials") }?.forEach { it.delete() }
            EncryptedSharedPreferences.create(
                "xjtu_credentials",
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // 最终兆底：仅内存 SharedPreferences，不写磁盘，避免明文存储密码
            android.util.Log.e("CredentialStore", "Recovery failed, using in-memory prefs (credentials will not persist)")
            InMemorySharedPreferences()
        }
    }

    fun save(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun load(): Pair<String, String>? {
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        if (username.isEmpty() || password.isEmpty()) return null
        return username to password
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // ── 设备指纹持久化（避免触发 MFA）──

    fun saveFpVisitorId(id: String) {
        prefs.edit().putString(KEY_FP_VISITOR_ID, id).apply()
    }

    fun loadFpVisitorId(): String? = prefs.getString(KEY_FP_VISITOR_ID, null)

    // ── RSA 公钥缓存（减少一次网络请求）──

    fun saveRsaPublicKey(key: String) {
        prefs.edit()
            .putString(KEY_RSA_PUBLIC_KEY, key)
            .putLong(KEY_RSA_KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    /** 获取缓存的 RSA 公钥（24 小时有效期） */
    fun loadRsaPublicKey(): String? {
        val time = prefs.getLong(KEY_RSA_KEY_TIME, 0L)
        if (System.currentTimeMillis() - time > 24 * 3600 * 1000L) return null
        return prefs.getString(KEY_RSA_PUBLIC_KEY, null)
    }

    // ── 用户昵称缓存（欢迎卡片秒显示）──

    fun saveNickname(name: String) {
        prefs.edit().putString(KEY_NICKNAME, name).apply()
    }

    fun loadNickname(): String? = prefs.getString(KEY_NICKNAME, null)

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_FP_VISITOR_ID = "fp_visitor_id"
        private const val KEY_RSA_PUBLIC_KEY = "rsa_public_key"
        private const val KEY_RSA_KEY_TIME = "rsa_key_time"
        private const val KEY_NICKNAME = "cached_nickname"
    }
}

/**
 * 仅内存的 SharedPreferences 实现（不写磁盘，用作加密存储彻底失败时的兜底）
 * 凭据仅在当前进程生命周期内有效，重启后需重新登录
 */
private class InMemorySharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
        override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
        override fun putStringSet(key: String?, value: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
        override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
        override fun remove(key: String?) = apply { pending.remove(key) }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { if (clear) map.clear(); map.putAll(pending); return true }
        override fun apply() { commit() }
    }

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
