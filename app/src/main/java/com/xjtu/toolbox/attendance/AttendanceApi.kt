package com.xjtu.toolbox.attendance

import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.schedule.XjtuTime
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 考勤（kq.xjtu.edu.cn）接口。site 应当是 `ensureSite(LoginType.ATTENDANCE)` 拿到的会话。
 */
class AttendanceApi(private val site: SiteSession) {

    private val jsonType = "application/json".toMediaType()
    @Volatile private var cachedTerms: List<TermInfo> = emptyList()

    private fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonObject {
        val req = Request.Builder().url(KqHttp.buildUrl(site, path, query)).get().build()
        return KqHttp.execute(site, req, path, retryable = true)
    }

    fun getStudentInfo(): Map<String, Any> {
        val root = getJson("/student/profile")
        var obj = KqHttp.dataObject(root)
        obj = KqHttp.obj(obj.get("user")) ?: KqHttp.obj(obj.get("student")) ?: obj
        return mapOf(
            "name" to KqHttp.str(obj, "name", "studentName", "userName", "realName"),
            "sno" to KqHttp.str(obj, "studentNo", "personNo", "sno", "studentNumber"),
            "identity" to "",
            "campusName" to "",
            "departmentName" to KqHttp.str(obj, "collegeName", "college", "facultyName", "departmentName"),
        )
    }

    fun getTermList(): List<TermInfo> {
        val root = getJson("/student/service/timetable/semesters")
        val rows = KqHttp.rows(root.get("data")).ifEmpty { KqHttp.rows(root) }
        val terms = rows.mapNotNull { row ->
            val bh = KqHttp.str(row, "semesterId", "termId", "id", "semesterCode", "termNo")
            if (bh.isBlank()) return@mapNotNull null
            val year = KqHttp.str(row, "academicYear", "schoolYear", "year")
            val semesterName = KqHttp.str(row, "semesterName", "termName", "name")
            val name = when {
                year.isNotBlank() && semesterName.isNotBlank() -> "$year $semesterName"
                KqHttp.str(row, "name", "displayName").isNotBlank() -> KqHttp.str(row, "name", "displayName")
                semesterName.isNotBlank() -> semesterName
                else -> bh
            }
            TermInfo(
                bh = bh,
                name = name,
                startDate = normalizeDate(KqHttp.str(row, "startDate", "beginDate", "startTime")),
                endDate = normalizeDate(KqHttp.str(row, "endDate", "finishDate", "endTime")),
                code = termCodeOf(year, semesterName),
            )
        }.distinctBy { it.bh }.sortedByDescending { it.startDate }
        cachedTerms = terms
        return terms
    }

    private fun termCodeOf(year: String, semesterName: String): String = TermCodeMapper.termCodeOf(year, semesterName)

    /**
     * 当前学期直接取列表第 0 个——接口按当前学期在前排序，不用再猜 current/isCurrent
     * 标志位，也不用为此多打一次请求。日期兜底只在列表异常（比如年初还没排出新学期）
     * 时才用得上。
     */
    fun getTermBh(): String {
        val terms = cachedTerms.ifEmpty { getTermList() }
        if (terms.isEmpty()) return ""
        val first = terms.first()
        val today = LocalDate.now().toString()
        if (first.startDate.isBlank() || first.endDate.isBlank() ||
            (first.startDate <= today && today <= first.endDate)
        ) {
            return first.bh
        }
        return terms.firstOrNull { it.startDate.isNotBlank() && it.endDate.isNotBlank() && it.startDate <= today && today <= it.endDate }?.bh
            ?: first.bh
    }

