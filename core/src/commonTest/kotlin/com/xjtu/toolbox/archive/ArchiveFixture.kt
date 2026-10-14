package com.xjtu.toolbox.archive

/**
 * 档案夹具（S1，Kotlin 常量）。
 *
 * 为什么写成 Kotlin 常量而不是 test resources：KMP 的 test resources 是逐目标的，
 * 而 `:testkit` 是 `kotlin.jvm`（`testkit/build.gradle.kts`），wasm 消费不了 ——
 * 三端互认（A5）要同一份夹具，所以放 `:core:commonTest`。
 */
object ArchiveFixture {

    const val ACCOUNT_ID = "2253415556"
    const val SUFFIX = "_2253415556"

    /** 固定时钟（A3 字节稳定用；也当 `lastUsedAt` / `at` 的值）。 */
    const val FIXED_NOW = 1_760_000_000_000L

    val accounts = listOf(
        ArchiveAccount(ACCOUNT_ID, SUFFIX, "UNDERGRADUATE", FIXED_NOW),
    )

    /** 全局档，六种类型标签各一个（§2.2 示例的那一批键）。 */
    val appSettings = StoreSnapshot(
        rawName = "app_settings",
        keys = mapOf(
            "dark_mode" to StoreValue.S("dark"),
            "show_quick_actions" to StoreValue.B(true),
            "eula_accepted_version" to StoreValue.I(4),
            "last_auto_update_check_at" to StoreValue.L(FIXED_NOW),
            "card_today_dinner_cache" to StoreValue.F(12.5f),
        ),
    )

    /** 账号档，带一个旁挂时间戳（写进 `at`）。 */
    val scheduleCache = StoreSnapshot(
        rawName = "schedule_cache$SUFFIX",
        keys = mapOf("schedule_current_term" to StoreValue.S("2026-2027-1")),
        writtenAt = mapOf("schedule_current_term" to FIXED_NOW),
    )

    /** 全局档，集合值故意**乱序 + 重复**，codec 要去重升序（§2.2 / A3）。 */
    val libraryFavorites = StoreSnapshot(
        rawName = "library_favorites",
        keys = mapOf("favorite_seats" to StoreValue.SetValue(listOf("B-2", "A-1", "A-1"))),
    )

    /** 凭据族：默认不进档案（A8）。 */
    val credential = StoreSnapshot(
        rawName = "xjtu_credentials",
        keys = mapOf("password" to StoreValue.S("hunter2")),
    )

    val stores = listOf(appSettings, scheduleCache, libraryFavorites, credential)

    /** `files/`：一事一文件原样搬运（§2.3）。账号档 + 全局档各一个。 */
    val files = listOf(
        ArchiveFile(
            SUFFIX,
            "agent_sessions/index.json",
            """[{"id":"s1","title":"hi","createdAt":"2026-10-14T08:00:00Z","updatedAt":"2026-10-14T09:00:00Z","locked":false}]""",
        ),
        ArchiveFile("", "courses/custom_courses.json", """[]"""),
    )

    fun fixedClock(): ExportOptions = ExportOptions(now = { FIXED_NOW })
}
