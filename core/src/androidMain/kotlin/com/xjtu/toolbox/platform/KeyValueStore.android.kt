package com.xjtu.toolbox.platform

import android.content.Context
import android.content.SharedPreferences

/**
 * :app 在 `Application.onCreate` 里调一次，把进程级的 `Context` 交给 `:core`。
 *
 * 这是所有「需要平台句柄」的家族的统一入口：`:core` 的 commonMain 不认识 `Context`，
 * androidMain 才认识，而句柄只能由宿主进程注入。
 */
fun initAndroidPlatform(context: Context) {
    appContext = context.applicationContext
}

private var appContext: Context? = null

/** 各 Android actual 共用的句柄。未初始化时返回 null，让调用方自己决定静默还是报错。 */
internal fun androidPlatformContext(): Context? = appContext

private fun requireContext(): Context = appContext
    ?: error("initAndroidPlatform(context) 还没被调用 —— 见 :core 的 platform/KeyValueStore.kt")

private class AndroidKeyValueStore(private val name: String) : KeyValueStore {
    private val prefs: SharedPreferences
        get() = requireContext().getSharedPreferences(name, Context.MODE_PRIVATE)

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

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        // 只清这个 store 的文件 —— 与现在 :app 里各 Store 自己 clear 自己那份的语义一致
        prefs.edit().clear().apply()
    }
}

actual fun keyValueStore(name: String): KeyValueStore = AndroidKeyValueStore(name)
