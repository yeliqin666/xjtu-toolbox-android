package com.xjtu.toolbox.settings

import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore

/** 设置页那些偏好的读写。 */
class SettingsStore(private val store: KeyValueStore = keyValueStore(APP_SETTINGS_FILE)) {

    /** 界面风格：[NAV_STYLE_FLOATING] 玻璃（默认），[NAV_STYLE_CLASSIC] 经典。 */
    var navBarStyle: String
        get() = store.getString(KEY_NAV_BAR_STYLE) ?: NAV_STYLE_FLOATING
        set(value) = store.putString(KEY_NAV_BAR_STYLE, value)

    var darkMode: String
        get() = store.getString(KEY_DARK_MODE) ?: DARK_MODE_SYSTEM
        set(value) = store.putString(KEY_DARK_MODE, value)

    /** 跟随系统壁纸 / 调色盘动态取色（Material You / Monet）。默认关闭。 */
    var dynamicColor: Boolean
        get() = store.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) = store.putBoolean(KEY_DYNAMIC_COLOR, value)

    /** 默认启动 tab。 */
    var defaultTab: String
        get() = store.getString(KEY_DEFAULT_TAB) ?: TAB_COURSES
        set(value) = store.putString(KEY_DEFAULT_TAB, value)

    var networkMode: String
        get() = store.getString(KEY_NETWORK_MODE) ?: NETWORK_AUTO
        set(value) = store.putString(KEY_NETWORK_MODE, value)

    var homeTheme: String
        get() = store.getString(KEY_HOME_THEME) ?: THEME_CARD
        set(value) = store.putString(KEY_HOME_THEME, value)

    var showQuickActions: Boolean
        get() = store.getBoolean(KEY_SHOW_QUICK_ACTIONS, true)
        set(value) = store.putBoolean(KEY_SHOW_QUICK_ACTIONS, value)

    /** 场馆滑块验证码自动识别开关。 */
    var venueAutoSolveCaptchaEnabled: Boolean
        get() = store.getBoolean(KEY_VENUE_AUTO_SOLVE_CAPTCHA, true)
        set(value) = store.putBoolean(KEY_VENUE_AUTO_SOLVE_CAPTCHA, value)

    /** 课表格子上叠考勤角标。 */
    var scheduleAttendanceBadge: Boolean
        get() = store.getBoolean(KEY_SCHEDULE_ATTENDANCE_BADGE, true)
        set(value) = store.putBoolean(KEY_SCHEDULE_ATTENDANCE_BADGE, value)

    /** 当前学期课表从哪个系统拉，取值见 `ScheduleSource.key`。 */
    var scheduleSource: String
        get() = store.getString(KEY_SCHEDULE_SOURCE) ?: SCHEDULE_SOURCE_JWXT
        set(value) = store.putString(KEY_SCHEDULE_SOURCE, value)

    companion object {
        /** 非敏感的应用设置（外观、协议、更新……），普通 SharedPreferences。 */
        const val APP_SETTINGS_FILE = "app_settings"

        private const val KEY_NAV_BAR_STYLE = "nav_bar_style"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_DEFAULT_TAB = "default_tab"
        private const val KEY_NETWORK_MODE = "network_mode"
        private const val KEY_HOME_THEME = "home_theme"
        private const val KEY_SHOW_QUICK_ACTIONS = "show_quick_actions"
        private const val KEY_VENUE_AUTO_SOLVE_CAPTCHA = "venue_auto_solve_captcha"
        private const val KEY_SCHEDULE_ATTENDANCE_BADGE = "schedule_attendance_badge"
        private const val KEY_SCHEDULE_SOURCE = "schedule_source"

        const val SCHEDULE_SOURCE_JWXT = "jwxt"

        /** 取值是旧版考勤（bkkq）的写法，已写进用户设置，不能改。 */
        const val SCHEDULE_SOURCE_ATTENDANCE = "bkkq"
        const val NAV_STYLE_FLOATING = "floating"
        const val NAV_STYLE_CLASSIC = "classic"
        const val DARK_MODE_SYSTEM = "system"
        const val DARK_MODE_LIGHT = "light"
        const val DARK_MODE_DARK = "dark"
        const val THEME_CARD = "card"
        const val THEME_ICON = "icon"
        const val TAB_HOME = "HOME"
        const val TAB_COURSES = "COURSES"
        const val TAB_PIDAI = "PIDAI"
        const val TAB_TOOLS = "TOOLS"
        const val TAB_PROFILE = "PROFILE"
        const val NETWORK_AUTO = "auto"
        const val NETWORK_DIRECT = "direct"
        const val NETWORK_VPN = "vpn"
    }
}
