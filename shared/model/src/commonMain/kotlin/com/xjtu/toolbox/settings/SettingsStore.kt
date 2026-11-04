package com.xjtu.toolbox.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore

/** 首页功能区设置落盘后的进程内信号：设置页与首页各读一份，改完靠它一起重读。 */
object HomeLayoutRevision {
    var current by mutableIntStateOf(0)
        private set

    fun bump() {
        current++
    }
}

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

    /** 自定义主题色（浅色模式那一档）；空串表示未选择。保持独立，切换取色来源时保留上次选择。 */
    var customColorHex: String
        get() = normalizeThemeColor(store.getString("custom_color_hex").orEmpty())
        set(value) = store.putString("custom_color_hex", normalizeThemeColor(value))

    var customColorEnabled: Boolean
        get() = store.getBoolean("custom_color_enabled", false)
        set(value) = store.putBoolean("custom_color_enabled", value)

    /**
     * 浅色 / 深色各存一份自选主题色。没单独选过的那一档回退到旧键 [customColorHex]：
     * 老版本只存了一个色，升级后两档都该还是那个色，而不是深色突然跑回默认配色。
     */
    fun customColorHex(dark: Boolean): String =
        normalizeThemeColor(store.getString(if (dark) "custom_color_hex_dark" else "custom_color_hex_light").orEmpty())
            .ifEmpty { customColorHex }

    fun setCustomColorHex(dark: Boolean, value: String) =
        store.putString(if (dark) "custom_color_hex_dark" else "custom_color_hex_light", normalizeThemeColor(value))

    /** 主题实际采用的自选色（按当前明暗取那一档）：未启用自定义时为空串。 */
    fun themeSeedHex(dark: Boolean): String = if (customColorEnabled) customColorHex(dark) else ""

    /** 默认启动 tab。不在底栏上时由读的一方回首页（`BottomBarTabs.defaultTab`）。 */
    var defaultTab: String
        get() = store.getString(KEY_DEFAULT_TAB) ?: TAB_COURSES
        set(value) = store.putString(KEY_DEFAULT_TAB, value)

    /** 底栏中间那几格的路由 id，逗号分隔；null = 没改过。解析见 `BottomBarTabs`。 */
    var bottomBar: String?
        get() = store.getString(KEY_BOTTOM_BAR)
        set(value) = store.putString(KEY_BOTTOM_BAR, value)

    var networkMode: String
        get() = store.getString(KEY_NETWORK_MODE) ?: NETWORK_AUTO
        set(value) = store.putString(KEY_NETWORK_MODE, value)

    var showQuickActions: Boolean
        get() = store.getBoolean(KEY_SHOW_QUICK_ACTIONS, true)
        set(value) = store.putBoolean(KEY_SHOW_QUICK_ACTIONS, value)

    /** 用户置顶的常用功能（路由 id，按显示顺序）；null = 没编辑过，按使用频率挑。 */
    var pinnedQuickRoutes: List<String>?
        get() = store.getString("pinned_quick_routes")?.split('\n')?.filter { it.isNotBlank() }
        set(value) = store.putString("pinned_quick_routes", value?.joinToString("\n"))

    /** 首页头卡外观；auto 保留随时间、主题和日程变化的默认效果。 */
    var heroBackground: String
        get() = store.getString("hero_background")?.takeIf { it in HERO_BACKGROUNDS } ?: "liquid"
        set(value) = store.putString("hero_background", value.takeIf { it in HERO_BACKGROUNDS } ?: "liquid")

    var heroPalette: String
        get() = store.getString("hero_palette")?.takeIf { it in HERO_PALETTES } ?: "auto"
        set(value) = store.putString("hero_palette", value.takeIf { it in HERO_PALETTES } ?: "auto")

    var heroColorHex: String
        get() = normalizeThemeColor(store.getString("hero_color_hex").orEmpty()).ifEmpty { "#2F7CFF" }
        set(value) = store.putString("hero_color_hex", normalizeThemeColor(value))

    /** 浅色 / 深色各存一份自选色；没单独选过的那一档回退到旧键。 */
    fun heroColorHex(dark: Boolean): String =
        normalizeThemeColor(store.getString(if (dark) "hero_color_hex_dark" else "hero_color_hex_light").orEmpty())
            .ifEmpty { heroColorHex }

    fun setHeroColorHex(dark: Boolean, value: String) =
        store.putString(if (dark) "hero_color_hex_dark" else "hero_color_hex_light", normalizeThemeColor(value))

    var heroMotion: String
        get() = store.getString("hero_motion")?.takeIf { it in HERO_MOTIONS } ?: "auto"
        set(value) = store.putString("hero_motion", value.takeIf { it in HERO_MOTIONS } ?: "auto")

    /** -1 每天自动换形状，其余值固定形状。 */
    var heroSeed: Int
        get() = store.getInt("hero_seed", -1).coerceIn(-1, 9999)
        set(value) = store.putInt("hero_seed", value.coerceIn(-1, 9999))

    /** 自定义背景：裁好的图片（WebP）的 Base64；空串 = 还没选图。 */
    var heroImageData: String
        get() = store.getString("hero_image_data").orEmpty()
        set(value) = store.putString("hero_image_data", value.ifEmpty { null })

    /** 自定义背景的模糊半径（dp），0 = 不模糊。 */
    var heroImageBlur: Int
        get() = store.getInt("hero_image_blur", HERO_IMAGE_BLUR_MIN)
            .coerceIn(HERO_IMAGE_BLUR_MIN, HERO_IMAGE_BLUR_MAX)
        set(value) = store.putInt("hero_image_blur", value.coerceIn(HERO_IMAGE_BLUR_MIN, HERO_IMAGE_BLUR_MAX))

    /** 自定义背景的亮度（百分比，20..100）；100 = 原图，越小越暗。 */
    var heroImageBrightness: Int
        get() = store.getInt("hero_image_brightness", HERO_IMAGE_BRIGHTNESS_MAX)
            .coerceIn(HERO_IMAGE_BRIGHTNESS_MIN, HERO_IMAGE_BRIGHTNESS_MAX)
        set(value) = store.putInt("hero_image_brightness", value.coerceIn(HERO_IMAGE_BRIGHTNESS_MIN, HERO_IMAGE_BRIGHTNESS_MAX))

    /**
     * 首页功能区用哪一套：classic = 经典分类卡，widgets = 可调尺寸的小组件，cards = 卡片组。
     *
     * 前两套是 dev 上已经发过版的实现（`HomeLegacyScreen`），`cards` 是本分支新写的
     * （`HomeCardGroupScreen`）；三套并存，由 `HomeScreen` 分派。老存档里没有这个键时仍是
     * widgets——升级后首页不会自己变样，想换的人自己在主页设置里选。
     */
    var homeLayout: String
        get() = store.getString("home_layout")?.takeIf {
            it == HOME_LAYOUT_WIDGETS || it == HOME_LAYOUT_CLASSIC || it == HOME_LAYOUT_CARDS
        } ?: HOME_LAYOUT_WIDGETS
        set(value) = store.putString("home_layout", value.takeIf { it in HOME_LAYOUTS } ?: HOME_LAYOUT_WIDGETS)

    /** 小组件的分组、排列与尺寸（格式见 `HomeWidgetLayout.encodeGroups`）；null = 没编辑过，用默认布局。 */
    var homeWidgets: List<String>?
        get() = store.getString("home_widgets")?.split('\n')?.filter { it.isNotBlank() }
        set(value) = store.putString("home_widgets", value?.joinToString("\n"))

    /** 小组件用液态玻璃（折射 + 高光，较耗电）；只在渐变与图片背景上生效，纯色背景一律是实心卡片。 */
    var homeLiquidGlass: Boolean
        get() = store.getBoolean("home_liquid_card", false)
        set(value) = store.putBoolean("home_liquid_card", value)

    /** 首页顶栏：true = 扫码、搜索、消息统一靠右（默认），false = 扫码单独靠左、标题居中。 */
    var homeTopActionsRight: Boolean
        get() = store.getBoolean("home_top_actions_right", true)
        set(value) = store.putBoolean("home_top_actions_right", value)

    var homeBackground: String
        get() = store.getString("home_background") ?: when {
            store.getString("home_widget_material") == WIDGET_MATERIAL_PAPER -> "solid"
            homeWallpaperImage.isNotEmpty() -> "image"
            else -> "gradient"
        }
        set(value) = store.putString("home_background", value)

    /** 首页自定义背景，WebP Base64。 */
    var homeWallpaperImage: String
        get() = store.getString("home_wallpaper_image").orEmpty()
        set(value) = store.putString("home_wallpaper_image", value.ifEmpty { null })

    /** 首页背景图的模糊与亮度，取值范围同首页大卡的自定义背景。 */
    var homeWallpaperBlur: Int
        get() = store.getInt("home_wallpaper_blur", HERO_IMAGE_BLUR_MIN).coerceIn(HERO_IMAGE_BLUR_MIN, HERO_IMAGE_BLUR_MAX)
        set(value) = store.putInt("home_wallpaper_blur", value.coerceIn(HERO_IMAGE_BLUR_MIN, HERO_IMAGE_BLUR_MAX))

    var homeWallpaperBrightness: Int
        get() = store.getInt("home_wallpaper_brightness", HERO_IMAGE_BRIGHTNESS_MAX)
            .coerceIn(HERO_IMAGE_BRIGHTNESS_MIN, HERO_IMAGE_BRIGHTNESS_MAX)
        set(value) = store.putInt("home_wallpaper_brightness", value.coerceIn(HERO_IMAGE_BRIGHTNESS_MIN, HERO_IMAGE_BRIGHTNESS_MAX))

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
        private val HERO_BACKGROUNDS = setOf("liquid", "solid", "forest", "pixel", "custom")
        private val HERO_PALETTES = setOf("auto", "midnight", "orchid", "blossom", "lagoon", "honey", "custom")
        private val HERO_MOTIONS = setOf("auto", "gentle", "flowing", "lively", "still")
        fun normalizeThemeColor(value: String): String {
            val hex = value.trim().removePrefix("#")
            if (hex.length !in listOf(3, 6) || !hex.all { it in "0123456789abcdefABCDEF" }) return ""
            return "#" + (if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex).uppercase()
        }
        /** 非敏感的应用设置（外观、协议、更新……），普通 SharedPreferences。 */
        const val APP_SETTINGS_FILE = "app_settings"
        const val HOME_LAYOUT_CLASSIC = "classic"
        const val HOME_LAYOUT_WIDGETS = "widgets"

        /** 本分支新增的第三种首页布局（卡片组）；前两种是 dev 上已发版的那两套。 */
        const val HOME_LAYOUT_CARDS = "cards"

        /** 合法的首页布局取值；落盘/读盘都按它校验，认不出的值退回 widgets。 */
        val HOME_LAYOUTS = setOf(HOME_LAYOUT_CLASSIC, HOME_LAYOUT_WIDGETS, HOME_LAYOUT_CARDS)
        const val WIDGET_MATERIAL_PAPER = "paper"

        const val HERO_IMAGE_BLUR_MIN = 0
        const val HERO_IMAGE_BLUR_MAX = 10
        const val HERO_IMAGE_BRIGHTNESS_MIN = 20
        const val HERO_IMAGE_BRIGHTNESS_MAX = 100

        private const val KEY_NAV_BAR_STYLE = "nav_bar_style"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_DEFAULT_TAB = "default_tab"
        private const val KEY_BOTTOM_BAR = "bottom_bar_tabs"
        private const val KEY_NETWORK_MODE = "network_mode"
        private const val KEY_SHOW_QUICK_ACTIONS = "show_quick_actions"
        private const val KEY_VENUE_AUTO_SOLVE_CAPTCHA = "venue_auto_solve_captcha"
        private const val KEY_SCHEDULE_ATTENDANCE_BADGE = "schedule_attendance_badge"
        private const val KEY_SCHEDULE_SOURCE = "schedule_source"

        const val SCHEDULE_SOURCE_JWXT = "jwxt"

        /** 取值是旧版考勤（bkkq）的写法，已写进用户设置，不能改。 */
        const val SCHEDULE_SOURCE_ATTENDANCE = "bkkq"

        /** 智慧教室平台；和旧版存的值一致，以前选过它的用户升级后直接生效。 */
        const val SCHEDULE_SOURCE_JS = "js"
        const val NAV_STYLE_FLOATING = "floating"
        const val NAV_STYLE_CLASSIC = "classic"
        const val DARK_MODE_SYSTEM = "system"
        const val DARK_MODE_LIGHT = "light"
        const val DARK_MODE_DARK = "dark"
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
