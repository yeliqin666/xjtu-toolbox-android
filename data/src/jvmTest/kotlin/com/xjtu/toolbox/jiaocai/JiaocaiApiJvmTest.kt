package com.xjtu.toolbox.jiaocai

import com.xjtu.toolbox.auth.withJiaocaiLogin
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 教材中心取数（[JiaocaiApi]，搬进 `:data` 之后）在 JVM 上的口径。
 *
 * ## 它钉的是什么
 *
 * 搬迁前这个类长在 `:app`，搬进 `:data` 时只换了两处（`android.util.Log` → `:core` 的 `Log`、
 * 数据模型切去 `:core`），取数的形状一行未改。这里用 `:testkit` 的假上游（教材中心那半台）
 * 把三条口径钉住：
 *
 *  - **真登录链**：`/entry/login` → CAS → 落会话（`withJiaocaiLogin` 里那句 `check(site.hasLogin)`）；
 *  - **字段口径**：`content` 那段富文本怎么变成 `summary` / `fields` / `hasFullText`
 *    （`<br>` 换行、全角分号拆键值、`<b>` 标签剥掉）；
 *  - **`ssno` 的两段路**：列表接口**不给**（真站点上那段 `<a>` 已被服务端剥掉），
 *    要进 `/engine2/d/…` 详情才补得出来 —— 这正是屏上「本地全文」徽标与「阅读全文」按钮
 *    之间那一跳的依据。
 *
 * ## 为什么是「真登录」而不是给会话打桩
 *
 * 与 `Jiaocai1ApiJvmTest` 同一条理由（见 `JiaocaiLoginTestHarness` 的 KDoc）：
 * `SiteSession.executeWithReAuth` 是 `final`，打不了桩。
 */
class JiaocaiApiJvmTest {

    @Test
    fun `检索：书名剥掉标签、作者与课程字段拆出来、有「本地全文」就亮徽标`() = withJiaocaiLogin { site, fake ->
        val books = runBlocking { JiaocaiApi(site).search("高等数学") }

        val book = assertNotNull(books.singleOrNull(), "假上游只给一行书目")
        assertEquals(Jiaocai1FakeUpstream.SEARCH_BOOK_TITLE, book.title, "`<b>` 已被剥掉")
        assertEquals(Jiaocai1FakeUpstream.SEARCH_BOOK_AUTHOR, book.author)
        assertTrue(!book.title.contains("<"), "书名里不该留标签")
        assertEquals("general_55258665", book.id)
        assertEquals(Jiaocai1FakeUpstream.SEARCH_BOOK_APP_ID, book.appId)
        assertEquals(Jiaocai1FakeUpstream.SEARCH_BOOK_ENGINE_ID, book.engineInstanceId)
        assertEquals("高等数学", fake.jiaocai1.lastJiaocaiKeyword, "关键词原样进 `keyWord=`（URL 编码往返对）")
    }

    @Test
    fun `检索：content 那段富文本按 br 与全角分号拆成 summary 与 fields`() = withJiaocaiLogin { site, _ ->
        val book = assertNotNull(runBlocking { JiaocaiApi(site).search("高等数学") }.singleOrNull())

        assertEquals(
            listOf("获取方式一：本地全文", "课程名称：示例课"),
            book.summary.lines().take(2),
            "`<br>` 保留成换行，正文里没有标签",
        )
        assertEquals("本地全文", book.fields["获取方式一"], "同一行内的全角分号也要拆")
        assertEquals("示例课", book.fields["课程名称"])
        assertEquals(Jiaocai1FakeUpstream.SEARCH_BOOK_TITLE, book.fields["教材名称"])
        assertEquals("示例出版社", book.fields["出版社"])
        assertTrue(book.hasFullText, "「获取方式一：本地全文」这段文字就够了")
    }

    @Test
    fun `检索：列表接口不给 ssno，进详情才补得出来`() = withJiaocaiLogin { site, fake ->
        val api = JiaocaiApi(site)
        val book = assertNotNull(runBlocking { api.search("高等数学") }.singleOrNull())
        assertNull(book.ssno, "列表那段 content 里没有链接（与真站一致）")

        assertEquals(
            Jiaocai1FakeUpstream.SSNO,
            runBlocking { api.fetchSsno(book) },
            "详情页里的 `本地全文` 链接带着 jiaocai1 的 ssno（正则只锚域名与 ssno=）",
        )
        assertTrue(fake.jiaocai1.jiaocaiDetailCalls.get() > 0)
    }

    @Test
    fun `检索：上游答空表就是空表，不抛也不编`() = withJiaocaiLogin { site, _ ->
        val books = runBlocking { JiaocaiApi(site).search(Jiaocai1FakeUpstream.MISS_SEARCH_KEYWORD) }

        assertTrue(books.isEmpty())
    }

    @Test
    fun `检索：id 里取不出数字那段 ⇒ fetchSsno 早退给 null`() = withJiaocaiLogin { site, _ ->
        val book = JiaocaiBook(
            id = "general_",  // `substringAfterLast('_')` 是空串 ⇒ 不必要时打上游
            appId = Jiaocai1FakeUpstream.SEARCH_BOOK_APP_ID,
            engineInstanceId = Jiaocai1FakeUpstream.SEARCH_BOOK_ENGINE_ID,
            title = "示例教材：没有开放全文的那本",
        )

        assertNull(
            runBlocking { JiaocaiApi(site).fetchSsno(book) },
            "id 里取不出数字那段就早退（搬迁前同一句话）",
        )
    }
}
