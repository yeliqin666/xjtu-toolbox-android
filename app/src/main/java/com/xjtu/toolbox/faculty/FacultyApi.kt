package com.xjtu.toolbox.faculty

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.brotli.BrotliInterceptor
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * 西安交通大学教师主页查询。
 *
 * 两个站点，**都不需要登录**：
 * - `faculty.xjtu.edu.cn` —— 检索接口与筛选项来源
 * - `gr.xjtu.edu.cn` —— 老师的个人主页
 *
 * 设计要点（每条都有实测依据，改之前先看注释）：
 * 1. 检索接口返回 JSON，但 `Content-Type` 是 `text/html`，只能按响应体判断，
 *    见 [looksLikeJson]。这跟图书馆 qspace/qseat 是同一个坑。
 * 2. 学院/学科/招生学科/荣誉四张 id 表在运行时从 search.jsp 解析，代码里不写死任何 id。
 * 3. 个人主页有 13 套模板，但字段标签一致，所以用标签驱动解析，不依赖 CSS 选择器。
 * 4. 约 1% 的老师主页不可用，一律走 [HomepageResult] 降级，不抛异常。
 */
class FacultyApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(BrotliInterceptor)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "FacultyApi"

        const val FACULTY_HOST = "https://faculty.xjtu.edu.cn"
        const val HOMEPAGE_HOST = "https://gr.xjtu.edu.cn"

        private const val SEARCH_URL = "$FACULTY_HOST/system/resource/tsites/advancesearch.jsp"

        /** 筛选项所在页面。四张 id 表都渲染在这里的 `<li onclick=...>` 上 */
        private const val FILTER_PAGE_URL = "$FACULTY_HOST/search.jsp?urltype=tree.TreeTempUrl&wbtreeid=1041"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 服务端模板渲染参数。这些值与查询语义无关，但缺任何一个都会拿不到结果。
         * `viewid` / `siteOwner` 是站点自身的标识，不是用户数据。
         */
        private val FIXED_PARAMS = mapOf(
            "viewmode" to "8",
            "viewid" to "1095235",
            "siteOwner" to "2105667170",
            "viewUniqueId" to "1095235",
            "showlang" to "zh_CN",
            "ispreview" to "false",
            "basenum" to "0",
            "productType" to "0",
            "ellipsis" to "...",
            "alignright" to "false",
        )

        /** 服务端单页上限，超过会被截断 */
        const val MAX_PAGE_SIZE = 100
        const val DEFAULT_PAGE_SIZE = 20

        /**
         * 判断响应体是不是 JSON，只看首个非空白字符。
         *
         * 不能用 Content-Type：advancesearch.jsp 返回纯 JSON 却打 `text/html` 的头。
         */
        internal fun looksLikeJson(body: String): Boolean =
            body.trimStart().firstOrNull()?.let { it == '{' || it == '[' } == true

        /** 把服务端给的相对路径（如头像 picUrl）补成绝对地址 */
        fun absoluteUrl(path: String, host: String = HOMEPAGE_HOST): String = when {
            path.isBlank() -> ""
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> host + path
            else -> "$host/$path"
        }

        /**
         * 个人主页字段标签。13 套模板共用同一套标签，各模板只是渲染其中的子集。
         *
         * 顺序重要：长标签必须排在短标签前面，否则「电子邮箱」会被「邮箱」抢先命中。
         */
        private val PROFILE_LABELS = listOf(
            "其他联系方式", "通讯/办公地址", "博士生导师", "硕士生导师",
            "毕业院校", "所在单位", "电子邮箱", "办公地点", "联系方式", "入职时间",
            "个人主页", "性别", "职称", "职务", "学历", "学位", "学科", "邮箱",
        )

        /**
         * 字段块之后紧跟的页面元素，命中即认为字段块结束。
         *
         * 块内最后一个字段没有「下一个标签」兜底，最容易把导航文字吞进来
         * （cn04 实测吞出过 `学科：公共管理学 主页`），所以这里把常见导航词一并列入。
         */
        private val FIELD_BLOCK_STOPPERS = listOf(
            "访问量", "最后更新时间", "版权所有", "当前位置", "中文主页", "陕ICP备",
            "主页", "基本信息", "个人简介", "校内登录", "手机版", "Personal profile",
        )

        /** 同一字段块内两个标签之间的最大间隔；超过则认为不是同一块 */
        private const val FIELD_CLUSTER_GAP = 220

        /** 单个字段值的长度上限，防止解析器把后面整段正文吞进来 */
        private const val FIELD_VALUE_MAX = 120

        /** 主页 HTML 里的邮箱是加密十六进制串，长这样就丢弃 */
        private val ENCRYPTED_VALUE_RE = Regex("^[0-9a-f]{32,}$", RegexOption.IGNORE_CASE)

        /** 模板标识：`jszy_cn01` 与 `jszyyyz` / `jszyzwmblan` 两套命名并存 */
        private val TEMPLATE_RE = Regex("""jszy_?([a-z0-9]+)""")

        /** 栏目 URL 语法，跨全部模板一致 */
        private val COLUMN_URL_RE =
            Regex("""/([A-Za-z0-9._-]+)/zh_CN/([a-z]+)/(\d+)/list/index\.htm""")

        /** 筛选项：`|--` 前缀编码层级 */
        private val OPTION_PREFIX_RE = Regex("""^[|\-\s]+""")
    }

    // ==================== 检索 ====================

    /**
     * 按条件检索教师。
     *
     * [FacultySearchQuery.proRank] 与 [FacultySearchQuery.tutorOnly] 在本地过滤：
     * 前者的 rankid 表服务端没有暴露，后者的 tutorType 参数语义不明，
     * 而 JSON 里已经带了 proRank / doctorTutor / gtutor，本地筛更可靠。
     *
     * 因为本地过滤发生在分页之后，开启这两个条件时单页返回数会少于 [pageSize]，
     * 这是预期行为——需要凑满一页请用 [searchAll]。
     */
    suspend fun search(
        query: FacultySearchQuery = FacultySearchQuery(),
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        /** 简介截断长度，列表页给小值可以显著减小响应体 */
        profileLength: Int = 400,
    ): FacultySearchPage = withContext(Dispatchers.IO) {
        val url = buildSearchUrl(query, page, pageSize, profileLength)
        val body = fetchText(url.toString(), referer = "$FACULTY_HOST/search.jsp")

        if (!looksLikeJson(body)) {
            Log.e(TAG, "advancesearch 未返回 JSON, preview=${body.take(500)}")
            throw RuntimeException("教师检索接口返回异常（非 JSON 响应）")
        }

        val json = JSONObject(body)
        val raw = json.optJSONArray("teacherData") ?: JSONArray()
        val members = buildList {
            for (i in 0 until raw.length()) {
                raw.optJSONObject(i)?.let { add(parseMember(it)) }
            }
        }
        FacultySearchPage(
            total = json.optInt("totalnum", members.size),
            totalPage = json.optInt("totalpage", 1),
            pageIndex = page,
            members = members.filter { it.matches(query) },
        )
    }

    /**
     * 翻页取回全部结果。
     *
     * [limit] 是硬上限——全校 4173 人，不设限容易在弱网下拖死 UI。
     * [onProgress] 回调已加载数量 / 服务端总数，方便做进度条。
     */
    suspend fun searchAll(
        query: FacultySearchQuery = FacultySearchQuery(),
        limit: Int = 500,
        pageSize: Int = MAX_PAGE_SIZE,
        profileLength: Int = 200,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null,
    ): List<FacultyMember> {
        val out = mutableListOf<FacultyMember>()
        val seen = mutableSetOf<Long>()
        var page = 1
        var totalPage = 1
        while (page <= totalPage && out.size < limit) {
            currentCoroutineContext().ensureActive()
            val result = search(query, page, pageSize, profileLength)
            totalPage = result.totalPage
            // 服务端分页偶有重复条目，按 teacherId 去重
            result.members.forEach { if (seen.add(it.teacherId)) out.add(it) }
            onProgress?.invoke(out.size, result.total)
            if (result.members.isEmpty()) break
            page++
        }
        return if (out.size > limit) out.take(limit) else out
    }

    private fun buildSearchUrl(
        query: FacultySearchQuery,
        page: Int,
        pageSize: Int,
        profileLength: Int,
    ): HttpUrl = SEARCH_URL.toHttpUrl().newBuilder().apply {
        addQueryParameter("pageindex", page.coerceAtLeast(1).toString())
        addQueryParameter("pagesize", pageSize.coerceIn(1, MAX_PAGE_SIZE).toString())
        addQueryParameter("profilelen", profileLength.coerceAtLeast(0).toString())
        addQueryParameter("collegeid", query.collegeId.toString())
        addQueryParameter("disciplineid", query.disciplineId.toString())
        addQueryParameter("enrollid", query.enrollDisciplineId.toString())
        addQueryParameter("honorid", query.honorId.toString())
        addQueryParameter("teacherName", query.name.trim())
        addQueryParameter("searchDirection", query.researchDirection.trim())
        addQueryParameter("py", query.pinyin.trim())
        // 服务端要求这两个参数存在；取值语义不明，一律留空走「不限」
        addQueryParameter("rankid", "0")
        addQueryParameter("degreeid", "0")
        addQueryParameter("tutorType", "")
        FIXED_PARAMS.forEach { (k, v) -> addQueryParameter(k, v) }
    }.build()

    private fun parseMember(o: JSONObject): FacultyMember = FacultyMember(
        teacherId = o.optLong("teacherId"),
        name = o.optString("name").trim(),
        englishName = o.optString("ename").trim(),
        pinyin = o.optString("pinYinName").trim(),
        homepageUrl = o.optString("url").trim(),
        collegeName = o.optString("collegeName").ifBlank { o.optString("unit") }.trim(),
        proRank = o.optString("prorank").trim(),
        job = o.optString("job").trim(),
        discipline = o.optString("discipline").trim(),
        degree = o.optString("degree").trim(),
        education = o.optString("education").trim(),
        graduatedUniversity = o.optString("graduatedUniversity").trim(),
        isDoctoralTutor = o.optInt("doctorTutor") == 1,
        isMasterTutor = o.optInt("gtutor") == 1,
        profile = o.optString("profile").ifBlank { o.optString("profileSummary") }.trim(),
        researchDirections = o.optJSONArray("researchDirectionList")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("researchDirectionTitle")
                        ?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
        }.orEmpty(),
        picUrl = o.optString("picUrl").trim(),
        email = o.optString("email").trim(),
        contact = o.optString("contact").trim(),
        phone = o.optString("phone").trim(),
        mobilePhone = o.optString("mobilephone").trim(),
        officeLocation = o.optString("officeLocation").trim(),
        address = o.optString("address").trim(),
        entryTime = o.optString("entryTime").trim(),
        lastUpdate = o.optString("latestUpdate").trim(),
        clickTimes = o.optLong("clickTimes"),
    )

    /** 本地补充过滤，见 [search] 注释 */
    private fun FacultyMember.matches(query: FacultySearchQuery): Boolean {
        if (query.proRank.isNotBlank() && proRank != query.proRank) return false
        return when (query.tutorOnly) {
            FacultySearchQuery.TutorFilter.DOCTORAL -> isDoctoralTutor
            FacultySearchQuery.TutorFilter.MASTER -> isMasterTutor
            null -> true
        }
    }

    // ==================== 筛选项（动态） ====================

    /**
     * 拉取四张筛选表。
     *
     * 页面约 400 KB，建议调用方缓存（DataCache，TTL 给到天级）——
     * 学院和学科一年也变不了几次。
     */
    suspend fun loadFilters(): FacultyFilters = withContext(Dispatchers.IO) {
        val html = fetchText(FILTER_PAGE_URL, referer = "$FACULTY_HOST/index.jsp")
        val doc = Jsoup.parse(html, FACULTY_HOST)
        FacultyFilters(
            colleges = parseOptions(doc, "selectByCollege"),
            disciplines = parseOptions(doc, "selectByDiscipline"),
            enrollDisciplines = parseOptions(doc, "selectByEnrollDiscipline"),
            honors = parseOptions(doc, "selectByHonor"),
        )
    }

    /**
     * 解析形如
     * `<li onclick="create_advance_search_conditon.selectByCollege(this,1077)">|--物理学院</li>`
     * 的筛选项。层级由文本前缀 `|--` 的长度推出。
     */
    private fun parseOptions(doc: org.jsoup.nodes.Document, fnName: String): List<FacultyOption> {
        val re = Regex("""$fnName\(this,(-?\d+)\)""")
        val seen = mutableSetOf<Int>()
        return doc.select("li[onclick*=$fnName]").mapNotNull { li ->
            val id = re.find(li.attr("onclick"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val rawText = li.ownText().ifBlank { li.text() }
            val prefix = OPTION_PREFIX_RE.find(rawText)?.value.orEmpty()
            val name = rawText.removePrefix(prefix).trim()
            if (name.isEmpty() || !seen.add(id)) return@mapNotNull null
            FacultyOption(id = id, name = name, depth = prefix.count { it == '-' })
        }
    }

    // ==================== 个人主页 ====================

    /**
     * 抓取并解析老师的个人主页。
     *
     * 不会抛网络异常——所有失败路径都映射成 [HomepageResult]，
     * 因为「主页打不开」在这里是常态（实测约 1%），不是异常。
     */
    suspend fun fetchHomepage(member: FacultyMember): HomepageResult =
        fetchHomepage(member.homepageUrl)

    suspend fun fetchHomepage(url: String): HomepageResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext HomepageResult.NotStandard(url)
        val standard = url.startsWith("$HOMEPAGE_HOST/") && url.endsWith("/zh_CN/index.htm")
        if (!standard) {
            // 实测出现过：学院自建师资页、WebVPN 链接、缺 /zh_CN/index.htm 的畸形值
            return@withContext HomepageResult.NotStandard(url)
        }
        val html = try {
            fetchText(url, referer = "$FACULTY_HOST/search.jsp")
        } catch (e: Exception) {
            Log.w(TAG, "主页抓取失败: $url", e)
            return@withContext HomepageResult.Error(e.message ?: "主页加载失败")
        }
        if (isUnavailablePage(html)) return@withContext HomepageResult.Unavailable
        runCatching { HomepageResult.Success(parseHomepage(html, url)) }
            .getOrElse {
                Log.e(TAG, "主页解析失败: $url", it)
                HomepageResult.Error(it.message ?: "主页解析失败")
            }
    }

    /**
     * 占位页判定。实测形态：563 字节且 `<title>error</title>`，或干脆 0 字节。
     * 用长度 + 标题双条件，避免把内容极简的真主页（最小的约 13 KB）误杀。
     */
    private fun isUnavailablePage(html: String): Boolean {
        if (html.isBlank()) return true
        if (html.length > 2000) return false
        val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        return title.isEmpty() || title.equals("error", ignoreCase = true)
    }

    internal fun parseHomepage(html: String, url: String): FacultyProfile {
        val doc = Jsoup.parse(html, HOMEPAGE_HOST)
        doc.select("script, style, noscript").remove()

        val template = TEMPLATE_RE.find(html)?.groupValues?.get(1).orEmpty()

        // <title> 格式跨模板一致：「{姓名} 西安交通大学教师主页管理系统 {栏目} {语言}」
        val titleName = doc.title().trim()
            .substringBefore("西安交通大学")
            .trim()

        // 文本里的 &nbsp;（ ）会破坏标签匹配，统一成普通空格
        val text = doc.body().text().replace(' ', ' ')

        return FacultyProfile(
            template = template,
            titleName = titleName,
            fields = parseFields(text),
            columns = parseColumns(doc, url),
        )
    }

    /**
     * 标签驱动的字段解析——13 套模板通吃的关键。
     *
     * 做法：
     * 1. 全文找出所有 `标签：` 的位置，长标签优先、重叠的丢弃
     *    （否则「电子邮箱：」里的「邮箱：」会被重复计一次）。
     * 2. 按位置聚簇：相邻标签间隔小于 [FIELD_CLUSTER_GAP] 才算同一块，取**最大的一簇**。
     *    这一步是为了避开老师在自定义栏目里手打的伪字段
     *    （zwmblan 模板常见「姓　名：李兵　职　称：二级教授」这类富文本）。
     * 3. 每个标签的值 = 到下一个标签之间的文本，再按结束标记与长度上限截断。
     */
    private fun parseFields(text: String): Map<String, String> {
        data class Hit(val start: Int, val valueStart: Int, val label: String)

        val hits = mutableListOf<Hit>()
        for (label in PROFILE_LABELS) {
            Regex(Regex.escape(label) + """\s*[：:]\s*""").findAll(text).forEach { m ->
                hits.add(Hit(m.range.first, m.range.last + 1, label))
            }
        }
        if (hits.isEmpty()) return emptyMap()
        hits.sortBy { it.start }

        // 丢弃与前一个命中重叠的（短标签被长标签包住的情况）
        val unique = mutableListOf<Hit>()
        for (h in hits) {
            val prev = unique.lastOrNull()
            if (prev != null && h.start < prev.valueStart) continue
            unique.add(h)
        }

        // 聚簇，取最大的一簇
        val clusters = mutableListOf<MutableList<Hit>>()
        for (h in unique) {
            val current = clusters.lastOrNull()
            if (current != null && h.start - current.last().valueStart <= FIELD_CLUSTER_GAP) {
                current.add(h)
            } else {
                clusters.add(mutableListOf(h))
            }
        }
        val block = clusters.maxByOrNull { it.size } ?: return emptyMap()

        val out = LinkedHashMap<String, String>()
        block.forEachIndexed { i, hit ->
            val end = block.getOrNull(i + 1)?.start ?: minOf(text.length, hit.valueStart + FIELD_VALUE_MAX)
            var value = text.substring(hit.valueStart, end.coerceAtLeast(hit.valueStart))
            FIELD_BLOCK_STOPPERS.forEach { stopper ->
                val at = value.indexOf(stopper)
                if (at >= 0) value = value.substring(0, at)
            }
            value = value.trim().trim('|', '-', '·').trim()
            if (value.isEmpty() || value.length > FIELD_VALUE_MAX) return@forEachIndexed
            // 主页上的邮箱是密文，丢掉——用 JSON 里的明文
            if (ENCRYPTED_VALUE_RE.matches(value)) return@forEachIndexed
            out.putIfAbsent(hit.label, value)
        }
        return out
    }

    /**
     * 解析主页栏目导航。
     *
     * 只认 URL 语法不认 DOM 结构，因此对全部模板有效。
     * 同一栏目在页面里往往出现多次（顶部导航 + 侧栏 + 页脚），按 columnId 去重，
     * 并保留第一个非空的链接文本作为标题。
     */
    private fun parseColumns(doc: org.jsoup.nodes.Document, pageUrl: String): List<FacultyColumn> {
        val siteId = pageUrl.removePrefix("$HOMEPAGE_HOST/").substringBefore("/")

        // 先按文档顺序收集，并记录每个链接的 <ul> 嵌套深度。
        // 层级不能丢：导航是「一级栏目 > 二级页面」的两级树，拍平会产生同名重复条目。
        data class Raw(val column: FacultyColumn, val ulDepth: Int)

        val raws = mutableListOf<Raw>()
        val seen = mutableSetOf<Long>()
        doc.select("a[href]").forEach { a ->
            val m = COLUMN_URL_RE.find(a.attr("href")) ?: return@forEach
            val (owner, type, idText) = m.destructured
            // 排除指向别人主页的链接（模板页脚偶有友情链接）
            if (siteId.isNotEmpty() && owner != siteId) return@forEach
            val columnId = idText.toLongOrNull() ?: return@forEach
            if (!seen.add(columnId)) return@forEach
            raws.add(
                Raw(
                    FacultyColumn(
                        type = type,
                        columnId = columnId,
                        url = absoluteUrl(m.value),
                        title = a.text().trim(),
                    ),
                    ulDepth = a.parents().count { it.tagName() == "ul" },
                )
            )
        }
        if (raws.isEmpty()) return emptyList()

        // 各模板的导航嵌在不同层数的容器里，所以按本页最小值归一化，
        // 而不是假设某个固定深度。没有嵌套的模板会全部落到 0，退化成平铺列表。
        val base = raws.minOf { it.ulDepth }
        var currentSection: Long? = null
        return raws.map { raw ->
            val depth = (raw.ulDepth - base).coerceIn(0, 1)
            if (depth == 0) {
                currentSection = raw.column.columnId
                raw.column.copy(depth = 0, parentId = null)
            } else {
                raw.column.copy(depth = 1, parentId = currentSection)
            }
        }
    }

    // ==================== 底层请求 ====================

    private fun fetchText(url: String, referer: String? = null): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            .apply { referer?.let { header("Referer", it) } }
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("请求失败 HTTP ${resp.code}")
            }
            return body
        }
    }
}
