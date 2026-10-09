package com.xjtu.toolbox.platform

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.xjtu.toolbox.data.SecurePrefs
import java.io.File

/**
 * `:app` 在 `Application.onCreate` 里调一次，把进程级的 `Context` 交给 `:data`。
 *
 * 与 `:core` 的 `initAndroidPlatform` 同形、同理由：`:data` 的 commonMain 不认识 `Context`，
 * 只有 androidMain 认识，而句柄只能由宿主进程注入。
 */
fun initDataPlatform(context: Context) {
    appContext = context.applicationContext
}

@Volatile
private var appContext: Context? = null

private fun requireContext(): Context = appContext
    ?: error("initDataPlatform(context) 还没被调用 —— 见 :data 的 platform/SecureStore.kt")

/**
 * Android 侧就是 `SecurePrefs`（`EncryptedSharedPreferences`）——**同名文件**，
 * 与搬迁前 `SessionBackend` / `PersistentCookieJar` / `SiteSnapshots` 用的是同一批
 * `cookies_normal_default` / `sites_normal_default` 之类。改名就会让所有人重登。
 */
private class PrefsKeyValueStore(private val name: String) : KeyValueStore {
    private val prefs: SharedPreferences
        get() = SecurePrefs.open(requireContext(), name)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        // 与 SharedPreferences 语义一致：写 null 等于删键
        if (value == null) prefs.edit().remove(key).apply() else prefs.edit().putString(key, value).apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getStringSet(key: String): Set<String>? = prefs.getStringSet(key, null)?.toSet()
    override fun putStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, LinkedHashSet(value)).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    /**
     * 用 `commit()` 而不是 `apply()`：搬迁前 `PersistentCookieJar.clear()`（登出时走的那条）
     * 就是同步落盘，退回 `apply()` 会留下「刚登出、进程被杀又登着」的窗口。
     * 调用方都在后台线程（`SiteSnapshots` 的专用线程 / 会话管家的清理路径）。
     */
    override fun clear() {
        prefs.edit().clear().commit()
    }
}

actual fun secureKeyValueStore(name: String): KeyValueStore = PrefsKeyValueStore(name)

/**
 * 与搬迁前 `SessionBackend.wipe` 的口径一致：**文件不存在就什么都不做** ——
 * 不为了清一个已删账号去创建一份新的加密存储。
 */
actual fun wipeSecureStore(name: String) {
    val app = requireContext()
    val file = File(app.applicationInfo.dataDir, "shared_prefs/$name.xml")
    if (!file.exists()) return
    runCatching { SecurePrefs.open(app, name).edit().clear().apply() }
}

actual fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

/** Android：逐字沿用搬迁前的五项（顺序不能改，见 `:data` commonMain 的 KDoc）。 */
actual fun stableDeviceFingerprintFields(): List<String> = listOf(
    "android",
    android.os.Build.MANUFACTURER,
    android.os.Build.BRAND,
    android.os.Build.MODEL,
    android.os.Build.DEVICE,
)
