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
    } catch (_: Exception) {
        // Fallback: 如果加密失败（极端情况），用普通 SharedPreferences
        context.getSharedPreferences("xjtu_credentials_fallback", Context.MODE_PRIVATE)
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

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_FP_VISITOR_ID = "fp_visitor_id"
        private const val KEY_RSA_PUBLIC_KEY = "rsa_public_key"
        private const val KEY_RSA_KEY_TIME = "rsa_key_time"
    }
}
