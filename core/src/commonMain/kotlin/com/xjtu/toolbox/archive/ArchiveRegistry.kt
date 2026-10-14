package com.xjtu.toolbox.archive

/**
 * store 名 → 分类表（§2.4 / §3.2 / §5.2 第 7 条 / §8.10）。
 *
 * 这是**唯一**允许在共享层里出现的「store 知识」，S1 建表、S2 用表；新增 store 只改这张表。
 * 同名不得同时是 plain 与 secure（否则两端写同一个 `.properties` 文件会互相覆盖 —— 有守卫测试）。
 *
 * TTL 档：§5.2 第 7 条的档位是**键级**粒度，store 级只作提示（§2.1 `ttlClass` 可缺省），
 * 且规格未决 #10 那几格（`score_cursor` / `schedule_diff` / `course_colors` / `home_usage` /
 * `pidai_appearance` / `schedule_widget_prefs` / `empty_room_cache`）未核对 ⇒ 一律 [Ttl.UNKNOWN]，
 * 不猜（见 `docs/storage-layout-plan.md` §7 第 9 项）。
 */
object ArchiveRegistry {

    enum class Scope { GLOBAL, ACCOUNT }

    enum class Carrier { PLAIN_KV, SECURE_KV, ROOM_ROWS, FILES }

    enum class Ttl { TERM, DAY, WEEK, FOREVER, NONE, UNKNOWN }

    data class Entry(
        val scope: Scope,
        val carrier: Carrier,
        val credential: Boolean = false,
        /** 端内私有，永不进档案（§2.4），也不进 `redacted`。 */
        val excluded: Boolean = false,
        val ttl: Ttl = Ttl.UNKNOWN,
        val rebuildable: Boolean = false,
    )

    /**
     * 凭据族前缀（§8.10 / A8）：`cookies_*` / `sites_*` / `agent_config_secure*`。
     * 用前缀而不是精确名：这些 store 名尾部还有账号 suffix，且 `cookies_`/`sites_` 有
     * `normal`/`webvpn` 两种 mode。`agent_config`（非 secure）不会命中 —— 前缀是 `agent_config_secure`。
     */
    private val credentialPrefixes = listOf("cookies_", "sites_", "agent_config_secure")

    /**
     * 端内私有、永不进档案（§2.4）。凭据族之外的一类：换包判据 / 门禁令牌 / 迁移残留。
     */
    private val excludedNames = setOf("data_cache_meta", "serve", "agent_config_fallback")

    private val table: Map<String, Entry> = buildMap {
        // ── 全局档（无账号后缀） ──
        put("app_settings", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("xjtu_credentials", Entry(Scope.GLOBAL, Carrier.SECURE_KV, credential = true))
        // xjtu_accounts 密文、内含 password/指纹（Account 全文）⇒ 凭据族；账号列表经 manifest.accounts 走，不进档案。
        put("xjtu_accounts", Entry(Scope.GLOBAL, Carrier.SECURE_KV, credential = true))
        // xjtu_cookies：旧单账号迁移残留池，密文。
        put("xjtu_cookies", Entry(Scope.GLOBAL, Carrier.SECURE_KV, credential = true))
        put("github_community", Entry(Scope.GLOBAL, Carrier.SECURE_KV, credential = true))
        // serve 的 access_token 是 serve 进程门禁令牌：既是凭据族（A8）又是端内私有（§2.4）。
        put("serve", Entry(Scope.GLOBAL, Carrier.SECURE_KV, credential = true, excluded = true))

        put("library_favorites", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("venue_favorites", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("empty_room", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("bulletin_store", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("feedback", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("games", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("haptics_prefs", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("jiaocai1_reader_prefs", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("feature_hints", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        // payment_code：Android/web 走 plain，桌面走 secure（§3.5/§5.3 #07 的跨端分歧）；按 Android 基准记 plain。
        put("payment_code", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("home_usage", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("home_stats_cursor", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("pidai_appearance", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("pidai_proactive", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("crash_reporter", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("onboarding", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("notice_watch", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("reminder_watch", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("session_keepalive", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("campus_probe", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("lms_downloads", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("schedule_widget_prefs", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("notice_widget_cache", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        // 端内私有（§2.4）：换包判据 install_stamp。
        put("data_cache_meta", Entry(Scope.GLOBAL, Carrier.PLAIN_KV, excluded = true))
        // Room 三张表：档案里是行数组（§8.3），Android = Room，桌面 = secure，web = plain —— 记 Android 基准 ROOM_ROWS。
        put("download_tasks", Entry(Scope.GLOBAL, Carrier.ROOM_ROWS, rebuildable = true))
        put("jiaocai1_shelf", Entry(Scope.GLOBAL, Carrier.ROOM_ROWS))
        put("custom_courses", Entry(Scope.GLOBAL, Carrier.ROOM_ROWS))
        put("score_report", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("score_report_cache", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))

        // ── 账号档（base，尾部还有账号 suffix） ──
        put("cookies_normal", Entry(Scope.ACCOUNT, Carrier.SECURE_KV, credential = true))
        put("cookies_webvpn", Entry(Scope.ACCOUNT, Carrier.SECURE_KV, credential = true))
        put("sites_normal", Entry(Scope.ACCOUNT, Carrier.SECURE_KV, credential = true))
        put("sites_webvpn", Entry(Scope.ACCOUNT, Carrier.SECURE_KV, credential = true))
        put("agent_config", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("agent_config_secure", Entry(Scope.ACCOUNT, Carrier.SECURE_KV, credential = true))
        put("agent_config_fallback", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV, excluded = true))
        put("agent_memory", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("agent_sessions", Entry(Scope.ACCOUNT, Carrier.FILES))
        put("empty_room_cache", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV, rebuildable = true))
        put("dorm_power", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("campus_card", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("campus_card_data_cache", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("inbox", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("schedule_source", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("schedule_changes", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("schedule_diff", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("course_colors", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("attendance_records_ug", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("attendance_records_pg", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("score_cursor", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        // schedule_cache：桌面带账号后缀、web 不带（§3.2 #51 / §3.5④），记桌面基准 ACCOUNT；课表族缓存标可重建（§8.7）。
        put("schedule_cache", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV, rebuildable = true))
    }

    /** 查分类；没登记的名字默认按全局 plain KV 对待（不猜、不拦）。 */
    fun entryFor(base: String): Entry = table[base] ?: Entry(Scope.GLOBAL, Carrier.PLAIN_KV)

    /** 是否凭据族（§8.10 / A8）：默认不进档案，除非显式开关。 */
    fun isCredential(base: String): Boolean =
        entryFor(base).credential || credentialPrefixes.any { base.startsWith(it) }

    /** 是否端内私有（§2.4）：永不进档案，也不进 `redacted`。 */
    fun isExcluded(base: String): Boolean = base in excludedNames

    /** 全表（供守卫测试断言）。 */
    fun allEntries(): Map<String, Entry> = table

    /** §2.1 `ttlClass` 的提示值；UNKNOWN ⇒ 不写。 */
    fun ttlClassFor(base: String): String? = when (entryFor(base).ttl) {
        Ttl.TERM -> "term"
        Ttl.DAY -> "day"
        Ttl.WEEK -> "week"
        Ttl.FOREVER -> "forever"
        Ttl.NONE -> "none"
        Ttl.UNKNOWN -> null
    }
}
