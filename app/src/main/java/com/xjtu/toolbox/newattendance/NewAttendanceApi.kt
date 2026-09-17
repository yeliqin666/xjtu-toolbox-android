package com.xjtu.toolbox.newattendance

import com.google.gson.JsonObject
import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.CourseAttendanceStat
import com.xjtu.toolbox.attendance.TermInfo
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.XjtuTime
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

class NewAttendanceApi(private val site: SiteSession) {

    private val jsonType = "application/json".toMediaType()
    @Volatile private var cachedTerms: List<TermInfo> = emptyList()

    private fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonObject {
        val req = Request.Builder().url(KqHttp.buildUrl(site, path, query)).get().build()
        return KqHttp.execute(site, req, path, retryable = true)
    }

    private fun postJson(path: String, body: JsonObject): JsonObject {
        val req = Request.Builder()
            .url(KqHttp.buildUrl(site, path))
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return KqHttp.execute(site, req, path, retryable = false)
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
            )
        }.distinctBy { it.bh }.sortedByDescending { it.startDate }
        cachedTerms = terms
        return terms
    }

    fun getTermBh(): String {
        val terms = cachedTerms.ifEmpty { getTermList() }
        if (terms.isEmpty()) return ""
        runCatching {
            val root = getJson("/student/service/timetable/semesters")
            val rows = KqHttp.rows(root.get("data")).ifEmpty { KqHttp.rows(root) }
            val current = rows.firstOrNull { row ->
                val flag = KqHttp.first(row, "current", "isCurrent", "active", "currentFlag")
                if (flag != null) {
                    KqHttp.bool(row, "current", "isCurrent", "active", "currentFlag")
                } else {
                    KqHttp.str(row, "status").uppercase() in setOf("CURRENT", "ACTIVE")
                }
            }
            val id = current?.let { KqHttp.str(it, "semesterId", "termId", "id", "semesterCode", "termNo") }
            if (!id.isNullOrBlank()) return id
        }
        val today = LocalDate.now().toString()
        return terms.firstOrNull { it.startDate.isNotBlank() && it.endDate.isNotBlank() && it.startDate <= today && today <= it.endDate }?.bh
            ?: terms.first().bh
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
                termString = term?.name.orEmpty(),
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
        val req = Request.Builder()
            .url(KqHttp.buildUrl(site, "/student/pc/attendance-records/page"))
            .post(KqHttp.pagePayload(data).toString().toRequestBody(jsonType))
            .build()
        val root = KqHttp.execute(site, req, "/student/pc/attendance-records/page", retryable = true)
        val rows = KqHttp.rows(root.get("data")).ifEmpty { KqHttp.rows(root) }
        return rows.filter { row ->
            KqHttp.str(row, "attendanceStatus", "status").uppercase() != "NOT_REQUIRED"
        }
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
