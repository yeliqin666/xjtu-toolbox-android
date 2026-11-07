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
     * 首页功能区用哪一套：`classic` = 经典分类卡（回落），`cards` = 卡片组（合并了原「小组件」那套
     * 能力的唯一现代实现）。
     *
     * 老存档里的 `widgets`（dev 上那套小组件）**没有独立选项了**：它已经并进卡片组，读到就当成
     * `cards`，升级后用户看到的是合并后的新实现；没设置过的也按 `cards` 走。
     */
    var homeLayout: String
        get() = when (store.getString("home_layout")) {
            HOME_LAYOUT_CLASSIC -> HOME_LAYOUT_CLASSIC
            else -> HOME_LAYOUT_CARDS
        }
        set(value) = store.putString("home_layout", value.takeIf { it in HOME_LAYOUTS } ?: HOME_LAYOUT_CARDS)

    /**
     * **历史键**：原「小组件」那套布局的分组与尺寸（每组一行 `#id\t标题\t颜色`，下面跟本组的
     * `路由id:尺寸`）；null = 没编辑过。
     *
     * 小组件那套实现已经并进卡片组（见 `HOME_LAYOUT_CARDS`），卡片组用的是 `HomeLayoutStore` 里
     * 那份整页快照，所以这个键现在**只读不写**，留着是为了不删用户硬盘上的旧数据、将来要迁移
     * 旧布局时还有得读。
     */
    var homeWidgets: List<String>?
        get() = store.getString("home_widgets")?.split('\n')?.filter { it.isNotBlank() }
        set(value) = store.putString("home_widgets", value?.joinToString("\n"))

    /**
     * 小组件用液态玻璃（折射 + 高光，较耗电）；只在图片背景上真能看出效果，纯色 / 渐变与
     * 「糊到没有细节的图片」一律退成薄色卡片（见 `SettingsStore.HOME_WALLPAPER_BLUR_HIDES_GLASS`）。
     *
     * **默认开**（2026-11 按维护者要求改）：这套玻璃是首页现在的默认观感，觉得卡 / 费电的人
     * 自己去主页设置里关，或者只关折射。
     */
    var homeLiquidGlass: Boolean
        get() = store.getBoolean("home_liquid_card", true)
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

    /**
     * 首页背景图的模糊半径，取值范围同首页大卡的自定义背景。
     *
     * 糊到 [HOME_WALLPAPER_BLUR_HIDES_GLASS] 及以上时，卡片上的模糊与折射就看不出差别了，
     * 那时卡片退成薄色玻璃（见那个常量的说明）——这里只负责存值，判定在渲染与设置面板。
     *
     * 原来还有一条「亮度」（键 `home_wallpaper_brightness`）：那**功能整个删掉了**（2026-11），
     * 键不再读也不再写，壁纸按原图铺。自定义壁纸的明暗交给「背景不透明度」那条滑条调
     * （往下拨就把主题底色盖回去）——亮度与它调的是同一件事，两条一起拨只会互相抵消。
     */
    var homeWallpaperBlur: Int
        get() = store.getInt("home_wallpaper_blur", HERO_IMAGE_BLUR_MIN).coerceIn(HERO_IMAGE_BLUR_MIN, HERO_IMAGE_BLUR_MAX)
        set(value) = store.putInt("home_wallpaper_blur", value.coerceIn(HERO_IMAGE_BLUR_MIN, HERO_IMAGE_BLUR_MAX))

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
        const val HOME_LAYOUT_CARDS = "cards"

        /**
         * 历史值：dev 上那套「小组件」布局。它已经并进 [HOME_LAYOUT_CARDS]，不再是可选项，
         * 只在读老存档时被认出来并映射过去。
         */
        const val HOME_LAYOUT_WIDGETS = "widgets"

        /** 合法的首页布局取值（写盘校验用）；`widgets` 只进不出。 */
        val HOME_LAYOUTS = setOf(HOME_LAYOUT_CLASSIC, HOME_LAYOUT_CARDS)
        const val WIDGET_MATERIAL_PAPER = "paper"

        const val HERO_IMAGE_BLUR_MIN = 0
        const val HERO_IMAGE_BLUR_MAX = 10

        /**
         * 背景图糊到这个半径（dp）及以上，卡片上的**模糊与折射就都看不出差别**了
         * （2026-11 维护者给的阈值）。
         *
         * 模糊是真高斯，半径翻倍等于细节对折再对折：到 5dp 时壁纸已经是一片色块，卡片再采它一遍
         * 糊出来的和「薄色底」几乎分不出，折射折的也是同一片色块——但这两层是全页最贵的
         * （每张卡一次离屏位移采样）。所以此时按「纯色 / 渐变背景」那套处理：
         * 不录卡片那层、不模糊、不折射，只留活力 / 高光 / 轮廓 / 投影。
         *
         * 注意这**不是**把用户的折射开关改成关：开关照原样显示、只是置灰（见主页设置），
         * 换回不糊的图片或把模糊调小就立刻恢复。
         */
        const val HOME_WALLPAPER_BLUR_HIDES_GLASS = 5
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
