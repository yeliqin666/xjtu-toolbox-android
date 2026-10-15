package com.xjtu.toolbox.bulletin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 公告的时间窗解析（[BulletinRules.parseInstant]） */
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
