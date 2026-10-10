package com.xjtu.toolbox.notification

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.CHALLENGE_ANSWER
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.CLET_DATE_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.CLET_DATE_2
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.CLET_DATE_3
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.CLET_HOST
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.CLET_LINK_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.CLET_LIST_PATH
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.CLET_TITLE_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.CLET_TITLE_2
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.CLET_TITLE_3
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.JWC_DATE_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.JWC_DATE_2
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.JWC_DATE_3
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.JWC_DATE_4
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.JWC_LINK_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.JWC_LINK_2
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.JWC_LINK_3
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.JWC_TAG
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.JWC_TITLE_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.JWC_TITLE_2
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.JWC_TITLE_3
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.JWC_TITLE_4
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_DATE_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_DATE_2
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_DATE_3
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_DEPT_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_DEPT_2
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_DEPT_3
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_LINK_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_LINK_2
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_LINK_3
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_TITLE_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_TITLE_2
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_TITLE_3
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.OA_TITLE_4
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.SEARCH_CATEGORY
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.SEARCH_DATE_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.SEARCH_DATE_2
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.SEARCH_KEYWORD
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.SEARCH_LINK_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.SEARCH_LINK_2
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.SEARCH_TITLE_1
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.SEARCH_TITLE_2
import com.xjtu.toolbox.notification.NotificationFakeUpstream.Companion.SEARCH_UNDATED_TITLE
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 通知公告取数的**字段级口径**：两条爬虫族（通用学院 / OA）与动态挑战那一路，对着 `:testkit` 的
 * 夹具逐字段读。
 *
 * ## 为什么在 `:data` 而不是 `:app`
 *
 * 这一轮把 `NotificationApi` 与 `AppNoticeSource` 从 `:app` 搬进了 `:data`（桌面端第 13 条真数据
 * 路由）。搬之前先用夹具把**搬之前**的口径钉住：原来那边一行逻辑没改，只是文件换了地方、两处
 * 写法替换（`android.util.Log` → `:core` 的 `Log`、`toKx()` 换成同语义的本地实现）——
 * 但「没改」这句话得有证据，这些断言就是那个证据。手法与校园卡 / 空闲教室那几条
 * （`CampusCardApiJvmTest` / `EmptyRoomApiJvmTest`）逐条对齐：URL 一个字符都不改，全部走真取数链。
 * `:app` 那两条纯解析用例（`SiteSearchParseTest`）也随代码迁到了这里 —— `SiteSearch` 是
 * `internal`，跨不了模块边界。
 *
 * ## 这些断言从哪来
 *
 * 全部来自**夹具原文**（`NotificationFakeUpstream` 里那些常量与 HTML）与**响应形状**
 * （`a[title="…"]` / `span.time` / `span.p_next` / `gotodetail('…')` / `td.timedate1` /
 * `页次: 1/2 页` / `newskeycode2`），**不是从跑通的实现里抄回来的**。
 *
 * ## 覆盖率（诚实的一条）
 *
 * 夹具只扮了 29 个源里的 3 个（教务处 / 化工学院 / OA），另 26 个没被覆盖；站内检索里
 * 「站点没有博达检索 ⇒ 退回本地筛」那条降级、`extractItems` 的日期密度兜底与 `bruteForceExtract`
 * 也没有夹具。夹具能证明的是「这两条爬虫族与挑战解在真实响应形状上是对的」，
 * **不能**说成「29 个源都验过了」。
 */
class NotificationApiJvmTest {

    /**
     * 起假上游并在里面跑 [block]。
     *
     * ⚠️ 顺序要紧：[FakeCampusProxy.installFakeUpstreams] 必须在**碰 `HttpClients` 之前**调
     * （`NotificationApi` 默认那个客户端是从它派生出来的，而它会抄下当时的 `ProxySelector` 与
     * 平台 trust manager）。这一条与其余取数测试是同一条口径。
     */
    private fun withFakeNotification(block: (FakeCampusProxy) -> Unit) {
        val fake = FakeCampusProxy()
        FakeCampusProxy.installFakeUpstreams()
        fake.start()
        try {
            block(fake)
        } finally {
            fake.close()
        }
    }

