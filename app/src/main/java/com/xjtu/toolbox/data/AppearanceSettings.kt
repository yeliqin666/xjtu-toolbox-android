package com.xjtu.toolbox.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 外观设置的可观察视图。写入仍走 [CredentialStore]，这里监听同一份文件，谁改了界面都跟着变。
 * 进程单例；监听器必须强引用持有（SharedPreferences 只弱引用它），见 [listener]。
 */
class AppearanceSettings private constructor(context: Context) {

    private val store = CredentialStore(context)
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(CredentialStore.APP_SETTINGS_FILE, Context.MODE_PRIVATE)

    private val _darkMode = MutableStateFlow(store.darkMode)
    private val _customColorHex = MutableStateFlow(store.customColorHex)
    val customColorHex: StateFlow<String> = _customColorHex.asStateFlow()
    private val _customDarkColorHex = MutableStateFlow(store.customDarkColorHex)
    val customDarkColorHex: StateFlow<String> = _customDarkColorHex.asStateFlow()
    private val _dynamicColor = MutableStateFlow(store.dynamicColor)
    private val _navBarStyle = MutableStateFlow(store.navBarStyle)
    private val _showQuickActions = MutableStateFlow(store.showQuickActions)
    private val _bottomTabs = MutableStateFlow(store.bottomTabs)

    /** "system" / "light" / "dark"，见 [CredentialStore.DARK_MODE_SYSTEM] 等。 */
    val darkMode: StateFlow<String> = _darkMode.asStateFlow()
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    /** [CredentialStore.NAV_STYLE_FLOATING]（玻璃）或 [CredentialStore.NAV_STYLE_CLASSIC]。 */
    val navBarStyle: StateFlow<String> = _navBarStyle.asStateFlow()
    val showQuickActions: StateFlow<Boolean> = _showQuickActions.asStateFlow()

    /** 底栏从左到右的格子 id，见 [CredentialStore.bottomTabs]。 */
    val bottomTabs: StateFlow<List<String>> = _bottomTabs.asStateFlow()

    // 这个字段只写不读，R8 会把它删掉，监听器随后被回收，设置改了界面不跟（debug 包不混淆所以没事）
    @field:androidx.annotation.Keep
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _darkMode.value = store.darkMode
        _dynamicColor.value = store.dynamicColor
        _customColorHex.value = store.customColorHex
        _customDarkColorHex.value = store.customDarkColorHex
        _navBarStyle.value = store.navBarStyle
        _showQuickActions.value = store.showQuickActions
        _bottomTabs.value = store.bottomTabs
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
