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

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                "xjtu_credentials",
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 加密失败：尝试清除损坏文件后重建
            android.util.Log.e("CredentialStore", "EncryptedSharedPreferences init failed, attempting recovery", e)
            try {
                // 删除可能损坏的文件
                val prefsDir = java.io.File(appContext.applicationInfo.dataDir, "shared_prefs")
                prefsDir.listFiles()?.filter { it.name.startsWith("xjtu_credentials") }?.forEach { it.delete() }
                EncryptedSharedPreferences.create(
                    "xjtu_credentials",
                    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                    appContext,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                // 最终兆底：仅内存 SharedPreferences，不写磁盘，避免明文存储密码
                android.util.Log.e("CredentialStore", "Recovery failed, using in-memory prefs (credentials will not persist)")
                InMemorySharedPreferences()
            }
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

    // ── NSA 个人信息持久化（首次登录全量加载，后续冷启动复用） ──

    /** 保存 NSA 个人信息 JSON（NsaStudentProfile.toJson()） */
    fun saveNsaProfile(json: String) {
        prefs.edit().putString(KEY_NSA_PROFILE, json).apply()
    }

    /** 加载缓存的 NSA 个人信息 JSON */
    fun loadNsaProfile(): String? = prefs.getString(KEY_NSA_PROFILE, null)

    /** 保存 NSA 学生证照片到内部文件 */
    fun saveNsaPhoto(bytes: ByteArray) {
        try {
            appContext.openFileOutput(NSA_PHOTO_FILE, Context.MODE_PRIVATE).use { it.write(bytes) }
        } catch (e: Exception) {
            android.util.Log.w("CredentialStore", "saveNsaPhoto failed", e)
        }
    }

    /** 加载缓存的 NSA 学生证照片 */
    fun loadNsaPhoto(): ByteArray? = try {
        appContext.openFileInput(NSA_PHOTO_FILE).use { it.readBytes() }
    } catch (_: Exception) { null }

    /** 清除 NSA 缓存（退出登录时调用） */
    fun clearNsaCache() {
        prefs.edit().remove(KEY_NSA_PROFILE).apply()
        try { appContext.deleteFile(NSA_PHOTO_FILE) } catch (_: Exception) {}
    }

    // ── 用户协议 & 公告（非敏感，使用普通 SharedPreferences） ──

    private val appPrefs: SharedPreferences =
        appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    /** 用户是否已同意当前版本的用户协议 */
    fun isEulaAccepted(): Boolean =
        appPrefs.getInt(KEY_EULA_VERSION, 0) >= CURRENT_EULA_VERSION

    /** 标记用户已同意用户协议 */
    fun acceptEula() {
        appPrefs.edit().putInt(KEY_EULA_VERSION, CURRENT_EULA_VERSION).apply()
    }

    /** 用户是否已看过指定版本的更新公告 */
    fun isUpdateNoticeSeen(versionName: String): Boolean =
        appPrefs.getBoolean("update_notice_$versionName", false)

    /** 标记用户已看过更新公告 */
    fun markUpdateNoticeSeen(versionName: String) {
        appPrefs.edit().putBoolean("update_notice_$versionName", true).apply()
    }

    // ── 设置页持久化（普通 SharedPreferences，非敏感） ──

    /** 获取 app_settings SharedPreferences，供 Compose 端直接读取/写入 */
    fun getAppPrefs(): SharedPreferences = appPrefs

    var navBarStyle: String
        get() = appPrefs.getString(KEY_NAV_BAR_STYLE, NAV_STYLE_FLOATING) ?: NAV_STYLE_FLOATING
        set(value) { appPrefs.edit().putString(KEY_NAV_BAR_STYLE, value).apply() }

    var darkMode: String
        get() = appPrefs.getString(KEY_DARK_MODE, DARK_MODE_SYSTEM) ?: DARK_MODE_SYSTEM
        set(value) { appPrefs.edit().putString(KEY_DARK_MODE, value).apply() }

    var defaultTab: String
        get() = appPrefs.getString(KEY_DEFAULT_TAB, TAB_HOME) ?: TAB_HOME
        set(value) { appPrefs.edit().putString(KEY_DEFAULT_TAB, value).apply() }

    var networkMode: String
        get() = appPrefs.getString(KEY_NETWORK_MODE, NETWORK_AUTO) ?: NETWORK_AUTO
        set(value) { appPrefs.edit().putString(KEY_NETWORK_MODE, value).apply() }

    var autoCheckUpdate: Boolean
        get() = appPrefs.getBoolean(KEY_AUTO_CHECK_UPDATE, true)
        set(value) { appPrefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATE, value).apply() }

    var updateChannel: String
        get() = appPrefs.getString(KEY_UPDATE_CHANNEL, CHANNEL_STABLE) ?: CHANNEL_STABLE
        set(value) { appPrefs.edit().putString(KEY_UPDATE_CHANNEL, value).apply() }

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_FP_VISITOR_ID = "fp_visitor_id"
        private const val KEY_RSA_PUBLIC_KEY = "rsa_public_key"
        private const val KEY_RSA_KEY_TIME = "rsa_key_time"
        private const val KEY_NICKNAME = "cached_nickname"
        private const val KEY_NSA_PROFILE = "nsa_profile_json"
        private const val NSA_PHOTO_FILE = "nsa_photo.jpg"
        private const val KEY_EULA_VERSION = "eula_accepted_version"
        /** 用户协议版本号，更新协议内容时递增 */
        const val CURRENT_EULA_VERSION = 2

        // ── 设置页键 ──
        private const val KEY_NAV_BAR_STYLE = "nav_bar_style"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_DEFAULT_TAB = "default_tab"
        private const val KEY_NETWORK_MODE = "network_mode"
        private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
        private const val KEY_UPDATE_CHANNEL = "update_channel"

        // ── 设置值常量 ──
        const val NAV_STYLE_FLOATING = "floating"
        const val NAV_STYLE_CLASSIC = "classic"
        const val DARK_MODE_SYSTEM = "system"
        const val DARK_MODE_LIGHT = "light"
        const val DARK_MODE_DARK = "dark"
        const val TAB_HOME = "HOME"
        const val TAB_COURSES = "COURSES"
        const val TAB_TOOLS = "TOOLS"
        const val TAB_PROFILE = "PROFILE"
        const val NETWORK_AUTO = "auto"
        const val NETWORK_DIRECT = "direct"
        const val NETWORK_VPN = "vpn"
        const val CHANNEL_STABLE = "stable"
        const val CHANNEL_BETA = "beta"
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