    // ══════ ① 教务处：通用学院爬虫的主路 ══════

    /** 列表页的标题 / 日期 / 标签 / 链接逐字段读出来，`span.p_next` 那条还真把人带到第二页。 */
    @Test
    fun `教务处：列表页逐字段读出标题、日期、标签与链接，再跟着「下页」翻一页`() =
        withFakeNotification { fake ->
            val api = NotificationApi()

            val first = api.getNotificationPage(NotificationSource.JWC, 1)

            assertEquals(listOf(JWC_TITLE_1, JWC_TITLE_2, JWC_TITLE_3), first.items.map { it.title })
            assertEquals(listOf(JWC_DATE_1, JWC_DATE_2, JWC_DATE_3), first.items.map { it.date.toString() })
            // 标签来自 `a > i` 里的「【通知】」，方括号该被 trim 掉
            assertEquals(listOf(JWC_TAG, JWC_TAG, JWC_TAG), first.items.map { it.tags.single() })
            // 根相对路径按列表页 URL 解析 ⇒ 不带那层栏目目录
            assertEquals(listOf(JWC_LINK_1, JWC_LINK_2, JWC_LINK_3), first.items.map { it.link })
            assertTrue(first.items.all { it.source == NotificationSource.JWC })
            assertTrue(first.hasMore, "这一页有 span.p_next ⇒ hasMore 为真")

            // 第二页：`span.p_next` 指的是**倒序编号**的 jxtz2/28.htm（不是 jxtz2/2.htm）
            val second = api.getNotificationPage(NotificationSource.JWC, 2)

            assertEquals(JWC_TITLE_4, second.items.first().title)
            assertEquals(JWC_DATE_4, second.items.first().date.toString())
            assertEquals(1, fake.notification.jwcPage2Calls.get(), "一共只该请求一次第二页")
            assertTrue(!second.hasMore, "第二页没有「下页」⇒ hasMore 为假")
        }

    // ══════ ② 动态挑战：新版挑战页 ══════

    /**
     * 化工学院那一枪先被拦到挑战页：页面只给 `challengeId` / `a` / `b` / `operator`，
     * 客户端自己算答案、附上 hash，POST 换 `client_id`，再带 cookie 把列表取回来。
     *
     * 挑战页里那句 `var answer = 999;` 是**诱饵**：新版那一段必须优先走 `a`/`b`/`operator`
     * ⇒ 上去的 `answer` 是 [CHALLENGE_ANSWER]（17-25），不是 999。
     * body 里其余字段（`browser_info` 那几项、`hash` 的算法）由夹具逐个验过 ——
     * 任何一项不对它就回 `{"success":false,…}`，`client_id` 拿不到，下面的条目断言必红。
     */
    @Test
    fun `动态挑战：新版页面自己算答案并附 hash，通过后才读到化工学院的列表`() =
        withFakeNotification { fake ->
            val page = NotificationApi().getNotificationPage(NotificationSource.CLET, 1)

            assertTrue(fake.notification.challengePages.get() >= 1, "第一枪应当被拦到挑战页")
            assertEquals(1, fake.notification.challengePosts.get(), "挑战只该提交一次")
            assertEquals(1, fake.notification.challengeAccepts.get(), "假上游认了这一次挑战")
            assertEquals(0, fake.notification.challengeRejections.get())
            assertEquals(1, fake.notification.cletListCalls.get(), "通过之后才取到列表")

            // 请求形体：端点之外的三个头
            assertEquals("XMLHttpRequest", fake.notification.lastChallengeRequestedWith.get())
            assertTrue(
                fake.notification.lastChallengeContentType.get().orEmpty().startsWith("application/json"),
                "挑战是 JSON POST：${fake.notification.lastChallengeContentType.get()}",
            )
            assertEquals("https://$CLET_HOST$CLET_LIST_PATH", fake.notification.lastChallengeReferer.get())

            val posted = fake.notification.lastChallengeBody.get().orEmpty().safeParseJsonObject()
            assertEquals(
                CHALLENGE_ANSWER,
                posted["answer"].intValue,
                "答案是自己按 a/b/operator 算出来的，不是页面里那个诱饵 999",
            )
            assertTrue(posted["hash"] != null, "新版要附 hash（它由 challengeId+answer+UA 前 10 位 算出）")

            // 日期是 `<span><b>MM/DD</b>YYYY</span>` 拼回来的
            assertEquals(listOf(CLET_TITLE_1, CLET_TITLE_2, CLET_TITLE_3), page.items.map { it.title })
            assertEquals(
                listOf(CLET_DATE_1, CLET_DATE_2, CLET_DATE_3),
                page.items.map { it.date.toString() },
            )
            // 相对路径按列表页 URL 解析 ⇒ 多一层栏目目录（/xwgg/）
            assertEquals(CLET_LINK_1, page.items.first().link)
            assertTrue(!page.hasMore, "这一页没有「下页」")
        }