    fun getWaterRecords(termBh: String? = null, startDate: String = "", endDate: String = ""): List<AttendanceWaterRecord> {
        val terms = cachedTerms.ifEmpty { runCatching { getTermList() }.getOrDefault(emptyList()) }
        val bh = termBh ?: getTermBh()
        val term = terms.firstOrNull { it.bh == bh }
        val start = normalizeDate(startDate).ifBlank { term?.startDate.orEmpty() }
        val end = normalizeDate(endDate).ifBlank { LocalDate.now().toString() }
        val rows = fetchAttendanceRecords(bh, start, end)
        return rows.mapNotNull { row ->
            val courseName = KqHttp.str(row, "courseName", "course")
            val courseCode = KqHttp.str(row, "courseCode", "courseNo")
            if (courseName.isBlank() && courseCode.isBlank()) return@mapNotNull null
            val date = recordDate(row)
            val startSection = KqHttp.int(row, "startSection", "startJc").takeIf { it > 0 }
                ?: sectionFromTime(KqHttp.str(row, "startTime", "beginTime"))
            val endSection = KqHttp.int(row, "endSection", "endJc").takeIf { it > 0 }
                ?: sectionFromTime(KqHttp.str(row, "endTime", "finishTime"))
            val id = KqHttp.str(row, "resultId", "id", "recordId").ifBlank {
                listOf(bh, date, courseCode, courseName, startSection).joinToString("|")
            }
            AttendanceWaterRecord(
                sbh = id,
                termString = term?.code?.ifBlank { term.name }.orEmpty(),
                startTime = startSection,
                endTime = endSection,
                week = weekOf(term?.startDate.orEmpty(), date),
                location = KqHttp.str(row, "classroomName", "classroom", "location"),
                courseName = courseName.ifBlank { courseCode },
                courseCode = courseCode,
                teacher = KqHttp.str(row, "teacherName", "teacher"),
                status = WaterType.fromCode(KqHttp.str(row, "attendanceStatus", "status")),
                date = date,
            )
        }.sortedWith(compareByDescending<AttendanceWaterRecord> { it.date }.thenByDescending { it.startTime })
    }

    fun getKqtjCurrentWeek(): List<CourseAttendanceStat> {
        return try {
            val root = getJson("/student/pc/home/attendance-statistics")
            parseCourseStats(root.get("data")).ifEmpty { parseCourseStats(root) }
                .ifEmpty {
                    val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    getKqtjByTime(monday.toString(), monday.plusDays(6).toString())
                }
        } catch (e: AuthExpiredException) {
            throw e
        } catch (_: Exception) {
            val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            getKqtjByTime(monday.toString(), monday.plusDays(6).toString())
        }
    }

