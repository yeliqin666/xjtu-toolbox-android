package com.xjtu.toolbox.util

import android.content.Context
import android.content.SharedPreferences
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.auth.AccountType

/**
 * 凭据安全存储（使用 EncryptedSharedPreferences）
 * 密码使用 AES-256-GCM 加密存储，密钥由 Android Keystore 管理
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { SecurePrefs.open(appContext, FILE_NAME) }

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

    // ── 设置页持久化（普通 SharedPreferences，非敏感） ──

    /** 获取 app_settings SharedPreferences，供 Compose 端直接读取/写入 */
    fun getAppPrefs(): SharedPreferences = appPrefs

    var navBarStyle: String
        get() = appPrefs.getString(KEY_NAV_BAR_STYLE, NAV_STYLE_CLASSIC) ?: NAV_STYLE_CLASSIC
        set(value) { appPrefs.edit().putString(KEY_NAV_BAR_STYLE, value).apply() }

    /**
     * 课表格子上叠考勤角标。**默认开**。
     *
     * 可以放心默认打开：取数走 ensureSite(silent = true)，遇到二次验证直接放弃——
     * 不弹窗、不发短信，结果只是不显示角标。加载是异步旁路的，考勤站点再慢也拖不住课表。
     * 用户主动关过的（本地已存 false）不受这次默认值改动影响。
     */
    var scheduleAttendanceBadge: Boolean
        get() = appPrefs.getBoolean(KEY_SCHEDULE_ATTENDANCE_BADGE, true)
        set(value) { appPrefs.edit().putBoolean(KEY_SCHEDULE_ATTENDANCE_BADGE, value).apply() }

    /**
     * 当前学期课表从哪个系统拉，取值见 `ScheduleSource.key`。
     *
     * 只影响**当前学期**：历史学期只有教务查得到，任何设置下都走教务，
     * 非教务源取不到时也自动退回教务。详见 `ScheduleSourceRouter`。
     */
    var scheduleSource: String
        get() = appPrefs.getString(KEY_SCHEDULE_SOURCE, null) ?: SCHEDULE_SOURCE_JWAPP
        set(value) { appPrefs.edit().putString(KEY_SCHEDULE_SOURCE, value).apply() }

    var darkMode: String
        get() = appPrefs.getString(KEY_DARK_MODE, DARK_MODE_SYSTEM) ?: DARK_MODE_SYSTEM
        set(value) { appPrefs.edit().putString(KEY_DARK_MODE, value).apply() }

    /** 跟随系统壁纸 / 调色盘动态取色（Material You / Monet）。默认关闭。 */
    var dynamicColor: Boolean
        get() = appPrefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) { appPrefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply() }

    // 默认启动 tab 改为日程（COURSES）：课表是用户最常用的核心功能，
    // 直接进日程减少一次点击。设置页可改回首页或其他。
    var defaultTab: String
        get() = appPrefs.getString(KEY_DEFAULT_TAB, TAB_COURSES) ?: TAB_COURSES
        set(value) { appPrefs.edit().putString(KEY_DEFAULT_TAB, value).apply() }

    var networkMode: String
        get() = appPrefs.getString(KEY_NETWORK_MODE, NETWORK_AUTO) ?: NETWORK_AUTO
        set(value) { appPrefs.edit().putString(KEY_NETWORK_MODE, value).apply() }

    /** 旧开关，启动路径不再读取。保留以免旧安装读到 false 还要迁一次。 */
    var autoCheckUpdate: Boolean
        get() = appPrefs.getBoolean(KEY_AUTO_CHECK_UPDATE, true)
        set(value) { appPrefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATE, value).apply() }

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
        get() = appPrefs.getString(KEY_HOME_THEME, THEME_CARD) ?: THEME_CARD
        set(value) { appPrefs.edit().putString(KEY_HOME_THEME, value).apply() }

    /**
     * 最近打开过的子系统 siteKey（最新在前，最多 [MAX_RECENT_SITES] 个）。
     * 冷启动后据此做免密 SSO 预热——用户大概率还会进这几个。
     */
    var recentSiteKeys: List<String>
        get() = appPrefs.getString(KEY_RECENT_SITES, null)
            ?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) {
            appPrefs.edit()
                .putString(KEY_RECENT_SITES, value.take(MAX_RECENT_SITES).joinToString(","))
                .apply()
        }

    /** 记录一次打开：置顶去重后截断。 */
    fun recordRecentSite(siteKey: String) {
        if (siteKey.isBlank()) return
        val cur = recentSiteKeys
        if (cur.firstOrNull() == siteKey) return
        recentSiteKeys = (listOf(siteKey) + cur.filter { it != siteKey })
    }

    var showQuickActions: Boolean
        get() = appPrefs.getBoolean(KEY_SHOW_QUICK_ACTIONS, true)
        set(value) { appPrefs.edit().putBoolean(KEY_SHOW_QUICK_ACTIONS, value).apply() }

    /**
     * 场馆滑块验证码自动识别开关。
     *
     * 默认开启：预约时先自动识别，置信度不足仍回退到手动滑块。
     */
    var venueAutoSolveCaptchaEnabled: Boolean
        get() = appPrefs.getBoolean(KEY_VENUE_AUTO_SOLVE_CAPTCHA, true)
        set(value) { appPrefs.edit().putBoolean(KEY_VENUE_AUTO_SOLVE_CAPTCHA, value).apply() }

    companion object {
        internal const val FILE_NAME = "xjtu_credentials"
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

        // ── 设置页键 ──
        private const val KEY_NAV_BAR_STYLE = "nav_bar_style"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_DEFAULT_TAB = "default_tab"
        private const val KEY_NETWORK_MODE = "network_mode"
        private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private const val KEY_RECEIVE_PREVIEW = "receive_preview_updates"
        private const val KEY_ROLLOUT_ID = "rollout_id"
        private const val KEY_LAST_RUN_VERSION = "last_run_version"
        private const val KEY_LAST_AUTO_UPDATE_CHECK_AT = "last_auto_update_check_at"
        private const val KEY_ACCOUNT_TYPE = "account_type"
        private const val KEY_EMPTY_ROOM_CDN_TIP = "empty_room_cdn_tip"
        private const val KEY_HOME_THEME = "home_theme"
        private const val KEY_RECENT_SITES = "recent_site_keys"
        private const val MAX_RECENT_SITES = 4
        private const val KEY_SHOW_QUICK_ACTIONS = "show_quick_actions"
        private const val KEY_VENUE_AUTO_SOLVE_CAPTCHA = "venue_auto_solve_captcha"
        private const val KEY_SCHEDULE_ATTENDANCE_BADGE = "schedule_attendance_badge"
        private const val KEY_SCHEDULE_SOURCE = "schedule_source"

        // ── 设置值常量 ──
        const val SCHEDULE_SOURCE_JWXT = "jwxt"
        const val SCHEDULE_SOURCE_JWAPP = "jwapp"
        const val SCHEDULE_SOURCE_BKKQ = "bkkq"
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
        const val CHANNEL_GITEE = AppUpdater.CHANNEL_GITEE
        const val CHANNEL_GITHUB = AppUpdater.CHANNEL_GITHUB
    }
}