    // ══════ ③ 动态挑战：服务端偶发拒绝 ⇒ 重取一次再解一轮 ══════

    /**
     * 真站点会偶发 `{"success":false,"message":"Invalid challenge"}`（浏览器里刷新一下就好），
     * 而 `challengeId` 是一次性的 ⇒ 客户端必须**重新取页面**拿一个新 id 再解一轮，不能拿旧 id 重试。
     */
    @Test
    fun `挑战被服务端偶发拒绝：重取页面拿新 challengeId 再解一轮，仍然读得到列表`() =
        withFakeNotification { fake ->
            fake.notification.rejectNextChallengePosts.set(1)

            val page = NotificationApi().getNotificationPage(NotificationSource.CLET, 1)

            assertEquals(2, fake.notification.challengePosts.get(), "被拒之后应当再解一轮")
            assertEquals(1, fake.notification.challengeRejections.get())
            assertEquals(1, fake.notification.challengeAccepts.get())
            assertEquals(
                2,
                fake.notification.challengeIdsPosted.toSet().size,
                "两轮必须是两个不同的 challengeId（一次性的，不能拿旧的重试）",
            )
            assertEquals(CLET_TITLE_1, page.items.first().title)
        }

    // ══════ ④ 动态挑战：旧版挑战页（页面直接给 answer）══════

    /** 旧版页面直接给 `answer`，那一支不附 hash，`browser_info` 也是另一套字段（由夹具验）。 */
    @Test
    fun `动态挑战旧版：页面直接给 answer、不附 hash，同样换得到 client_id`() =
        withFakeNotification { fake ->
            fake.notification.legacyChallenge.set(true)

            val page = NotificationApi().getNotificationPage(NotificationSource.CLET, 1)

            assertEquals(1, fake.notification.challengeAccepts.get())
            val posted = fake.notification.lastChallengeBody.get().orEmpty().safeParseJsonObject()
            assertEquals(CHALLENGE_ANSWER, posted["answer"].intValue)
            assertNull(posted["hash"], "旧版那一支不该附 hash")
            assertEquals(CLET_TITLE_1, page.items.first().title)
        }

    // ══════ ⑤ OA：另一族爬虫（表格行 + 页次）══════

    /** OA 的条目是表格行：标题取 `a[title]`、作者取 `td.timedate1` 里的部门、链接由 `gotodetail` 的 id 拼。 */
    @Test
    fun `OA 通知：表格行读出标题、部门标签与详情链接，「页次」说了还有下一页就再翻一页`() =
        withFakeNotification { fake ->
            val api = NotificationApi()

            val first = api.getNotificationPage(NotificationSource.OA, 1)

            assertEquals(listOf(OA_TITLE_1, OA_TITLE_2, OA_TITLE_3), first.items.map { it.title })
            assertEquals(listOf(OA_DATE_1, OA_DATE_2, OA_DATE_3), first.items.map { it.date.toString() })
            assertEquals(listOf(OA_DEPT_1, OA_DEPT_2, OA_DEPT_3), first.items.map { it.tags.single() })
            assertEquals(listOf(OA_LINK_1, OA_LINK_2, OA_LINK_3), first.items.map { it.link })
            assertTrue(first.items.all { it.source == NotificationSource.OA })
            assertTrue(first.hasMore, "「页次: 1/2 页」⇒ 还有下一页")

            val second = api.getNotificationPage(NotificationSource.OA, 2)

            assertEquals(OA_TITLE_4, second.items.first().title)
            assertTrue(!second.hasMore, "「页次: 2/2 页」⇒ 到底了")
            assertTrue(fake.notification.oaListCalls.get() >= 2, "两页都被请求过")
        }

