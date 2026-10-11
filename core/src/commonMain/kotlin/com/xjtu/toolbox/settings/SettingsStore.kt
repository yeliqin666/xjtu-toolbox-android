package com.xjtu.toolbox.settings

import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore

/**
 * **设置页那些偏好**的读写 —— 从 `:app` 的 `data/CredentialStore` 里切出来搬进 `:core`
 * （键名、默认值、取值一个字符都没改）。
 *
 * ## 为什么它能在 `:core`
 *
 * `CredentialStore` 是一份「凭据 + 应用设置」的合体：密文那半（账号密码 / 指纹 / RSA 公钥，
 * `SecurePrefs` + Android Keystore）留在 `:app`；而设置页这一半本来就住在**普通的**
 * `SharedPreferences("app_settings")` 上（非敏感），换成 [KeyValueStore] 缝即可：
 *
 *  - Android：`keyValueStore("app_settings")` **就是** `getSharedPreferences("app_settings")`
 *    —— 同一个文件、同一个实例 ⇒ 老用户设过的每一项一个不丢，`AppearanceSettings` 那个
 *    `OnSharedPreferenceChangeListener` 照旧会被叫醒；
 *  - 桌面：宿主传 `secureKeyValueStore("app_settings")`（一份 0600 的 Properties 文件）
 *    ⇒ **真落盘**，与 `:data` 的凭据 / cookie 同一套落盘口径。
 *
 * 存储由宿主构造后传进来（不是这里写死 `keyValueStore(name)`），正是为了让两端各接自己那份。
 *
 * ## 只搬「设置页真会读写」的那几项
 *
 * `app_settings` 这个文件里还有协议版本、更新公告已读、灰度 ID、站点使用记录等 —— 它们**不是**
 * 设置页的项（分别属于首启、更新、首页统计），继续留在 `:app` 的 `CredentialStore` 里。
 * 两个对象写同一个文件是安全的：Android 上 `getSharedPreferences` 按名字返回**同一个实例**。
 */
class SettingsStore(private val store: KeyValueStore = keyValueStore(APP_SETTINGS_FILE)) {

    /**
     * 界面风格：[NAV_STYLE_FLOATING] 玻璃（**默认**），[NAV_STYLE_CLASSIC] 经典。
     *
     * 键名和取值沿用原来的「底栏风格」：以前选过悬浮胶囊的人自动用上玻璃，
     * 主动选过经典（本地已存 "classic"）的人保持不变。选经典时底栏回到经典样式，
     * 所有玻璃点一起退回不透明——经典同时就是「性能模式」。
     */
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

    /**
     * 默认启动 tab。默认日程（[TAB_COURSES]）：课表是用户最常用的核心功能，
     * 直接进日程减少一次点击。
     */
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

    /**
     * 场馆滑块验证码自动识别开关。默认开启：预约时先自动识别，置信度不足仍回退到手动滑块。
     */
    var venueAutoSolveCaptchaEnabled: Boolean
        get() = store.getBoolean(KEY_VENUE_AUTO_SOLVE_CAPTCHA, true)
        set(value) = store.putBoolean(KEY_VENUE_AUTO_SOLVE_CAPTCHA, value)

    /**
     * 课表格子上叠考勤角标。**默认开**。
     *
     * 可以放心默认打开：取数走 ensureSite(silent = true)，遇到二次验证直接放弃 ——
     * 不弹窗、不发短信，结果只是不显示角标。
     */
    var scheduleAttendanceBadge: Boolean
        get() = store.getBoolean(KEY_SCHEDULE_ATTENDANCE_BADGE, true)
        set(value) = store.putBoolean(KEY_SCHEDULE_ATTENDANCE_BADGE, value)

    /**
     * 当前学期课表从哪个系统拉，取值见 `ScheduleSource.key`（`:app` 的 `schedule/ScheduleSource.kt`）。
     */
    var scheduleSource: String
        get() = store.getString(KEY_SCHEDULE_SOURCE) ?: SCHEDULE_SOURCE_JWXT
        set(value) = store.putString(KEY_SCHEDULE_SOURCE, value)

    companion object {
        /** 非敏感的应用设置（外观、协议、更新……），普通 SharedPreferences。 */
        const val APP_SETTINGS_FILE = "app_settings"

        // ── 设置页键（与 `:app` 的 `CredentialStore` 逐字相同）──
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

        // ── 设置值常量（原样从 `CredentialStore` 搬来，`:app` 那边留同名转发）──
        const val SCHEDULE_SOURCE_JWXT = "jwxt"

        /** 值沿用旧版考勤（bkkq）时代的写法：已经写进用户设置，不能改。 */
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
