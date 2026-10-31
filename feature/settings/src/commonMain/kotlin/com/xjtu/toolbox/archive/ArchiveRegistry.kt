package com.xjtu.toolbox.archive

/** store 名 → 分类表（§2.4 / §3.2 / §5.2 第 7 条 / §8.10）。 */
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

    /** 凭据族前缀（§8.10）：`cookies_*` / `sites_*` / `agent_config_secure*`。 */
    private val credentialPrefixes = listOf("cookies_", "sites_", "agent_config_secure", "community_images_")

    /** 端内私有、永不进档案（§2.4）。 */
    private val excludedNames = setOf("data_cache_meta", "serve", "agent_config_fallback")

    /**
     * 值语义是「字符串集合」的键：`store 名（base） → 键名`，键名以 `*` 结尾 = 前缀匹配。
     *
     * 为什么需要一张表：档案里的 `t` 是**反推**出来的——端内存储只有字符串（web 逗号 join、桌面 `\u0000` join、
     * Android 是 `getStringSet`），导出侧只能猜。猜错的代价是跨端导入 `ClassCastException`：
     * `favorite_venue_ids` 只收藏一个时猜到 `t="i"`（`42` 看着像 int）、空集合猜到 `t="s" v=""`，
     * 而 Android 读它用的是 `getStringSet` ⇒ 落成 Int/String 的键一读就崩。
     *
     * 所以“值语义已知”的键就在这里声明，导出侧（`StoreArchive.export`）对它们**强制**写 `t="set"` + 数组，
     * 不再猜。按 `store/key` 两级声明：同名键在不同 store 里完全可能是两种东西
     * （`venue_favorites` 里除了 `favorite_venue_ids` 还有 `last_venue` 这种普通字符串）。
     */
    private val setKeys: Map<String, Set<String>> = mapOf(
        "venue_favorites" to setOf("favorite_venue_ids"),
        "library_favorites" to setOf("favorite_seats"),
        "bulletin_store" to setOf("dismissed_ids", "acked_ids"),
        "feedback" to setOf("read_replies"),
        "notice_watch" to setOf("sources", "baselined_sources"),
        // 课表快照键带学期/来源（`snap3_<term>_<source>`，旧格式是 `snap_*`）⇒ 前缀匹配。
        "schedule_diff" to setOf("snap*"),
    )

    private val table: Map<String, Entry> = buildMap {
        // ── 全局档（无账号后缀） ──
        put("app_settings", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("xjtu_credentials", Entry(Scope.GLOBAL, Carrier.SECURE_KV, credential = true))
        // xjtu_accounts 密文、内含 password/指纹（Account 全文）⇒ 凭据族；账号列表经 manifest.accounts 走，不进档案。
        put("xjtu_accounts", Entry(Scope.GLOBAL, Carrier.SECURE_KV, credential = true))
        // xjtu_cookies：旧单账号迁移残留池，密文。
        put("xjtu_cookies", Entry(Scope.GLOBAL, Carrier.SECURE_KV, credential = true))
        put("github_community", Entry(Scope.GLOBAL, Carrier.SECURE_KV, credential = true))
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
        put("pidai_card_visibility", Entry(Scope.GLOBAL, Carrier.PLAIN_KV))
        put("agent_memory", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV))
        put("agent_sessions", Entry(Scope.ACCOUNT, Carrier.FILES))
        put("empty_room_cache", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV, rebuildable = true))
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
        put("schedule_cache", Entry(Scope.ACCOUNT, Carrier.PLAIN_KV, rebuildable = true))
    }

    /** 查分类；没登记的名字默认按全局 plain KV 对待（不猜、不拦）。 */
    fun entryFor(base: String): Entry = table[base] ?: Entry(Scope.GLOBAL, Carrier.PLAIN_KV)

    /** 是否凭据族（§8.10）：默认不进档案，除非显式开关。 */
    fun isCredential(base: String): Boolean =
        entryFor(base).credential || credentialPrefixes.any { base.startsWith(it) }

    /** 是否端内私有（§2.4）：永不进档案，也不进 `redacted`。 */
    fun isExcluded(base: String): Boolean = base in excludedNames

    /**
     * 这个键的值语义是不是字符串集合（导出侧据此强制 `t="set"`，见 [setKeys]）。
     *
     * [base] 是 store 名去掉账号后缀；[key] 是 store 内的键名。没声明的就是 `false`。
     */
    fun isStringSet(base: String, key: String): Boolean =
        setKeys[base]?.any { pattern ->
            if (pattern.endsWith("*")) key.startsWith(pattern.dropLast(1)) else key == pattern
        } ?: false

    /** 全表（供守卫测试断言）。 */
    fun allEntries(): Map<String, Entry> = table
}
