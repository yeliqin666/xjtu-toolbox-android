package com.xjtu.toolbox.jwapp

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ScheduleChangeEvent
import com.xjtu.toolbox.util.XjtuTime
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "JwappSchedule"

/** [JwappScheduleApi.getSchedule] 的结果：合并后的课表，加一份人可读的调课/停课事件（含官方备注）。 */
data class JwappScheduleResult(val courses: List<CourseItem>, val changeEvents: List<ScheduleChangeEvent>)

/**
 * 新版移动教务的课表接口。
 *
 * 与教务（`ScheduleApi.getSchedule`）最大的不同：这边**一次只给一周**
 * （`querySchedule` 的 `skzc` 就是周次），所以整学期课表要从第 1 周查到
 * `maxWeekNum` 周，再把同一节课在哪些周出现过累成 [CourseItem.weekBits]。
 * 代价是一次刷新要发十几个请求，好处是拿得到分钟级上下课时间。
 *
 * 只能查当前学期——历史学期 jwapp 查不了，由 `ScheduleSourceRouter` 负责拦在外面。
 */
class JwappScheduleApi(site: SiteSession) {

    private val api = JwappApi(site)
    private val jsonType = "application/json".toMediaType()

    /** 学期代码与总周数。jwapp 自己说当前学期是哪个，比外部猜一个可靠。 */
    fun basis(): TimeTableBasis = api.getTimeTableBasis()

    /**
     * 整学期课表。
     *
     * @param termCode 学期代码，必须是 jwapp 的当前学期
     * @param maxWeekNum 学期总周数，来自 [basis]
     */
    fun getSchedule(termCode: String, maxWeekNum: Int): JwappScheduleResult {
        require(maxWeekNum in 1..MAX_REASONABLE_WEEKS) { "jwapp 给的学期周数不可信：$maxWeekNum" }

        val weekly = queryAllWeeks(maxWeekNum, termCode)

        // 合并规则对照官方前端核实过，但学校至今没发过一条真实的调课记录可供验证。
        // 合并出岔子时宁可退回原始课表（等于没有调休信息），也不能把整张课表搞乱。
        val merged = runCatching { mergeWeeks(weekly, maxWeekNum) }
            .getOrElse { e ->
                Log.w(TAG, "调休合并失败，退回未合并课表", e)
                null
            }

        if (merged == null) {
            if (weekly.any { it.second.changes.size() > 0 }) {
                Log.w(TAG, "调休记录存在但未能合并，本次课表不含调休调整")
            }
            val fallback = aggregate(weekly.map { (week, raw) -> week to raw.theory }, maxWeekNum)
            return JwappScheduleResult(fallback, emptyList())
        }
        return merged
    }

    // ── 单周查询 ────────────────────────────────────────────

    internal data class WeekRaw(val theory: List<Occurrence>, val changes: JsonArray)

    /**
     * 整学期十几到二十个请求，串着发要等好几秒。小批量并发拉，够快又不至于
     * 一口气把二十个连接甩给学校网关。`executeWithReAuth` 本身按登录代数处理并发
     * 重认证，几个协程同时撞上令牌过期也只会重登一次。
     */
    private fun queryAllWeeks(maxWeekNum: Int, termCode: String): List<Pair<Int, WeekRaw>> =
        runBlocking {
            (1..maxWeekNum).chunked(WEEK_FETCH_CONCURRENCY).flatMap { chunk ->
                chunk.map { week -> async(Dispatchers.IO) { week to queryWeek(week, termCode) } }.awaitAll()
            }
        }

