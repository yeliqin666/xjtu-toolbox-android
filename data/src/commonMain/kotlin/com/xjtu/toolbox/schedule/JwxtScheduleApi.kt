package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.core.net.ScheduleRow
import com.xjtu.toolbox.core.net.TermStartData
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Request

private const val BASE_URL = "https://jwxt.xjtu.edu.cn"

/** 学期号形状：`YYYY-YYYY-N`（教务的学期起点查询要把它切成 `XN` + `XQ` 两格）。 */
private val TERM_CODE = Regex("""\d{4}-\d{4}-\d""")

/**
 * [term] 是不是一个学期号（`2026-2027-1`）。
 *
 * 与 `:app` 的 `schedule/ScheduleApi.kt` 里那个私有 `TERM_CODE` **同一形状**（搬过来的就是它）；
 * `:server` 用它把「`?term=` 给的是一条学期号」判在 400 那一层（口径见契约 §5.2 的错误码）。
 */
fun isTermCode(term: String): Boolean = TERM_CODE.matches(term)

/**
 * **学生课表**（`wdkb` 应用）的取数与学期起点 —— `:app` 的 `schedule/ScheduleApi.kt` 里那两块的
 * 减法搬运，`serve` 模式（`:server`）与将来的桌面端共用。
 *
 * ## 它为什么在 `:data`
 *
 * `:server` 只能依赖 `:data`（同一个数据层、同一份解析口径，契约 §7.3：「`:data` 定义模型与口径，
 * 本文档只定义怎么把它端出去」）。`:app` 那份 `ScheduleApi` 还在原地（Android 端一行未改），所以
 * 这里**只搬 `:server` 要端出去的两件**：
 *
 * | 方法 | 上游 | `:app` 的对应 |
 * |---|---|---|
 * | [rows] | `POST <wdkb>/modules/xskcb/xskcb.do`（表单 `XNXQDM=<学期>`） | `ScheduleApi.getSchedule` 的原始行那一步（`wdkbRows`） |
 * | [termStart] | `POST <wdkb>/modules/jshkcb/cxjcs.do`（表单 `XN=<学年>&XQ=<学期序>`） | `ScheduleApi.getStartOfTerm` |
 *
 * **没有搬**：调停补课（`xskcb/xsdkkc.do` → `JwxtChanges` 的合并，见 [rows] 的 TODO）、
 * 考试表（`studentWdksapApp`）、教材（帆软报表）、考勤源那一档（那是 App 的设置，不是数据层概念）。
 *
 * ## 返回值为什么是 `:core` 的那两个类
 *
 * [ScheduleRow] / [TermStartData] 就在 `:core` 的 `core/net/JwxtSchedule.kt` 里，是**消费方
 * 解码用的同一个模型**（`CampusScheduleApi` 的 `rows.map { it.toCourseSlot() }` 吃的就是它）。
 * 端出去的行键名就是上游的键名（`KCM`/`SKJS`/…），这里再起一个模型只会多一份要同步的拼写。
 *
 * @param site 教务站点会话（`:data` 的 [SiteSession]，`:server` 侧由 `ServeSession` 给出）。
 */
class JwxtScheduleApi(private val site: SiteSession) {

