package com.xjtu.toolbox.jwapp

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ScheduleChangeEvent
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

        // 调休合并整段包起来：这套字段是从学校前端 bundle 反推的，本学期还没遇上过真正的
        // 调课，没有任何真实样本验证过。合并出岔子时宁可退回原始课表（等于没有调休信息），
        // 也不能让一个没验证过的算法把整张课表搞乱。
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
        return WeekRaw(theory.mapNotNull { it.asJsonObject.toOccurrence() }, changes)
    }

    // ── 调休合并 ────────────────────────────────────────────

    private data class WeekMerge(val courses: List<Occurrence>, val events: List<ScheduleChangeEvent>)

    private data class ObservedChange(val row: JsonObject, val observedWeeks: Set<Int>)

    /**
     * 先汇总所有周响应里的变更，再把它们分别应用到原周和目标周。
     *
     * 跨周调课记录可能只随原周响应返回。旧实现逐周只看该周响应，因此能删掉原课，
     * 却无法在目标周补上课程。这里保留记录出现过的周次，供缺失/陌生周次字段安全回退；
     * 字段明确写了原周或目标周时，则不依赖记录随哪一周返回。
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
            week to applyChanges(week, raw.theory, changes)
        }
        check(merged.all { (_, merge) -> merge.courses.all { it.looksSane() } }) {
            "调休合并后出现无效课程"
        }
        val courses = aggregate(merged.map { (week, merge) -> week to merge.courses }, maxWeekNum)
        val events = merged.flatMap { (_, merge) -> merge.events }.distinct()
        return JwappScheduleResult(courses, events)
    }

    /**
     * 把这一周的调课记录合到这一周的原始课表上，同时收集人可读的变更事件（含官方备注）。
     *
     * `tklxdm`：01 移课（原时段删掉、新时段加上）、02 停课（只删）、03 新增（只加）。
     * 移课/停课靠 `kbid` 找到被改的那一行；新增没有原始行，课程信息只能取自记录本身，
     * 取不到就跳过这一条——漏掉一节新增课与"整表退回未合并"结果相当，但不会牵连其他记录。
     *
     * `ksjc`/`jsjc` 只是原始行的节次，用来定位被改的那一行；新节次/新教室在
     * `xksjc`/`xjsjc`/`xjasmc`——这三个字段名是从官方 App 自己的合并逻辑核实的，
     * 早前版本把 `ksjc`/`jsjc` 兼当新节次用是错的，同天换节次或换教室会显示原节次/原教室。
     */
    private fun applyChanges(
        week: Int,
        theory: List<Occurrence>,
        changes: List<ObservedChange>,
    ): WeekMerge {
        if (changes.isEmpty()) return WeekMerge(theory, emptyList())
        var result = theory
        val events = mutableListOf<ScheduleChangeEvent>()
        for (change in changes) {
            val row = change.row
            val type = row.get("tklxdm").safeString().trim().trimStart('0').ifEmpty { "0" }
            val kbid = row.get("kbid").safeString()
            val fromDay = row.get("skxq").safeInt(0)
            val toDay = row.get("xskxq").safeInt(0)
            // 只用于定位原始行，不是新节次。
            val originStartSection = row.get("ksjc").safeInt(0)
            val originEndSection = row.get("jsjc").safeInt(0)
            val toStartSection = row.get("xksjc").safeInt(0)
            val toEndSection = row.get("xjsjc").safeInt(0)
            val toLocation = row.get("xjasmc").safeString()
            val reason = row.get("bz").safeString().trim()
            val hitsOrigin = weekMarked(row.get("skzc"), week, change.observedWeeks)
            val hitsTarget = weekMarked(row.get("xskzc"), week, change.observedWeeks)

            val origin = result.firstOrNull { it.matches(kbid, fromDay, originStartSection, originEndSection) }
            val name = origin?.courseName ?: row.get("kcm").safeString()
            val code = origin?.courseCode ?: row.get("kch").safeString()

            if (type == "1" || type == "2") {
                if (hitsOrigin && origin != null) result = result - origin
                // 有些响应的 theorySchedule 已经先删掉原课；此时仍保留官方变更事件和备注。
                if (hitsOrigin && name.isNotBlank()) events += ScheduleChangeEvent(
                    courseName = name,
                    courseCode = code,
                    kind = if (type == "1") ScheduleChangeEvent.Kind.MOVED else ScheduleChangeEvent.Kind.CANCELLED,
                    fromDay = fromDay,
                    fromStartSection = originStartSection,
                    fromEndSection = originEndSection,
                    toDay = if (type == "1") toDay else 0,
                    toStartSection = if (type == "1") toStartSection else 0,
                    toEndSection = if (type == "1") toEndSection else 0,
                    toLocation = if (type == "1") toLocation else "",
                    reason = reason,
                )
            }
            if (type == "1" || type == "3") {
                if (!hitsTarget) continue
                val template = origin ?: row.toOccurrence() ?: run {
                    Log.w(TAG, "第${week}周新增课缺少课程信息，跳过：$row")
                    continue
                }
                val effectiveStart = toStartSection.takeIf { it > 0 } ?: template.startSection
                val effectiveEnd = toEndSection.takeIf { it > 0 } ?: template.endSection
                val sectionsChanged = effectiveStart != template.startSection || effectiveEnd != template.endSection
                val added = template.copy(
                    dayOfWeek = toDay.takeIf { it > 0 } ?: template.dayOfWeek,
                    startSection = effectiveStart,
                    endSection = effectiveEnd,
                    location = toLocation.ifBlank { template.location },
                    // 变更记录只给目标节次，不给可靠的目标钟点；清空旧钟点，让 UI 按新节次换算。
                    startMinute = if (sectionsChanged) -1 else template.startMinute,
                    endMinute = if (sectionsChanged) -1 else template.endMinute,
                )
                result = result + added
                if (type == "3") {
                    events += ScheduleChangeEvent(
                        courseName = name,
                        courseCode = code,
                        kind = ScheduleChangeEvent.Kind.ADDED,
                        fromDay = 0,
                        fromStartSection = 0,
                        fromEndSection = 0,
                        toDay = added.dayOfWeek,
                        toStartSection = added.startSection,
                        toEndSection = added.endSection,
                        toLocation = added.location,
                        reason = reason,
                    )
                }
            }
        }
        return WeekMerge(result, events)
    }

    /**
     * 这条调课记录管不管第 [week] 周。
     *
     * 周次字段的写法没有实样可依：可能是 `000100…` 位串、单个周次数字，也可能是
     * `3,5` / `3-5` 这样的枚举。都认；认不出（字段缺失或格式陌生）时，仅应用到
     * 服务端实际返回这条记录的周，避免汇总所有周后把一条无周次记录扩散到整学期。
     */
    private fun weekMarked(element: JsonElement?, week: Int, observedWeeks: Set<Int>): Boolean {
        val raw = element.safeString().trim()
        if (raw.isEmpty()) return week in observedWeeks
        if (raw.length > 4 && raw.all { it == '0' || it == '1' }) {
            return raw.getOrNull(week - 1) == '1'
        }
        raw.toIntOrNull()?.let { return it == week }
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
     * `ScheduleDiff` 仍可用较宽松的身份口径把它识别成同一节课的位置变化。
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
    ) {
        fun identity() =
            "${courseCode.ifBlank { courseName }}|$dayOfWeek|$startSection|$endSection|${location.trim()}"

        fun matches(kbid: String, day: Int, start: Int, end: Int): Boolean =
            if (kbid.isNotBlank() && this.kbid.isNotBlank()) this.kbid == kbid
            else dayOfWeek == day && startSection == start && endSection == end

        fun looksSane(): Boolean =
            courseName.isNotBlank() &&
                dayOfWeek in 1..7 &&
                startSection in 1..MAX_REASONABLE_SECTION &&
                endSection in startSection..MAX_REASONABLE_SECTION
    }

    private fun JsonObject.toOccurrence(): Occurrence? {
        val name = get("kcm").safeString()
        if (name.isBlank()) return null
        return Occurrence(
            courseName = name,
            teacher = get("skjs").safeString(),
            location = get("jasmc").safeString(),
            dayOfWeek = get("skxq").safeInt(0),
            startSection = get("ksjc").safeInt(0),
            endSection = get("jsjc").safeInt(0),
            courseCode = get("kch").safeString(),
            courseType = get("kcxzmc").safeString(),
            startMinute = get("starttime").safeString().toMinuteOfDay(),
            endMinute = get("endtime").safeString().toMinuteOfDay(),
            kbid = get("kbid").safeString(),
        )
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