    // ══════ ⑥ 合并：三个源一起 ══════

    /** 合并按日期倒序；三个源都取得到 ⇒ 没有谁被静默跳过（`skipped` 为空）。 */
    @Test
    fun `合并三个源：按日期倒序排好，没有一个源被静默跳过`() = withFakeNotification { fake ->
        val merged = runBlocking {
            NotificationApi().getMergedNotificationsWithSkipped(
                listOf(NotificationSource.JWC, NotificationSource.CLET, NotificationSource.OA),
                page = 1,
            )
        }

        assertEquals(
            listOf(CLET_DATE_3, CLET_DATE_2, JWC_DATE_1, JWC_DATE_2, CLET_DATE_1, OA_DATE_1, JWC_DATE_3, OA_DATE_2, OA_DATE_3),
            merged.items.map { it.date.toString() },
            "9 条样本按日期倒序（含两个源的日期交错）",
        )
        assertEquals(
            setOf(NotificationSource.JWC, NotificationSource.CLET, NotificationSource.OA),
            merged.items.map { it.source }.toSet(),
            "三个源都该在里面",
        )
        assertTrue(merged.skipped.isEmpty(), "三个源都答得上，不该有被跳过的")
        assertTrue(merged.hasMore, "教务处与 OA 的第一页都还有下一页")
        assertEquals(1, fake.notification.jwcListCalls.get())
        assertEquals(1, fake.notification.cletListCalls.get())
        assertEquals(1, fake.notification.oaListCalls.get())
    }

    // ══════ ⑦ 站内检索：博达 CMS 的全文检索 ══════

    /**
     * 检索走站点自己的检索表单：客户端在首页上认出那个 `search.jsp?wbtreeid=…` 的表单，
     * 把关键词 **Base64 后放进 `newskeycode2`**（夹具解回来逐字比对），结果按相关度排的那几行里
     * 只收**带完整日期**的 —— 只写「09-28」的那一行没法按时间排，丢掉（所以它不在结果里）。
     */
    @Test
    fun `站内检索：关键词 Base64 发过去，只收带完整日期的结果行`() = withFakeNotification { fake ->
        val result = runBlocking { NotificationApi().search(listOf(NotificationSource.JWC), SEARCH_KEYWORD) }

        assertEquals(SEARCH_KEYWORD, fake.notification.lastSearchKeyword.get(), "Base64 解回来该是原关键词")
        // 结果页说还有下一页 ⇒ 客户端把第二页也取了
        assertEquals(2, fake.notification.lastSearchPage.get(), "第二页确实被取过")

        assertEquals(listOf(SEARCH_LINK_1, SEARCH_LINK_2), result.items.map { it.link })
        assertEquals(listOf(SEARCH_DATE_1, SEARCH_DATE_2), result.items.map { it.date.toString() })
        // 「【通知】」是分类标签：从标题里抠掉、当 tag 留着
        assertEquals(SEARCH_TITLE_1, result.items.first().title)
        assertEquals(listOf(SEARCH_CATEGORY), result.items.first().tags)
        assertEquals(SEARCH_TITLE_2, result.items.last().title)
        assertTrue(result.items.last().tags.isEmpty(), "没有分类前缀的那条就没有标签")
        assertTrue(
            result.items.none { it.title == SEARCH_UNDATED_TITLE },
            "只写「09-28」的行没有年份，没法按时间排 ⇒ 该被丢掉",
        )
        assertTrue(result.skipped.isEmpty())
    }
}
