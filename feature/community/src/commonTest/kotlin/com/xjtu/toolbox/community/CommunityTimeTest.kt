package com.xjtu.toolbox.community

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** `CommunityChrome` 的纯逻辑断言；正文渲染的见 `GithubMarkdownTest`。 */
class CommunityTimeTest {

    private val shanghai = TimeZone.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-07T12:00:00+08:00")

    private fun time(iso: String, zone: TimeZone = shanghai): String = communityTimeOf(iso, now, zone)

    @Test
    fun `刚刚：未来时间与不到一分钟都算刚刚`() {
        // 服务器时钟快几秒时上游会给「未来」的时间戳，必须当「刚刚」
        assertEquals("刚刚", time("2026-10-07T12:00:30+08:00"))
        assertEquals("刚刚", time("2026-10-07T11:59:30+08:00"))
        assertEquals("刚刚", time("2026-10-07T11:59:01+08:00"))
    }

    @Test
    fun `分钟与小时的边界`() {
        assertEquals("1 分钟前", time("2026-10-07T11:59:00+08:00")) // 整 60 秒
        assertEquals("59 分钟前", time("2026-10-07T11:01:00+08:00"))
        assertEquals("1 小时前", time("2026-10-07T11:00:00+08:00")) // 整 60 分钟：跨到小时档
        assertEquals("3 小时前", time("2026-10-07T09:00:00+08:00"))
    }

    @Test
    fun `昨天带时分，且补零`() {
        assertEquals("昨天 23:30", time("2026-10-06T23:30:00+08:00"))
        assertEquals("昨天 09:05", time("2026-10-06T09:05:00+08:00"))
    }

    @Test
    fun `同年更早只给月日，跨年给年月日`() {
        assertEquals("10-02", time("2026-10-02T08:05:00+08:00"))
        assertEquals("01-05", time("2026-01-05T01:02:00+08:00"))
        assertEquals("2025-12-31", time("2025-12-31T23:59:00+08:00"))
    }

    @Test
    fun `同一个时刻在不同时区可以落在不同的那一天`() {
        // 上海 10-07 00:30 / UTC 10-06 16:30；上游那个时刻是上海 10-06 23:00 / UTC 10-06 15:00
        val nearMidnight = Instant.parse("2026-10-07T00:30:00+08:00")
        val iso = "2026-10-06T23:00:00+08:00" // 90 分钟前
        assertEquals("昨天 23:00", communityTimeOf(iso, nearMidnight, shanghai))
        assertEquals("1 小时前", communityTimeOf(iso, nearMidnight, TimeZone.UTC))
    }

    @Test
    fun `解析不了返回空串`() {
        assertEquals("", time(""))
        assertEquals("", time("刚刚"))
        assertEquals("", time("2026-13-45T99:99:99+08:00"))
    }

    @Test
    fun `mentionQuery 认光标前的 @前缀`() {
        assertEquals("ali", mentionQuery("hi @ali", 7))
        assertEquals("", mentionQuery("hi @", 4))
        assertNull(mentionQuery("hi", 2))
        assertNull(mentionQuery("a@b", 3)) // 前面紧挨字母：不是提及
        assertNull(mentionQuery("hi @" + "a".repeat(40), 44)) // 超过 39 个字符
        assertEquals("a-b", mentionQuery("（@a-b", 5))
    }
}
