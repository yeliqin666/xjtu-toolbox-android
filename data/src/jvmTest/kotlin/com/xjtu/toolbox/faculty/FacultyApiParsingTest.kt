package com.xjtu.toolbox.faculty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 教师检索的**解析口径** —— 在 `FacultyApi` 搬进 `:data`（`org.json` → `:core` 的 `AppJson`）
 * **之前**先把搬之前的行为钉住，搬完逐条仍然过。
 *
 * 夹具是 `:testkit` 的 [FacultyFixture]（纯字符串样本，不起服务器）；取数用**一条 OkHttp 拦截器**
 * 喂响应 —— 于是 `search()` / `loadFilters()` 的整条链路都是真跑（建 URL、`looksLikeJson` 判据、
 * `fetchPage` 的按因数拆页、`parseMember` 逐字段、英文接口补主页地址），却不需要网络，
 * 也不受「纯 HTTP 假代理给不了 CONNECT+TLS」那条限制。
 *
 * **断言里的 expected 值不是从搬完的代码抄来的**：它们来自两份参考实现，各自把搬之前的
 * `parseMember` / `parseOptions` 照抄一遍跑夹具 ——
 * 1. 真 `org.json`（`json-20240303`，`optInt`/`optLong` 的字符串解析与 `optJSONArray`/`optJSONObject`
 *    的 null 语义与 Android libcore 逐条相同；只有 `optString` 对 JSON `null` 那一格两边不一样：
 *    Android 走 `JSON.toString(JSONObject.NULL)` ⇒ 字面量 `"null"`，上游 json.org 给 fallback。
 *    **:app 跑在 Android 上**，所以这里按 `"null"` 钉，见 [FacultyFixture.searchJson] 第 3 行）；
 * 2. 真 `jsoup` 1.23.2（`ownText()` / `text()` / `attr()` 的行为）。
 *
 * 主页地址那套规整规则（`normalizeHomepage` / `siteOf`）在 [FacultyApiTest] 里钉着，两者同一个测试源集。
 */
class FacultyApiParsingTest {

