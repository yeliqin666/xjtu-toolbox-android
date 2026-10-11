package com.xjtu.toolbox.data

import android.content.Context
import android.content.SharedPreferences
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.update.AppUpdater
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.settings.SettingsStore

/**
 * 凭据安全存储（使用 EncryptedSharedPreferences）
 * 密码使用 AES-256-GCM 加密存储，密钥由 Android Keystore 管理
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { SecurePrefs.open(appContext, FILE_NAME) }

    // 旧单账号凭据：现在存在 AccountStore，这里只保留读取供 AccountMigration 迁移用

    fun load(): Pair<String, String>? {
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        if (username.isEmpty() || password.isEmpty()) return null
        return username to password
    }

    /** 迁移完成后删掉旧的密码 / 指纹 / 公钥副本，避免与 AccountStore 各存一份。昵称不动。 */
    fun clearLegacyCredentials() {
        if (!prefs.contains(KEY_PASSWORD) && !prefs.contains(KEY_USERNAME)) return
        prefs.edit()
            .remove(KEY_USERNAME).remove(KEY_PASSWORD)
            .remove(KEY_FP_VISITOR_ID).remove(KEY_RSA_PUBLIC_KEY).remove(KEY_RSA_KEY_TIME)
            .apply()
    }

    fun loadFpVisitorId(): String? = prefs.getString(KEY_FP_VISITOR_ID, null)

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

    // ── 用户协议 & 公告（非敏感，使用普通 SharedPreferences） ──

    private val appPrefs: SharedPreferences =
        appContext.getSharedPreferences(APP_SETTINGS_FILE, Context.MODE_PRIVATE)

    /** 用户是否已同意当前版本的用户协议 */
    fun isEulaAccepted(): Boolean =
        appPrefs.getInt(KEY_EULA_VERSION, 0) >= CURRENT_EULA_VERSION

    /** 标记用户已同意用户协议 */
    fun acceptEula() {
        appPrefs.edit().putInt(KEY_EULA_VERSION, CURRENT_EULA_VERSION).apply()
    }

    /** 用户是否已看过指定版本的更新公告（旧 API，仅 AutoUpdate 弹窗仍在用） */
    fun isUpdateNoticeSeen(versionName: String): Boolean =
        appPrefs.getBoolean("update_notice_$versionName", false)

    /** 标记用户已看过更新公告（旧 API） */
    fun markUpdateNoticeSeen(versionName: String) {
        appPrefs.edit().putBoolean("update_notice_$versionName", true).apply()
    }

    /**
     * 用户已见过的最高 What's New 版本号。
     * 用于堆叠展示「上次已见 → 当前」之间所有版本的 changelog。
     */
    var lastSeenChangelogVersion: String?
        get() = appPrefs.getString("last_seen_changelog_version", null)
        set(value) {
            appPrefs.edit().putString("last_seen_changelog_version", value).apply()
        }

    /** 上一次启动时记录的 app 版本，用于判断是否刚完成升级。 */
    var lastRunVersion: String?
        get() = appPrefs.getString(KEY_LAST_RUN_VERSION, null)
        set(value) {
            appPrefs.edit().putString(KEY_LAST_RUN_VERSION, value).apply()
        }

    /** 最近一次自动检查更新时间；手动检查不受这个冷却限制。 */
    var lastAutoUpdateCheckAt: Long
        get() = appPrefs.getLong(KEY_LAST_AUTO_UPDATE_CHECK_AT, 0L)
        set(value) {
            appPrefs.edit().putLong(KEY_LAST_AUTO_UPDATE_CHECK_AT, value).apply()
        }

    // ── 设置页持久化（搬进 `:core` 的 `SettingsStore`，这里只留转发）──
    //
    // 这十项连同它们的**键名 / 默认值 / 取值常量**都搬到了 `:core/settings/SettingsStore.kt`：
    // 共享设置屏（`:core` 的 `SettingsScreen`）与 `:app` 必须读同一份实现，否则「键名漂了」
    // 这种错只有在用户重装后才看得见。`:app` 这一侧保留**同名属性转发** ⇒ 其余 40 处调用点
    // 一行未改（C1：只搬走 + 引用改名）。
    //
    // 存储仍是同一个 `app_settings` 文件：`:core` 的键值缝在 Android 上就是
    // `getSharedPreferences("app_settings", MODE_PRIVATE)`（同一个实例）⇒ 老用户设过的
    // 每一项一个不丢，`AppearanceSettings` 那个 `OnSharedPreferenceChangeListener` 照旧会被叫醒。
    private val settings by lazy { SettingsStore(keyValueStore(SettingsStore.APP_SETTINGS_FILE)) }


    /**
     * 界面风格：[NAV_STYLE_FLOATING] 玻璃（**默认**），[NAV_STYLE_CLASSIC] 经典。
     *
     * 键名和取值沿用原来的「底栏风格」：以前选过悬浮胶囊的人自动用上玻璃，
     * 主动选过经典（本地已存 "classic"）的人保持不变。选经典时底栏回到经典样式，
     * 所有玻璃点一起退回不透明——经典同时就是「性能模式」。
     */
    var navBarStyle: String
        get() = settings.navBarStyle
        set(value) { settings.navBarStyle = value }

    /**
     * 课表格子上叠考勤角标。**默认开**。
     *
     * 可以放心默认打开：取数走 ensureSite(silent = true)，遇到二次验证直接放弃——
     * 不弹窗、不发短信，结果只是不显示角标。加载是异步旁路的，考勤站点再慢也拖不住课表。
     * 用户主动关过的（本地已存 false）不受这次默认值改动影响。
     */
    var scheduleAttendanceBadge: Boolean
        get() = settings.scheduleAttendanceBadge
        set(value) { settings.scheduleAttendanceBadge = value }

    /**
     * 当前学期课表从哪个系统拉，取值见 `ScheduleSource.key`。
     *
     * 只影响**当前学期**：历史学期只有教务查得到，任何设置下都走教务，
     * 非教务源取不到时也自动退回教务。详见 `ScheduleSourceRouter`。
     */
    var scheduleSource: String
        get() = settings.scheduleSource
        set(value) { settings.scheduleSource = value }

    var darkMode: String
        get() = settings.darkMode
        set(value) { settings.darkMode = value }

    /** 跟随系统壁纸 / 调色盘动态取色（Material You / Monet）。默认关闭。 */
    var dynamicColor: Boolean
        get() = settings.dynamicColor
        set(value) { settings.dynamicColor = value }

    // 默认启动 tab 改为日程（COURSES）：课表是用户最常用的核心功能，
    // 直接进日程减少一次点击。设置页可改回首页或其他。
    var defaultTab: String
        get() = settings.defaultTab
        set(value) { settings.defaultTab = value }

    var networkMode: String
        get() = settings.networkMode
        set(value) { settings.networkMode = value }

    var updateChannel: String
        get() = AppUpdater.normalizeChannel(appPrefs.getString(KEY_UPDATE_CHANNEL, CHANNEL_GITEE))
        set(value) { appPrefs.edit().putString(KEY_UPDATE_CHANNEL, AppUpdater.normalizeChannel(value)).apply() }

    /** 是否接收预览版更新。预览包自身默认开（否则装了预览包的人收不到下一个预览）。 */
    var receivePreviewUpdates: Boolean
        get() = appPrefs.getBoolean(KEY_RECEIVE_PREVIEW, BuildConfig.IS_PREVIEW)
        set(value) { appPrefs.edit().putBoolean(KEY_RECEIVE_PREVIEW, value).apply() }

    /**
     * 灰度分桶用的本机随机 ID。只存在本地，不上传、不与账号关联。
     * 首次读取时生成；清数据/重装会重新生成（等于重新抽签，可以接受）。
     */
    val rolloutId: String
        // 各处都是现场 new 的 CredentialStore，锁实例没用，锁类。
        get() = synchronized(CredentialStore::class.java) {
            appPrefs.getString(KEY_ROLLOUT_ID, null) ?: java.util.UUID.randomUUID().toString()
                .also { appPrefs.edit().putString(KEY_ROLLOUT_ID, it).commit() }
        }

    var accountType: AccountType
        get() = AccountType.fromKey(appPrefs.getString(KEY_ACCOUNT_TYPE, AccountType.UNDERGRADUATE.key))
        set(value) { appPrefs.edit().putString(KEY_ACCOUNT_TYPE, value.key).apply() }

    var hasReadEmptyRoomCdnTip: Boolean
        get() = appPrefs.getBoolean(KEY_EMPTY_ROOM_CDN_TIP, false)
        set(value) { appPrefs.edit().putBoolean(KEY_EMPTY_ROOM_CDN_TIP, value).apply() }

    var homeTheme: String
        get() = settings.homeTheme
        set(value) { settings.homeTheme = value }

    /** 最近 + 常用的前 [n] 个站点（见 [SiteUsage]），会话预热按它挑。 */
    fun topSites(n: Int): List<String> = SiteUsage.top(siteUsage(), n, System.currentTimeMillis())

    /** 记录一次打开。 */
    fun recordSiteUse(siteKey: String) {
        if (siteKey.isBlank()) return
        val next = SiteUsage.record(siteUsage(), siteKey, System.currentTimeMillis())
        appPrefs.edit().putString(KEY_SITE_USAGE, SiteUsage.encode(next)).remove(KEY_RECENT_SITES).apply()
    }

    private fun siteUsage(): Map<String, SiteUsage.Entry> {
        appPrefs.getString(KEY_SITE_USAGE, null)?.let { return SiteUsage.decode(it) }
        // 旧版只记了打开顺序，越新的给越高的起始分
        val legacy = appPrefs.getString(KEY_RECENT_SITES, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val now = System.currentTimeMillis()
        return legacy.withIndex().associate { (i, key) -> key to SiteUsage.Entry((legacy.size - i).toDouble(), now) }
    }

    var showQuickActions: Boolean
        get() = settings.showQuickActions
        set(value) { settings.showQuickActions = value }

    /**
     * 场馆滑块验证码自动识别开关。
     *
     * 默认开启：预约时先自动识别，置信度不足仍回退到手动滑块。
     */
    var venueAutoSolveCaptchaEnabled: Boolean
        get() = settings.venueAutoSolveCaptchaEnabled
        set(value) { settings.venueAutoSolveCaptchaEnabled = value }

    companion object {
        internal const val FILE_NAME = "xjtu_credentials"
        /** 非敏感的应用设置（外观、协议、更新……），普通 SharedPreferences。 */
        const val APP_SETTINGS_FILE = "app_settings"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_FP_VISITOR_ID = "fp_visitor_id"
        private const val KEY_RSA_PUBLIC_KEY = "rsa_public_key"
        private const val KEY_RSA_KEY_TIME = "rsa_key_time"
        private const val KEY_NICKNAME = "cached_nickname"
        private const val KEY_EULA_VERSION = "eula_accepted_version"
        /**
         * 用户协议版本号，更新协议内容时递增。
         * 4：新增崩溃日志匿名上报的说明（[com.xjtu.toolbox.error.CrashReporter]）。
         */
        const val CURRENT_EULA_VERSION = 4

        // ── 设置页键（搬到 `:core` 的 `SettingsStore` 里了，这里不再各存一份）──
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private const val KEY_RECEIVE_PREVIEW = "receive_preview_updates"
        private const val KEY_ROLLOUT_ID = "rollout_id"
        private const val KEY_LAST_RUN_VERSION = "last_run_version"
        private const val KEY_LAST_AUTO_UPDATE_CHECK_AT = "last_auto_update_check_at"
        private const val KEY_ACCOUNT_TYPE = "account_type"
        private const val KEY_EMPTY_ROOM_CDN_TIP = "empty_room_cdn_tip"
        /** 旧版「最近打开」顺序，只用于迁移到 [KEY_SITE_USAGE]。 */
        private const val KEY_RECENT_SITES = "recent_site_keys"
        private const val KEY_SITE_USAGE = "site_usage"

        // ── 设置值常量（值住在 `:core` 的 `SettingsStore`，这里保留同名转发）──
        //
        // `:app` 里 40 处 `CredentialStore.XXX` 引用点因此一行未改（C1：只搬走 + 引用改名）；
        // 值只存一份 ⇒ 不会出现「:app 改了、:core 没改」那种漂。
        //
        // 两项仍然只有这一份定义（它们本来就不在设置屏里）：`SCHEDULE_SOURCE_ATTENDANCE`
        // 那条「值沿用旧版考勤」的注释随之搬进 `SettingsStore`。
        const val SCHEDULE_SOURCE_JWXT = SettingsStore.SCHEDULE_SOURCE_JWXT
        const val SCHEDULE_SOURCE_ATTENDANCE = SettingsStore.SCHEDULE_SOURCE_ATTENDANCE
        const val NAV_STYLE_FLOATING = SettingsStore.NAV_STYLE_FLOATING
        const val NAV_STYLE_CLASSIC = SettingsStore.NAV_STYLE_CLASSIC
        const val DARK_MODE_SYSTEM = SettingsStore.DARK_MODE_SYSTEM
        const val DARK_MODE_LIGHT = SettingsStore.DARK_MODE_LIGHT
        const val DARK_MODE_DARK = SettingsStore.DARK_MODE_DARK
        const val THEME_CARD = SettingsStore.THEME_CARD
        const val THEME_ICON = SettingsStore.THEME_ICON
        const val TAB_HOME = SettingsStore.TAB_HOME
        const val TAB_COURSES = SettingsStore.TAB_COURSES
        const val TAB_PIDAI = SettingsStore.TAB_PIDAI
        const val TAB_TOOLS = SettingsStore.TAB_TOOLS
        const val TAB_PROFILE = SettingsStore.TAB_PROFILE
        const val NETWORK_AUTO = SettingsStore.NETWORK_AUTO
        const val NETWORK_DIRECT = SettingsStore.NETWORK_DIRECT
        const val NETWORK_VPN = SettingsStore.NETWORK_VPN
        const val CHANNEL_GITEE = AppUpdater.CHANNEL_GITEE
        const val CHANNEL_GITHUB = AppUpdater.CHANNEL_GITHUB
    }
}
