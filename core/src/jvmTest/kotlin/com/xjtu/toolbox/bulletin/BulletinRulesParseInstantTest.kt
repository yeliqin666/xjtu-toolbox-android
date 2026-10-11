package com.xjtu.toolbox.bulletin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 公告的时间窗解析（[BulletinRules.parseInstant]）—— 搬进 `:core` 时 `java.time` 换成
 * `kotlinx-datetime` 的那一段，逐条钉住。
 *
 * ## 为什么它值得单独一个测试
 *
 * 公告 JSON 的时间窗是**人写的**（`announce/bulletin.json` 里的 `startsAt` / `endsAt`），
 * 仓库里历史上出现过两种写法：带时区的 ISO 8601 与**不带时区**的本地时间。而搬迁时那次
 * 依赖替换恰好在这两种写法上做了合并（`Instant.parse` + `OffsetDateTime.parse` 两条并成一条、
 * `LocalDateTime` / `LocalDate` 改走 `toInstant(beijing)` / `atStartOfDayIn(beijing)`）——
 * 一旦换算错，公告会**静默**地在不该出现的时候出现（或者该出现时不出现），屏上完全看不出来。
 *
 * 断言值全部来自搬迁前的实现（`:app` 的 `BulletinRulesTest` 里那一批同名断言），
 * `2026-08-19T08:00:00+08:00` ≡ `2026-08-19T00:00:00Z` 就是那条口径。
 */
class BulletinRulesParseInstantTest {

    @Test
    fun `带时区的 ISO 8601：Z 与 +08-00 都认，且换算成同一时刻`() {
        assertEquals("2026-08-19T00:00:00Z", BulletinRules.parseInstant("2026-08-19T08:00:00+08:00").toString())
        assertEquals("2026-08-19T00:00:00Z", BulletinRules.parseInstant("2026-08-19T00:00:00Z").toString())
        assertEquals(
            BulletinRules.parseInstant("2026-08-19T08:00:00+08:00"),
            BulletinRules.parseInstant("2026-08-19T00:00:00Z"),
        )
    }

    @Test
    fun `不带时区的本地时间：按北京时间理解（含秒与不带秒两种写法）`() {
        assertEquals("2026-08-19T00:00:00Z", BulletinRules.parseInstant("2026-08-19T08:00:00").toString())
        assertEquals("2026-08-19T00:00:00Z", BulletinRules.parseInstant("2026-08-19T08:00").toString())
    }

    @Test
    fun `只有日期：当天零点（北京时间）`() {
        assertEquals("2026-08-18T16:00:00Z", BulletinRules.parseInstant("2026-08-19").toString())
    }

    @Test
    fun `空与认不出来的写法：null，不抛`() {
        assertNull(BulletinRules.parseInstant(null))
        assertNull(BulletinRules.parseInstant(""))
        assertNull(BulletinRules.parseInstant("   "))
        assertNull(BulletinRules.parseInstant("明天"))
        assertNull(BulletinRules.parseInstant("2026-13-40"))
    }
}