    /** 一条只喂夹具、不出网的 OkHttp 客户端；[requests] 留着给断言数「打了几枪、带了什么参数」。 */
    private class FakeUpstream(private val responder: (Request) -> String) {
        val requests = mutableListOf<Request>()
        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests.add(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    // 真站点给检索 JSON 打的就是 text/html 的头（所以判据只看响应体首字符）
                    .body(responder(request).toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
    }

    /**
     * 按路径分派夹具。**认不出的路径回一个写着 URL 的错误页** —— 夹具要是把地址搞错了，
     * 报错里能一眼看回是哪条 URL（别让它长得像解析器坏了）。
     */
    private fun fixtureUpstream(searchBody: String = FacultyFixture.searchJson): FakeUpstream =
        FakeUpstream { request ->
            val url = request.url
            when {
                url.encodedPath == FacultyFixture.SEARCH_PATH && url.queryParameter("showlang") == "en" ->
                    FacultyFixture.searchJsonEn
                url.encodedPath == FacultyFixture.SEARCH_PATH -> searchBody
                url.encodedPath == FacultyFixture.FILTER_PAGE_PATH -> FacultyFixture.searchJspHtml
                else -> "<html><head><title>error</title></head><body>夹具没有这条路径：$url</body></html>"
            }
        }

    private fun search(fake: FakeUpstream, page: Int = 1, pageSize: Int = FacultyApi.DEFAULT_PAGE_SIZE) =
        runBlocking { FacultyApi(fake.client).search(page = page, pageSize = pageSize) }

    // ══════ parseMember：逐字段 ══════

    @Test
    fun `字段全给时逐字段取到值`() {
        val page = search(fixtureUpstream())
        assertEquals(listOf(20101L, 20102L, 20103L, 20104L), page.members.map { it.teacherId })
        // 整条 data class 相等 = 24 个字段一起钉住（含每个字符串的 trim）
        assertEquals(
            FacultyMember(
                teacherId = 20101,
                name = "示例甲",
                englishName = "Jia Shi Li",
                pinyin = "shi li jia",
                homepageUrl = "https://gr.xjtu.edu.cn/example-a/zh_CN/index.htm",
                collegeName = "示例学院",
                proRank = "教授",
                job = "系主任",
                discipline = "物理学",
                degree = "博士",
                education = "研究生",
                graduatedUniversity = "西安交通大学",
                isDoctoralTutor = true,
                isMasterTutor = false,
                profile = "示例甲的简介。",
                researchDirections = listOf("方向甲", "方向乙"),
                picUrl = "/system/resource/tsites/pic/20101.jpg",
                email = "example-a@example.edu.cn",
                contact = "029-00000000",
                phone = "029-00000001",
                mobilePhone = "13000000000",
                officeLocation = "教一楼 101",
                address = "兴庆校区",
                entryTime = "2005-09",
                lastUpdate = "2026-08-17",
                clickTimes = 1234,
            ),
            page.members[0],
        )
        // 研究方向的三个边界：没有 title 的对象不收、带空格的 title 收 trim 后的
        assertEquals(listOf("方向甲", "方向乙"), page.members[0].researchDirections)
    }

    @Test
    fun `字段一个都不给时每个字段给各自的默认值`() {
        val m = search(fixtureUpstream()).members[1]
        assertEquals(20102, m.teacherId)
        assertEquals("示例乙", m.name)
        assertEquals("", m.englishName)
        assertEquals("", m.pinyin)
        // homepageUrl 不在这里断言：这一行正是「地址为空」的那一行，search() 会用英文接口把它补上
        //（parseMember 给的默认值是空串 —— 正因为是空串才会去补，见「英文接口只补空的主页地址」）
        assertEquals("", m.collegeName)
        assertEquals("", m.proRank)
        assertEquals("", m.job)
        assertEquals("", m.discipline)
        assertEquals("", m.degree)
        assertEquals("", m.education)
        assertEquals("", m.graduatedUniversity)
        assertFalse(m.isDoctoralTutor)
        assertFalse(m.isMasterTutor)
        assertEquals("", m.profile)
        assertEquals(emptyList(), m.researchDirections)
        assertEquals("", m.picUrl)
        assertEquals("", m.email)
        assertEquals("", m.contact)
        assertEquals("", m.phone)
        assertEquals("", m.mobilePhone)
        assertEquals("", m.officeLocation)
        assertEquals("", m.address)
        assertEquals("", m.entryTime)
        assertEquals("", m.lastUpdate)
        assertEquals(0L, m.clickTimes)
    }

    @Test
    fun `JSON null 与字符串数字按 org json 的口径`() {
        val m = search(fixtureUpstream()).members[2]
        // optLong/optInt 对字符串数字会解析（不是「认不出就给默认值」）
        assertEquals(20103, m.teacherId)
        assertEquals(42L, m.clickTimes)
        assertTrue(m.isDoctoralTutor)
        assertFalse(m.isMasterTutor)
        // 空串走兜底字段
        assertEquals("示例学院丙", m.collegeName)
        assertEquals("备用简介", m.profile)
        // JSON null：Android 的 optString 给的是字面量 "null"（JSON.toString(JSONObject.NULL)），
        // 不是空串 —— 别为了「好看」把它换成 ""，那是行为变化
        assertEquals("null", m.email)
        assertEquals("null", m.homepageUrl)
    }

    // ══════ fetchPage：总数与列表长度 ══════

    @Test
    fun `总数取 totalnum 列表长度取 teacherData 的长度`() {
        val page = search(fixtureUpstream())
        assertEquals(4, page.total)
        assertEquals(4, page.members.size)
        assertEquals(1, page.totalPage)
        assertEquals(1, page.pageIndex)
    }

    @Test
    fun `totalnum 缺失时用 teacherData 的长度兜底`() {
        val withoutTotal = FacultyFixture.searchJson.replace("\"totalnum\":4,", "")
        assertEquals(4, search(fixtureUpstream(withoutTotal)).total)
    }

    @Test
    fun `总页数按 totalnum 与请求的 pageSize 算`() {
        val page = search(fixtureUpstream(), page = 2, pageSize = 2)
        assertEquals(4, page.total)
        assertEquals(2, page.totalPage)
        assertEquals(2, page.pageIndex)
    }

    @Test
    fun `整页回空对象时按因数拆小重取，最后降级为空`() {
        // fetchPage 的降级：没有 teacherData 且 pageSize > 1 ⇒ 按因数拆成 pageSize/2 重取，
        // 一直拆到 pageSize = 1（那里返回 -1）。pageSize=4 ⇒ 1 + 2 + 4 = 7 枪，总数 -1。
        val fake = fixtureUpstream(FacultyFixture.searchJsonEmptyPage)
        val page = search(fake, pageSize = 4)
        assertEquals(7, fake.requests.size)
        assertEquals(0, page.total)
        assertEquals(emptyList(), page.members)
        assertEquals(1, page.totalPage)
    }

    // ══════ buildSearchUrl ══════

    @Test
    fun `查询参数与固定模板参数一个不少`() {
        val fake = fixtureUpstream()
        val api = FacultyApi(fake.client)
        runBlocking {
            api.search(
                query = FacultySearchQuery(name = " 示例 ", researchDirection = " 方向 ", pinyin = " slj "),
                page = 3,
                pageSize = 10,
            )
        }
        val url = fake.requests.first().url
        assertEquals("https", url.scheme)
        assertEquals(FacultyFixture.HOST, url.host)
        assertEquals(FacultyFixture.SEARCH_PATH, url.encodedPath)
        // 名字里带空格的查询参数一律 trim 后再发；不传条件的一律 "0" / 空串（服务端要求这两个参数存在）
        val expected = mapOf(
            "pageindex" to "3",
            "pagesize" to "10",
            "showlang" to "zh_CN",
            "profilelen" to "400",
            "collegeid" to "0",
            "disciplineid" to "0",
            "enrollid" to "0",
            "honorid" to "0",
            "teacherName" to "示例",
            "searchDirection" to "方向",
            "py" to "slj",
            "rankid" to "0",
            "degreeid" to "0",
            "tutorType" to "",
            "viewmode" to "8",
            "viewid" to "1095235",
            "siteOwner" to "2105667170",
            "viewUniqueId" to "1095235",
            "ispreview" to "false",
            "basenum" to "0",
            "productType" to "0",
            "ellipsis" to "...",
            "alignright" to "false",
        )
        expected.forEach { (key, value) -> assertEquals(value, url.queryParameter(key), key) }
        assertEquals(expected.keys, url.queryParameterNames)
    }

    // ══════ 英文接口补主页地址 ══════

    @Test
    fun `英文接口只补空的主页地址`() {
        val fake = fixtureUpstream()
        val members = search(fake).members
        // 第 2 行是唯一地址为空的，从英文接口补；补进来的地址同样过 normalizeHomepage
        assertEquals("https://gr.xjtu.edu.cn/example-b/zh_CN/index.htm", members[1].homepageUrl)
        // 其余三行**不被英文接口覆盖**：自己的写法、站外地址、以及 JSON null 那个 "null"
        assertEquals("https://gr.xjtu.edu.cn/example-a/zh_CN/index.htm", members[0].homepageUrl)
        assertEquals("null", members[2].homepageUrl)
        assertEquals("https://orcid.org/0000-0000-0000-0000", members[3].homepageUrl)

        val en = fake.requests.single { it.url.queryParameter("showlang") == "en" }
        assertEquals(1, fake.requests.count { it.url.queryParameter("showlang") == "en" })
        assertEquals("1", en.url.queryParameter("profilelen"))
        assertEquals("20", en.url.queryParameter("pagesize"))
        assertEquals("1", en.url.queryParameter("pageindex"))
    }

    // ══════ loadFilters：四张 id 表 ══════

    @Test
    fun `四张筛选表逐项`() {
        val fake = fixtureUpstream()
        val filters = runBlocking { FacultyApi(fake.client).loadFilters() }
        assertEquals(FacultyFixture.FILTER_PAGE_PATH, fake.requests.single().url.encodedPath)
        // 学院：不限(0) 是个真选项；重复的 id 只留第一条；没有 id 的、别的函数名的都不进来
        assertEquals(
            listOf(
                FacultyOption(0, "不限", 0),
                FacultyOption(1001, "示例学院", 2),
                FacultyOption(1002, "示例学院二", 2),
            ),
            filters.colleges,
        )
        // 学科：层级是 `|--` 前缀里的 `-` 个数（4 层是 |--|--）；ownText 为空时退回 text()
        assertEquals(
            listOf(
                FacultyOption(2001, "一级学科", 4),
                FacultyOption(2002, "文本包在 span 里", 0),
                FacultyOption(2003, "二级学科", 4),
                FacultyOption(2004, "文本全在子元素里", 2),
            ),
            filters.disciplines,
        )
        assertEquals(
            listOf(FacultyOption(0, "不限", 0), FacultyOption(3001, "招生学科甲", 2)),
            filters.enrollDisciplines,
        )
        assertEquals(listOf(FacultyOption(4001, "荣誉甲", 2)), filters.honors)
        assertFalse(filters.isEmpty)
    }

    // ══════ 两条降级判据 ══════

    @Test
    fun `返回的不是 JSON 就报错`() {
        val fake = fixtureUpstream(FacultyFixture.notJsonPage)
        val error = assertFailsWith<RuntimeException> { search(fake) }
        assertEquals("教师检索返回了异常数据，请稍后重试", error.message)
    }

    @Test
    fun `looksLikeJson 只看首个非空白字符`() {
        // 真站点对检索 JSON 打 text/html 的头 ⇒ 判据只能看响应体
        assertTrue(FacultyApi.looksLikeJson("""  {"teacherData":[]}"""))
        assertTrue(FacultyApi.looksLikeJson("[1,2]"))
        assertTrue(FacultyApi.looksLikeJson("{}"))
        assertFalse(FacultyApi.looksLikeJson(FacultyFixture.notJsonPage))
        assertFalse(FacultyApi.looksLikeJson(""))
        assertFalse(FacultyApi.looksLikeJson("   "))
    }
}