    private fun queryWeek(week: Int, termCode: String): WeekRaw {
        val payload = JsonObject().apply {
            addProperty("skzc", week)
            addProperty("xnxqdm", termCode)
        }
        val request = api.authenticatedRequest("$BASE_URL/api/biz/v410/schedule/querySchedule")
            .post(payload.toString().toRequestBody(jsonType))
        val root = api.execute(request).safeParseJsonObject()

        val code = root.get("code").safeInt(-1)
        if (code != 200) throw RuntimeException(root.get("msg").safeString("移动教务课表请求失败（$code）"))

        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return WeekRaw(emptyList(), JsonArray())
        val theory = data.get("theorySchedule")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val changes = data.get("changeSchedule")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        return WeekRaw(theory.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.toOccurrence() }, changes)
    }

    // ── 调休合并 ────────────────────────────────────────────

    private data class WeekMerge(val courses: List<Occurrence>, val events: List<ScheduleChangeEvent>)

    /** 一条去重后的变更记录，以及服务端在哪几周的响应里返回过它。 */
    private data class ObservedChange(val row: JsonObject, val observedWeeks: Set<Int>)

    /**
     * 把各周响应里的变更记录合到课表上。规则逐条对照官方 App 前端
     * （`pages-timetable-index` 的 `weekCourses` / `changeCourse` / `suspendCourse` /
     * `makeUpCourse`，2026-09 抓包核实）：
     *
     * - `01` 调课：查第 W 周时，`xskzc` 位串第 W 位为 1 就在 `xskxq`/`xksjc`~`xjsjc`/`xjasmc`
     *   加一节；`skzc` 第 W 位为 1 就从 `skxq` 那天找 `kbid` 相同、节次**包含** `ksjc`~`jsjc`
     *   的原始行，只挖掉这几节——可能只调走其中一段，剩下的节次照常上。
     * - `02` 停课：**不看周次**，出现在第 W 周的响应里就作用于第 W 周；同样按 `kbid` +
     *   包含关系定位，只挖掉被停的那几节。
     * - `03` 补课：同样不看周次，出现在哪周就在哪周的 `xskxq` 加一节。
     *
     * 记录先跨周汇总再逐周应用：`01` 靠位串定周，不依赖记录随哪一周返回；`02`/`03`
     * 与官方一致，只作用于返回它的那几周。
     */
    internal fun mergeWeeks(
        weekly: List<Pair<Int, WeekRaw>>,
        maxWeekNum: Int,
    ): JwappScheduleResult {
        val observed = LinkedHashMap<String, Pair<JsonObject, MutableSet<Int>>>()
        for ((week, raw) in weekly) {
            for (element in raw.changes) {
                val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val entry = observed.getOrPut(row.toString()) { row to linkedSetOf() }
                entry.second += week
            }
        }
        val changes = observed.values.map { (row, weeks) -> ObservedChange(row, weeks) }
        val merged = weekly.map { (week, raw) ->
            week to applyChanges(week, raw.theory, changes, maxWeekNum)
        }
        check(merged.all { (_, merge) -> merge.courses.all { it.looksSane() } }) {
            "调休合并后出现无效课程"
        }
        val courses = aggregate(merged.map { (week, merge) -> week to merge.courses }, maxWeekNum)
        return JwappScheduleResult(courses, collapseEvents(merged))
    }

    /**
     * 同一条变更在每一周各记一次事件，这里按内容合成一条，原时段/新时段各自的周次收进
     * [ScheduleChangeEvent.weeks] / [ScheduleChangeEvent.toWeeks]。不带周次的话，一门课
     * 连停三周只剩一句"停课"，跨周调课也看不出挪到了哪一周。
     */
    private fun collapseEvents(merged: List<Pair<Int, WeekMerge>>): List<ScheduleChangeEvent> {
        val byContent = LinkedHashMap<ScheduleChangeEvent, Pair<MutableSet<Int>, MutableSet<Int>>>()
        for ((_, merge) in merged) {
            for (event in merge.events) {
                val (from, to) = byContent.getOrPut(event.copy(weeks = emptyList(), toWeeks = emptyList())) {
                    sortedSetOf<Int>() to sortedSetOf()
                }
                from += event.weeks
                to += event.toWeeks
            }
        }
        return byContent.map { (event, weeks) -> event.copy(weeks = weeks.first.toList(), toWeeks = weeks.second.toList()) }
    }

    /**
     * 把变更记录合到第 [week] 周的原始课表上，同时收集人可读的变更事件（含官方备注 `bz`）。
     * 顺序与官方一致：先调课、再停课、最后补课。
     */
    private fun applyChanges(
        week: Int,
        theory: List<Occurrence>,
        changes: List<ObservedChange>,
        maxWeekNum: Int,
    ): WeekMerge {
        if (changes.isEmpty()) return WeekMerge(theory, emptyList())
        val result = theory.toMutableList()
        val events = mutableListOf<ScheduleChangeEvent>()
        val byType = changes.groupBy { it.row.get("tklxdm").safeString().trim().trimStart('0') }

        for (change in byType["1"].orEmpty()) {
            val row = change.row
            val loc = ChangeLocator.of(row)
            val hitsOrigin = weekMarked(row.get("skzc"), week, change.observedWeeks, maxWeekNum)
            val hitsTarget = weekMarked(row.get("xskzc"), week, change.observedWeeks, maxWeekNum)
            if (!hitsOrigin && !hitsTarget) continue

            // 模板优先取本周的原始行：教师、课程性质、钟点都在上面；找不到才用记录本身
            val template = result.firstOrNull { loc.locates(it) } ?: row.toOccurrence()
            if (hitsTarget) {
                val added = template?.let { moveTarget(it, row) }
                if (added != null) result += added
                else Log.w(TAG, "第${week}周调课缺少课程信息，跳过新时段：$row")
            }
            if (hitsOrigin) {
                result.firstOrNull { loc.locates(it) }?.let { carve(result, it, loc.start, loc.end) }
            }
            val name = template?.courseName ?: row.get("kcm").safeString()
            if (name.isNotBlank()) events += ScheduleChangeEvent(
                courseName = name,
                courseCode = template?.courseCode ?: row.get("kch").safeString(),
                kind = ScheduleChangeEvent.Kind.MOVED,
                fromDay = loc.day,
                fromStartSection = loc.start,
                fromEndSection = loc.end,
                toDay = row.get("xskxq").safeInt(0),
                toStartSection = row.get("xksjc").safeInt(0),
                toEndSection = row.get("xjsjc").safeInt(0),
                toLocation = row.get("xjasmc").safeString(),
                reason = row.get("bz").safeString().trim(),
                weeks = if (hitsOrigin) listOf(week) else emptyList(),
                toWeeks = if (hitsTarget) listOf(week) else emptyList(),
            )
        }

        for (change in byType["2"].orEmpty()) {
            if (week !in change.observedWeeks) continue
            val row = change.row
            val loc = ChangeLocator.of(row)
            val origin = result.firstOrNull { loc.locates(it) }
            if (origin != null) carve(result, origin, loc.start, loc.end)
            val name = origin?.courseName ?: row.get("kcm").safeString()
            if (name.isNotBlank()) events += ScheduleChangeEvent(
                courseName = name,
                courseCode = origin?.courseCode ?: row.get("kch").safeString(),
                kind = ScheduleChangeEvent.Kind.CANCELLED,
                fromDay = loc.day,
                fromStartSection = loc.start,
                fromEndSection = loc.end,
                toDay = 0,
                toStartSection = 0,
                toEndSection = 0,
                toLocation = "",
                reason = row.get("bz").safeString().trim(),
                weeks = listOf(week),
            )
        }

        for (change in byType["3"].orEmpty()) {
            if (week !in change.observedWeeks) continue
            val row = change.row
            val base = row.toOccurrence() ?: run {
                Log.w(TAG, "第${week}周补课缺少课程信息，跳过：$row")
                continue
            }
            // 补课记录不一定带任课教师，拿同一门课的原始行补上
            val sibling = theory.firstOrNull { it.courseCode.isNotBlank() && it.courseCode == base.courseCode }
            val added = moveTarget(base.copy(teacher = base.teacher.ifBlank { sibling?.teacher.orEmpty() }), row)
                ?: continue
            result += added
            events += ScheduleChangeEvent(
                courseName = added.courseName,
                courseCode = added.courseCode,
                kind = ScheduleChangeEvent.Kind.ADDED,
                fromDay = 0,
                fromStartSection = 0,
                fromEndSection = 0,
                toDay = added.dayOfWeek,
                toStartSection = added.startSection,
                toEndSection = added.endSection,
                toLocation = added.location,
                reason = row.get("bz").safeString().trim(),
                toWeeks = listOf(week),
            )
        }
        return WeekMerge(result, events)
    }

    /**
     * 变更记录里"原来那节课"的坐标：`kbid` + `skxq` + `ksjc`~`jsjc`。
     *
     * 官方按 `kbid` 相同、同一天、原始行节次**包含**记录节次来找；调课/补课加出来的行
     * 不算原始行，否则同一 `kbid` 的第二条记录可能把刚调进来的课又挖掉。
     * `kbid` 缺失时退一步按课程号认，免得整条记录落空。
     */
    private class ChangeLocator(
        val kbid: String,
        val courseCode: String,
        val day: Int,
        val start: Int,
        val end: Int,
    ) {
        fun locates(o: Occurrence): Boolean {
            if (o.fromChange || o.dayOfWeek != day) return false
            if (start <= 0 || end < start || o.startSection > start || end > o.endSection) return false
            return if (kbid.isNotBlank() && o.kbid.isNotBlank()) o.kbid == kbid
            else courseCode.isNotBlank() && o.courseCode == courseCode
        }

        companion object {
            fun of(row: JsonObject) = ChangeLocator(
                kbid = row.get("kbid").safeString(),
                courseCode = row.get("kch").safeString(),
                day = row.get("skxq").safeInt(0),
                start = row.get("ksjc").safeInt(0),
                end = row.get("jsjc").safeInt(0),
            )
        }
    }

    /**
     * 从 [origin] 里挖掉第 [start]~[end] 节，与官方的四种情形一致：整段去掉、掐头、
     * 去尾、从中间劈成两段。被截短的那段钟点作废（-1），交给 UI 按节次换算。
     */
    private fun carve(result: MutableList<Occurrence>, origin: Occurrence, start: Int, end: Int) {
        val index = result.indexOf(origin)
        if (index < 0) return
        val pieces = buildList {
            if (origin.startSection < start) add(origin.copy(endSection = start - 1, startMinute = -1, endMinute = -1))
            if (end < origin.endSection) add(origin.copy(startSection = end + 1, startMinute = -1, endMinute = -1))
        }
        result.removeAt(index)
        result.addAll(index, pieces)
    }

    /**
     * 调课/补课的新时段：星期、节次、教室取记录里的 `xskxq`/`xksjc`/`xjsjc`/`xjasmc`，缺了沿用 [template]。
     *
     * 钟点只在"只换教室"时沿用。换了星期或节次就清掉：记录不给新钟点，而夏令时/冬令时
     * 按日期切换，同一节次换一天、换一周都可能对应不同的钟点，交给 UI 按当天作息换算。
     */
    private fun moveTarget(template: Occurrence, row: JsonObject): Occurrence? {
        if (template.courseName.isBlank()) return null
        val day = row.get("xskxq").safeInt(0).takeIf { it > 0 } ?: template.dayOfWeek
        val start = row.get("xksjc").safeInt(0).takeIf { it > 0 } ?: template.startSection
        val end = row.get("xjsjc").safeInt(0).takeIf { it > 0 } ?: template.endSection
        val sameSlot = day == template.dayOfWeek && start == template.startSection && end == template.endSection
        return template.copy(
            dayOfWeek = day,
            startSection = start,
            endSection = end,
            location = row.get("xjasmc").safeString().ifBlank { template.location },
            startMinute = if (sameSlot) template.startMinute else -1,
            endMinute = if (sameSlot) template.endMinute else -1,
            fromChange = true,
        )
    }

    /**
     * 这条调课记录管不管第 [week] 周。
     *
     * 官方把 `skzc`/`xskzc` 当位串用（第 W 位为 1 即第 W 周），长度就是学期周数。
     * 位串要求不短于 [maxWeekNum]，免得把 `"10"` 这种周次数字误当位串；单个数字、
     * `3,5`、`3-5` 也认。字段缺失或认不出时，只作用于服务端实际返回它的周。
     */
    private fun weekMarked(element: JsonElement?, week: Int, observedWeeks: Set<Int>, maxWeekNum: Int): Boolean {
        val raw = element.safeString().trim()
        if (raw.isEmpty()) return week in observedWeeks
        if (raw.length >= maxWeekNum && raw.all { it == '0' || it == '1' }) {
            return raw.getOrNull(week - 1) == '1'
        }
        val parsed = raw.split(',', '，', '、').flatMap { part ->
            val range = part.trim().split('-', '~')
            when {
                range.size == 2 -> {
                    val a = range[0].trim().toIntOrNull()
                    val b = range[1].trim().toIntOrNull()
                    if (a != null && b != null && a <= b) (a..b).toList() else emptyList()
                }
                else -> listOfNotNull(part.trim().toIntOrNull())
            }
        }
        return if (parsed.isEmpty()) week in observedWeeks else week in parsed
    }

    // ── 聚合成 CourseItem ───────────────────────────────────

    /**
     * 同一节课在多周出现，合成一条 [CourseItem]，周次落到 `weekBits`。
     *
     * 身份取「课程号 + 星期 + 起止节次 + 教室」。教室必须参与聚合：若只有某一周换教室，
     * 排除教室会把两个地点压成一条，并沿用最早一周的地点，导致临时换教室完全不可见。
     * 于是同一节课可能落成几条，按课程汇总的地方（学期课程表、`ScheduleDiff`）自己再合。
     */
    private fun aggregate(weekly: List<Pair<Int, List<Occurrence>>>, maxWeekNum: Int): List<CourseItem> {
        val bits = LinkedHashMap<String, CharArray>()
        val sample = LinkedHashMap<String, Occurrence>()
        for ((week, list) in weekly) {
            for (occurrence in list) {
                val key = occurrence.identity()
                sample.getOrPut(key) { occurrence }
                bits.getOrPut(key) { CharArray(maxWeekNum) { '0' } }[week - 1] = '1'
            }
        }
        return sample.map { (key, occurrence) ->
            CourseItem(
                courseName = occurrence.courseName,
                teacher = occurrence.teacher,
                location = occurrence.location,
                weekBits = String(bits.getValue(key)),
                dayOfWeek = occurrence.dayOfWeek,
                startSection = occurrence.startSection,
                endSection = occurrence.endSection,
                courseCode = occurrence.courseCode,
                courseType = occurrence.courseType,
                startMinuteOfDay = occurrence.startMinute,
                endMinuteOfDay = occurrence.endMinute,
            )
        }
    }

    // ── 单次上课 ────────────────────────────────────────────

    internal data class Occurrence(
        val courseName: String,
        val teacher: String,
        val location: String,
        val dayOfWeek: Int,
        val startSection: Int,
        val endSection: Int,
        val courseCode: String,
        val courseType: String,
        val startMinute: Int,
        val endMinute: Int,
        val kbid: String,
        /** 由调课/补课记录加出来的行。找"原始行"时跳过它们，见 [ChangeLocator]。 */
        val fromChange: Boolean = false,
    ) {
        /**
         * 聚合身份。钟点也算进去：留下来的只有作息表外的特殊钟点（见 [clockTimes]），
         * 某几周不一样时不能被第一周的覆盖。
         */
        fun identity() =
            "${courseCode.ifBlank { courseName }}|$dayOfWeek|$startSection|$endSection|${location.trim()}|$startMinute|$endMinute"

        fun looksSane(): Boolean =
            courseName.isNotBlank() &&
                dayOfWeek in 1..7 &&
                startSection in 1..MAX_REASONABLE_SECTION &&
                endSection in startSection..MAX_REASONABLE_SECTION
    }

    private fun JsonObject.toOccurrence(): Occurrence? {
        val name = get("kcm").safeString()
        if (name.isBlank()) return null
        val start = get("ksjc").safeInt(0)
        val end = get("jsjc").safeInt(0)
        val (startMinute, endMinute) = clockTimes(
            start, end,
            get("starttime").safeString().toMinuteOfDay(),
            get("endtime").safeString().toMinuteOfDay(),
        )
        return Occurrence(
            courseName = name,
            teacher = get("skjs").safeString(),
            location = get("jasmc").safeString(),
            dayOfWeek = get("skxq").safeInt(0),
            startSection = start,
            endSection = end,
            courseCode = get("kch").safeString(),
            courseType = get("kcxzmc").safeString(),
            startMinute = startMinute,
            endMinute = endMinute,
            kbid = get("kbid").safeString(),
        )
    }

    /**
     * jwapp 给的钟点**不随夏令时/冬令时变**：2026-09 抓包，秋季学期第 1–12 周（跨过 10 月 1 日）
     * 周一第 5 节一律是 14:30，而 10 月起冬令时是 14:00。照单全收的话，10 月以后每节下午课
     * 都显示晚半小时。
     *
     * 所以钟点和作息表对得上（夏令或冬令任一套）就不留，交给 UI 按当天日期换算；
     * 只保留作息表外的特殊钟点（比如体育课、实验课单独排的时间）。
     * 连堂课的 `endtime` 可能只到第一小节下课，也按标准处理，见 [XjtuTime.isStandardSpan]。
     */
    internal fun clockTimes(startSection: Int, endSection: Int, startMinute: Int, endMinute: Int): Pair<Int, Int> {
        if (startMinute < 0 || endMinute < 0) return -1 to -1
        val standard = XjtuTime.isStandardSpan(startSection, endSection, startMinute, endMinute)
        return if (standard) -1 to -1 else startMinute to endMinute
    }

    /** `"14:30"` → 870。认不出返回 -1，与 [CourseItem] 的"未提供"约定一致。 */
    private fun String.toMinuteOfDay(): Int {
        val parts = trim().split(':')
        if (parts.size != 2) return -1
        val hour = parts[0].toIntOrNull() ?: return -1
        val minute = parts[1].toIntOrNull() ?: return -1
        if (hour !in 0..23 || minute !in 0..59) return -1
        return hour * 60 + minute
    }

    private companion object {
        const val BASE_URL = "https://jwapp.xjtu.edu.cn"
        const val MAX_REASONABLE_WEEKS = 30
        const val MAX_REASONABLE_SECTION = 20
        const val WEEK_FETCH_CONCURRENCY = 4
    }
}