    /**
     * 整学期课表行（`wdkb` 的 `xskcb.do`）。
     *
     * 逐字段：**:core** [ScheduleRow] 钉住的那 8 列，键名就是上游列名 ——
     * `KCM`（课程名）/ `SKJS`（教师）/ `JASMC`（教室）/ `SKXQ`（星期）/ `KSJC`（起始节）/
     * `JSJC`（结束节）/ `ZCMC`（周次文本）/ `JXBID`（教学班号）。
     * 上游给的是数字的几格（`SKXQ`/`KSJC`/`JSJC`）按 `safeString` 的既有口径读成文本，
     * 缺键就是空串（与 `:app` 的 `obj.get("KSJC").safeString()` 同一个读法）。
     *
     * ⚠️ **周次必须看 `ZCMC`**（`1-3周,5-7周(单)`），不要用 `SKZC` 位串 —— 上游位宽 16/18 不齐，
     * 两个端都不能踩（钉在 [ScheduleRow] 的 KDoc 上）。
     *
     * 上游把 `datas.xskcb.rows` 换掉（而不是报错）时给空表：与 `:app` 的 `wdkbRows`
     * （`?: return emptyList()`）同一个口径，页面上的表现是「这一学期没有课」。
     *
     * TODO（未搬）：调停补课（`xskcb/xsdkkc.do`）**没有**合进这些行 —— `:app` 的
     * `ScheduleApi.getSchedule` 会 `JwxtChanges.apply` 把调课/停课/补课合进去，这里给的是排课原样。
     * 端出去之前那是「serve 模式的课表对调课不敏感」的事实，不要当成两端已经一致。
     */
    suspend fun rows(term: String): List<ScheduleRow> {
        val request = Request.Builder()
            .url("$BASE_URL/jwapp/sys/wdkb/modules/xskcb/xskcb.do")
            .post(FormBody.Builder().add("XNXQDM", term).build())
            .build()
        val rows = execute(request).safeParseJsonObject()
            .obj("datas")?.obj("xskcb")?.arr("rows") ?: return emptyList()
        return rows.map { element ->
            val row = element.jsonObject
            ScheduleRow(
                courseName = row["KCM"].safeString(),
                teacher = row["SKJS"].safeString(),
                classroom = row["JASMC"].safeString(),
                dayOfWeek = row["SKXQ"].safeString(),
                startSection = row["KSJC"].safeString(),
                endSection = row["JSJC"].safeString(),
                weeksText = row["ZCMC"].safeString(),
                classId = row["JXBID"].safeString(),
            )
        }
    }

    /**
     * 学期起点：`XQKSRQ`（第 1 周周一）与 `ZZC`（总周数，含考试周）。
     *
     * `:core` [TermStartData] 的两个字段就这么来 —— `startDate` 是 `XQKSRQ` 的**日期那一段**
     * （上游给 `2026-09-14 00:00:00`，取前 10 个字符，与 `:app` 的 `split(" ")[0]` 同一个读法）。
     * `totalWeeks` 只在 `1..`[TermWeeks.MAX_REASONABLE] 里才算数，否则是 0（[TermStartData] 的
     * 默认值 —— 上游偶发给 `0` 或一个荒唐的大数，拿它画周次条会画到天上）。
     *
     * 上游没给 `XQKSRQ` 时**抛错**而不是编一个日期：课表的「今天是第几周」全靠这一天
     *（`:app` 侧对空值同样是抛 `IllegalStateException`，见 `getCurrentTerm` 的空行处理）。
     *
     * @param term 学期号；形状不对（[isTermCode] 为假）直接抛 —— `:server` 在更外层已经判过 400。
     */
    suspend fun termStart(term: String): TermStartData {
        if (!isTermCode(term)) throw IllegalStateException("学期号形状不对：$term")
        val parts = term.split("-")
        val request = Request.Builder()
            .url("$BASE_URL/jwapp/sys/wdkb/modules/jshkcb/cxjcs.do")
            .post(
                FormBody.Builder()
                    .add("XN", "${parts[0]}-${parts[1]}")
                    .add("XQ", parts[2])
                    .build(),
            )
            .build()
        val row = execute(request).safeParseJsonObject()
            .obj("datas")?.obj("cxjcs")?.arr("rows")?.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("教务未返回学期起点（$term）")
        val date = row["XQKSRQ"].safeString().trim().substringBefore(' ')
        if (date.isEmpty()) throw IllegalStateException("教务未返回学期开始日期（$term）")
        val totalWeeks = (row["ZZC"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?.takeIf { it in 1..TermWeeks.MAX_REASONABLE }
            ?: 0
        return TermStartData(term = term, startDate = date, totalWeeks = totalWeeks)
    }

    /** 取数（`executeWithReAuth`：会话过期时站点层会先重认一次，与 `:app` 同一条缝）。 */
    private suspend fun execute(request: Request): String =
        site.executeWithReAuth(request).use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
}