    fun getKqtjByTime(startDate: String, endDate: String): List<CourseAttendanceStat> {
        val bh = getTermBh()
        val rows = try {
            fetchAttendanceRecords(bh, normalizeDate(startDate), normalizeDate(endDate))
        } catch (e: AuthExpiredException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        return aggregateCourseStats(rows)
    }

    /**
     * 按学期取整学期课表（`/student/service/timetable/weekly`，尽管路径带 weekly，
     * 实测不传周次也会把整学期的行一次性返回，同一门课跨越的不同周段会拆成多行，
     * 靠 weekRanges 区分，如 "1-8,10-16"）。调用方（[com.xjtu.toolbox.schedule.ScheduleSourceRouter]）
     * 负责按 (name, teacher, room, day, start, end) 合并这些行。
     */
    fun getWeeklyTimetable(semesterId: String): List<KqTimetableRow> {
        val root = getJson("/student/service/timetable/weekly", mapOf("semesterId" to semesterId))
        val data = KqHttp.obj(root.get("data"))
        val courseRows = data?.get("courses")?.let { KqHttp.rows(it) } ?: KqHttp.rows(root.get("data"))
        return courseRows.mapNotNull { row ->
            val name = KqHttp.str(row, "courseName", "course")
            if (name.isBlank()) return@mapNotNull null
            val day = KqHttp.int(row, "dayOfWeek", "day")
            val start = KqHttp.int(row, "startSection", "startJc")
            val end = KqHttp.int(row, "endSection", "endJc").takeIf { it > 0 } ?: start
            if (day !in 1..7 || start <= 0) return@mapNotNull null
            KqTimetableRow(
                courseName = name,
                teacherName = KqHttp.str(row, "teacherName", "teacher"),
                classroomName = KqHttp.str(row, "classroomName", "classroom", "location"),
                courseCode = KqHttp.str(row, "courseCode", "courseNo"),
                dayOfWeek = day,
                startSection = start,
                endSection = end,
                weekRanges = KqHttp.str(row, "weekRanges", "weeks"),
            )
        }
    }

    fun computeCourseStatsFromRecords(records: List<AttendanceWaterRecord>): List<CourseAttendanceStat> {
        if (records.isEmpty()) return emptyList()
        return records.groupBy { it.courseName }
            .filter { it.key.isNotEmpty() }
            .map { (name, recs) ->
                CourseAttendanceStat(
                    subjectName = name,
                    subjectCode = recs.firstOrNull()?.courseCode.orEmpty(),
                    normalCount = recs.count { it.status == WaterType.NORMAL },
                    lateCount = recs.count { it.status == WaterType.LATE },
                    absenceCount = recs.count { it.status == WaterType.ABSENCE },
                    leaveEarlyCount = 0,
                    leaveCount = recs.count { it.status == WaterType.LEAVE },
                    total = recs.size,
                )
            }
    }

    /**
     * 考勤流水分页。
     *
     * 路径里的 `pc` 那一段不能省：网页端 PC 版实测走的是
     * `/sa/student/pc/attendance-records/page`，而 `profile`、`service/timetable`
     * 这类跨端共用的接口才没有这一段。我们登录时申请的就是 `student-pc` 终端，
     * 路径也要对上同一个终端。
     */
    private fun fetchAttendanceRecords(term: String, startDate: String, endDate: String): List<JsonObject> {
        val data = JsonObject().apply {
            addProperty("startDate", startDate)
            addProperty("endDate", endDate)
            addProperty("courseName", "")
            addProperty("courseCode", "")
            addProperty("attendanceStatus", "")
            if (term.isNotBlank()) addProperty("semesterId", term)
        }
        val rows = fetchAllPages("/student/pc/attendance-records/page", data)
        // 服务端状态全集（上游 PR #72 取自前端状态标签）：PENDING / NORMAL / LATE / ABSENT / LEAVE / NOT_REQUIRED。
        // PENDING（待考勤，课还没上完或还没出结果）和 NOT_REQUIRED（不考勤）都不是考勤结果，丢掉；
        // 留着的话 PENDING 会落进 WaterType.UNKNOWN，课表角标按「最坏」标红，今天还没上的课全是红的。
        // 已出结果的记录由增量/全量刷新补上。
        return rows.filter { row ->
            KqHttp.str(row, "attendanceStatus", "status").uppercase() !in NON_RESULT_STATUSES
        }
    }

    private companion object {
        val NON_RESULT_STATUSES = setOf("NOT_REQUIRED", "PENDING")
    }

    /**
     * 考勤打卡流水分页，跟 [fetchAttendanceRecords] 同一套接口形状，字段不同。
     */
    fun getStreams(startDate: String, endDate: String): List<AttendanceStream> {
        val data = JsonObject().apply {
            addProperty("startDate", startDate)
            addProperty("endDate", endDate)
        }
        val rows = fetchAllPages("/student/pc/attendance-streams/page", data)
        return rows.map { row ->
            AttendanceStream(
                id = KqHttp.str(row, "id", "streamId"),
                location = KqHttp.str(row, "classroomName", "classroom", "location"),
                collectTime = KqHttp.str(row, "collectTime", "time"),
                effective = KqHttp.bool(row, "effective", "isEffective"),
            )
        }
    }

    /**
     * 通用分页拉取：每页 50 条，靠 `data.total` 判断是否还有下一页，最多拉 40 页
     * （2000 条），避免账号数据异常时无限翻页。以前是单页 pageSize=500 硬取，
     * 数据量一旦超过 500 条（比如整年流水）后面的就直接丢了。
     */
    private fun fetchAllPages(path: String, data: JsonObject): List<JsonObject> {
        val pageSize = 50
        val maxPages = 40
        val result = mutableListOf<JsonObject>()
        var page = 1
        while (page <= maxPages) {
            val req = Request.Builder()
                .url(KqHttp.buildUrl(site, path))
                .post(KqHttp.pagePayload(data, pageNum = page, pageSize = pageSize).toString().toRequestBody(jsonType))
                .build()
            val root = KqHttp.execute(site, req, path, retryable = true)
            val rows = KqHttp.rows(root.get("data")).ifEmpty { KqHttp.rows(root) }
            result += rows
            val total = KqHttp.total(root)
            // total 取不到时是 0，不能拿它当"已经取完"——只靠不满一页来判断结束。
            if (rows.isEmpty() || (total > 0 && result.size >= total) || rows.size < pageSize) break
            page++
        }
        return result
    }

    private fun aggregateCourseStats(records: List<JsonObject>): List<CourseAttendanceStat> {
        return records.groupBy { row ->
            KqHttp.str(row, "courseCode", "courseNo").ifBlank { KqHttp.str(row, "courseName", "course") }
        }.mapNotNull { (key, recs) ->
            if (key.isBlank()) return@mapNotNull null
            val sample = recs.first()
            val normal = recs.count { WaterType.fromCode(KqHttp.str(it, "attendanceStatus", "status")) == WaterType.NORMAL }
            val late = recs.count { WaterType.fromCode(KqHttp.str(it, "attendanceStatus", "status")) == WaterType.LATE }
            val absence = recs.count { WaterType.fromCode(KqHttp.str(it, "attendanceStatus", "status")) == WaterType.ABSENCE }
            val leave = recs.count { WaterType.fromCode(KqHttp.str(it, "attendanceStatus", "status")) == WaterType.LEAVE }
            CourseAttendanceStat(
                subjectName = KqHttp.str(sample, "courseName", "course"),
                subjectCode = KqHttp.str(sample, "courseCode", "courseNo"),
                normalCount = normal,
                lateCount = late,
                absenceCount = absence,
                leaveEarlyCount = 0,
                leaveCount = leave,
                total = normal + late + absence + leave,
            )
        }
    }

    private fun parseCourseStats(data: com.google.gson.JsonElement?): List<CourseAttendanceStat> {
        return KqHttp.rows(data).mapNotNull { obj ->
            val name = KqHttp.str(obj, "courseName", "subjectname", "course")
            if (name.isBlank()) return@mapNotNull null
            CourseAttendanceStat(
                subjectName = name,
                subjectCode = KqHttp.str(obj, "courseCode", "subjectCode", "courseNo"),
                normalCount = KqHttp.int(obj, "normalCount"),
                lateCount = KqHttp.int(obj, "lateCount"),
                absenceCount = KqHttp.int(obj, "absenceCount"),
                leaveEarlyCount = KqHttp.int(obj, "leaveEarlyCount"),
                leaveCount = KqHttp.int(obj, "leaveCount"),
                total = KqHttp.int(obj, "total"),
            )
        }
    }

    private fun recordDate(row: JsonObject): String {
        val raw = KqHttp.str(row, "courseDate", "date", "attendanceDate", "checkDate")
        return normalizeDate(raw).ifBlank {
            normalizeDate(KqHttp.str(row, "collectTime", "attendanceTime"))
        }
    }

    private fun normalizeDate(value: String): String {
        if (value.isBlank()) return ""
        val head = value.trim().take(10)
        return runCatching { LocalDate.parse(head).toString() }.getOrDefault(head)
    }

    private fun weekOf(termStart: String, dateText: String): Int {
        val start = runCatching { LocalDate.parse(normalizeDate(termStart)) }.getOrNull() ?: return 0
        val date = runCatching { LocalDate.parse(normalizeDate(dateText)) }.getOrNull() ?: return 0
        if (date.isBefore(start)) return 0
        return (ChronoUnit.DAYS.between(start, date) / 7).toInt() + 1
    }

    private fun sectionFromTime(value: String): Int {
        val text = value.trim()
        if (text.isBlank()) return 0
        val time = runCatching {
            val hm = text.substringAfter(' ').ifBlank { text }.take(5)
            LocalTime.parse(hm)
        }.getOrNull() ?: return 0
        return (1..11).minByOrNull { section ->
            val start = XjtuTime.getClassTime(section)?.start ?: return@minByOrNull Int.MAX_VALUE
            kotlin.math.abs(ChronoUnit.MINUTES.between(start, time).toInt())
        } ?: 0
    }
}
