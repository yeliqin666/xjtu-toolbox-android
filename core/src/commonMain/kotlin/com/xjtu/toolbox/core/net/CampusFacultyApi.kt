package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.faculty.FacultyFilters
import com.xjtu.toolbox.faculty.FacultyMember
import com.xjtu.toolbox.faculty.FacultySearchPage
import com.xjtu.toolbox.faculty.FacultySearchQuery
import com.xjtu.toolbox.faculty.FacultySource
import com.xjtu.toolbox.faculty.HomepageResult
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.isObject
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeLong
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 教师检索的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 `FacultyApi`）。
 *
 * 上游是同一个 `faculty.xjtu.edu.cn/system/resource/tsites/advancesearch.jsp`
 * （campus-api 响应的 `source` 字段写着），所以列表字段可以逐个对上；
 * campus-api 的 `rows[]` 是**白名单投影**后的归一字段。
 *
 * ## 与 `:app` 端的三处刻意差异（都写进 [FacultySource] 的 KDoc 了，这里给出处）
 * 1. **筛选 id 表拿不到**：[filters] 返回空的 [FacultyFilters]。那四张表在
 *    `search.jsp` 的 HTML 里，campus-api 刻意不解析（它的 KDoc 写明「要筛就得自己看页面拿 id」）
 *    ⇒ Web 上学院/学科两个下拉是空的，**职称那一档照常可用**（它由已加载结果推导）。
 * 2. **个人主页不解析**：[homepage] 返回 [HomepageResult.NotStandard]，
 *    详情页据此显示「在浏览器中打开」—— 正是 :app 端遇到非标准主页时的同一条退路。
 * 3. **联系方式不取**：campus-api 默认不返回任何联系方式（只有 `?contacts=1` 才附，
 *    见它的隐私口径），本类**不传**那个参数 ⇒ 详情卡里少几行。这是刻意的：
 *    批量下拉手机号与「看一眼某人主页」不同，Web 端不该自己开这个口子。
 *
 * 另外 `researchDirections` 在 campus-api 的投影里是 `["[object Object]", …]`（上游字段是对象，
 * 投影时没取到 title）⇒ 这里**丢弃**，改由 [FacultyMember.discipline] 顶在卡片那行（屏本来就这么兜底）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
class CampusFacultyApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
) : FacultySource {

    override suspend fun search(query: FacultySearchQuery, page: Int): FacultySearchPage {
        val data = getData(
            "/api/info/faculty",
            "q" to query.name,
            "college" to query.collegeId.takeIf { it > 0 }?.toString().orEmpty(),
            "discipline" to query.disciplineId.takeIf { it > 0 }?.toString().orEmpty(),
            "page" to page.coerceAtLeast(1).toString(),
            // 与 :app 的列表页同一档：简介截断 400，省响应体
            "profilelen" to "400",
        )
        val members = data.arr("rows").orEmpty()
            .mapNotNull { if (it.isObject) it.jsonObject else null }
            .map { parseMember(it) }
            // 职称 / 博导硕导是**客户端**过滤（服务端没有那两张表），与 :app 的 `matches` 同逻辑
            .filter { it.matches(query) }
        return FacultySearchPage(
            total = data["total"].safeInt(),
            totalPage = data["totalPages"].safeInt().coerceAtLeast(1),
            pageIndex = page,
            members = members,
        )
    }

    /** 见类 KDoc 第 1 条：campus-api 不解析那四张 id 表。 */
    override suspend fun filters(): FacultyFilters = FacultyFilters()

    /** 见类 KDoc 第 2 条：不做主页正文解析，一律降级成「在浏览器中打开」。 */
    override suspend fun homepage(member: FacultyMember): HomepageResult =
        HomepageResult.NotStandard(member.homepageUrl)

    private fun parseMember(item: JsonObject): FacultyMember {
        val contacts = item["contacts"] as? JsonObject
        return FacultyMember(
            // campus-api 同时给 uid 与 teacherId；后者与 :app 用的那个是同一个（上游 teacherId）
            teacherId = item["teacherId"].safeLong().takeIf { it != 0L }
                ?: item["uid"].safeString().toLongOrNull() ?: 0L,
            name = item["name"].safeString().trim(),
            englishName = item["englishName"].safeString().trim(),
            pinyin = item["pinyin"].safeString().trim(),
            homepageUrl = item["homepage"].safeString().trim(),
            collegeName = item["college"].safeString().ifBlank { item["unit"].safeString() }.trim(),
            // campus-api 把上游的 `prorank`（全小写那个键）投影成 `title`
            proRank = item["title"].safeString().trim(),
            job = item["job"].safeString().trim(),
            discipline = item["discipline"].safeString().trim(),
            degree = item["degree"].safeString().trim(),
            education = item["education"].safeString().trim(),
            graduatedUniversity = item["graduatedUniversity"].safeString().trim(),
            isDoctoralTutor = item["isDoctoralTutor"].safeBoolean(),
            isMasterTutor = item["isMasterTutor"].safeBoolean(),
            profile = item["profile"].safeString().ifBlank { item["profileSummary"].safeString() }.trim(),
            // 见类 KDoc：campus-api 的 researchDirections 是 "[object Object]"，不收
            researchDirections = emptyList(),
            picUrl = item["photo"].safeString().trim(),
            email = contacts?.get("email").safeString().trim().orEmpty(),
            contact = contacts?.get("contact").safeString().trim().orEmpty(),
            phone = contacts?.get("phone").safeString().trim().orEmpty(),
            mobilePhone = contacts?.get("mobilephone").safeString().trim().orEmpty(),
            officeLocation = contacts?.get("officeLocation").safeString().trim().orEmpty(),
            address = contacts?.get("address").safeString().trim().orEmpty(),
            entryTime = item["entryTime"].safeString().trim(),
            lastUpdate = item["latestUpdate"].safeString().trim(),
            clickTimes = item["clickTimes"].safeLong(),
        )
    }

    /** 与 `:app` 的 `FacultyMember.matches` 逐条相同：职称精确匹配 + 导师类型。 */
    private fun FacultyMember.matches(query: FacultySearchQuery): Boolean {
        if (query.proRank.isNotBlank() && proRank != query.proRank) return false
        return when (query.tutorOnly) {
            FacultySearchQuery.TutorFilter.DOCTORAL -> isDoctoralTutor
            FacultySearchQuery.TutorFilter.MASTER -> isMasterTutor
            null -> true
        }
    }

    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        val text = client.get("$baseUrl$path") {
            query.forEach { (k, v) -> if (v.isNotEmpty()) parameter(k, v) }
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 教师检索返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            error("campus-api 教师检索失败：${envelope["msg"].safeString().ifBlank { envelope["error"].safeString() }}")
        }
        return envelope["data"] as? JsonObject
            ?: error("campus-api 教师检索返回缺少 data：${text.take(120)}")
    }
}
