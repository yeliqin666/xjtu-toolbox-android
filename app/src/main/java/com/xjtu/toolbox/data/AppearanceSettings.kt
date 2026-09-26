package com.xjtu.toolbox.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 外观设置的可观察视图。写入仍走 [CredentialStore]，这里监听同一份文件，谁改了界面都跟着变。
 * 进程单例；监听器必须强引用持有（SharedPreferences 只弱引用它）。
 */
class AppearanceSettings private constructor(context: Context) {

    private val store = CredentialStore(context)
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(CredentialStore.APP_SETTINGS_FILE, Context.MODE_PRIVATE)

    private val _darkMode = MutableStateFlow(store.darkMode)
    private val _dynamicColor = MutableStateFlow(store.dynamicColor)
    private val _navBarStyle = MutableStateFlow(store.navBarStyle)
    private val _homeTheme = MutableStateFlow(store.homeTheme)
    private val _showQuickActions = MutableStateFlow(store.showQuickActions)

    /** "system" / "light" / "dark"，见 [CredentialStore.DARK_MODE_SYSTEM] 等。 */
    val darkMode: StateFlow<String> = _darkMode.asStateFlow()
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    /** [CredentialStore.NAV_STYLE_FLOATING]（玻璃）或 [CredentialStore.NAV_STYLE_CLASSIC]。 */
    val navBarStyle: StateFlow<String> = _navBarStyle.asStateFlow()
    val homeTheme: StateFlow<String> = _homeTheme.asStateFlow()
    val showQuickActions: StateFlow<Boolean> = _showQuickActions.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _darkMode.value = store.darkMode
        _dynamicColor.value = store.dynamicColor
        _navBarStyle.value = store.navBarStyle
        _homeTheme.value = store.homeTheme
        _showQuickActions.value = store.showQuickActions
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        @Volatile private var instance: AppearanceSettings? = null

        fun get(context: Context): AppearanceSettings =
            instance ?: synchronized(this) {
                instance ?: AppearanceSettings(context.applicationContext).also { instance = it }
            }
    }
}
