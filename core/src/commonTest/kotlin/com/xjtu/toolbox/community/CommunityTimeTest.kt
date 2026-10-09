package com.xjtu.toolbox.community

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * 随 `CommunityChrome` 搬进 :core 的这一批纯逻辑断言。
 *
 * 这三件事以前**没有测试**：帖子/楼层的相对时间（`communityTime`）、@提及的链接化
 * （`linkMentions`）、以及输入框里的 @前缀识别（`mentionQuery`）。搬进 commonMain 时
 * `java.time` 换成了 `kotlinx-datetime`（`DateTimeFormatter` 改手写补零、「昨天」改比
 * `toEpochDays()`），属于「改写」而不是「搬家」，所以这里把每个分支和几条边界钉住 ——
 * 搬迁把文案或分支顺序改坏了，这里先红。
 *
 * `communityTimeOf` 收 `now`/`zone` 两个参数就是为了这个：不然「今天是哪天」「哪个时区的今天」
 * 只能靠真实时钟，测不了跨日/跨年/跨时区。
 */
class CommunityTimeTest {

    private val shanghai = TimeZone.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-07T12:00:00+08:00")

    private fun time(iso: String, zone: TimeZone = shanghai): String = communityTimeOf(iso, now, zone)

    @Test
    fun `刚刚：未来时间与不到一分钟都算刚刚`() {
        // 服务器时钟快几秒时上游会给「未来」的时间戳，旧实现用 minutes < 1 接住，必须保留
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
    fun `@用户名 变成指向 GitHub 主页的链接`() {
        assertEquals("[@alice](https://github.com/alice) 你好", linkMentions("@alice 你好"))
        assertEquals("看 [@a-b](https://github.com/a-b) 的帖", linkMentions("看 @a-b 的帖"))
        assertEquals("（[@alice](https://github.com/alice)）", linkMentions("（@alice）"))
    }

    @Test
    fun `代码块与行内代码里的 @ 不动，邮箱也不动`() {
        val fenced = "```\n@alice\n```"
        assertEquals(fenced, linkMentions(fenced))
        assertEquals("`@alice`", linkMentions("`@alice`"))
        assertEquals("me@example.com", linkMentions("me@example.com"))
        assertEquals("没有 @ 就原样", linkMentions("没有 @ 就原样"))
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
