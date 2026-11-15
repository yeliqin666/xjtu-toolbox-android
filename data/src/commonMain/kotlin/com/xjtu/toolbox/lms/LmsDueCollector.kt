package com.xjtu.toolbox.lms

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.platform.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 思源作业截止的采集：逐门课拉活动 → 挑出带截止的作业 → 补提交态 → 写进 [LmsDueStore]。
 *
 * Android 的首页刷新（最新学期）和截止提醒 Worker（全部课）直接调 [activities] + [collect]；
 * 桌面和 serve 没有后台排程，读缓存前调 [refreshIfStale]，按 [REFRESH_TTL] 节流。
 */
object LmsDueCollector {
    private const val TAG = "LmsDue"

    /** 桌面、serve 两次采集的最短间隔：读缓存的入口每次进屏都会走到，不能每次都逐门课重拉。 */
    val REFRESH_TTL: Duration = 30.minutes

    private val refreshLock = Mutex()
    private val lastRefresh = HashMap<String, Long>()

    /** 逐门课拉活动，串行。单门课失败（课程归档偶发 403 等）只记日志，不毁整轮。 */
    suspend fun activities(api: LmsApi, courses: List<LmsCourseSummary>): List<Pair<LmsCourseSummary, List<LmsActivity>>> =
        courses.map { c ->
            c to runCatching { api.getCourseActivities(c.id) }
                .onFailure { Log.w(TAG, "课程 ${c.name} 活动拉取失败：${it.message}") }
                .getOrDefault(emptyList())
        }

    /**
     * 整理出带截止的作业、补上提交态，[isCurrent] 仍为真才落盘（拉取途中切了账号就作废）。
     *
     * 列表接口不给 `user_submit_count`，只能逐条查详情：只查没截止、之前也没查到已交的；
     * 查失败当没交——宁可多挂一条，也别把没交的当成交了。
     */
    suspend fun collect(
        api: LmsApi,
        perCourse: List<Pair<LmsCourseSummary, List<LmsActivity>>>,
        account: String,
        isCurrent: () -> Boolean,
    ): List<LmsDue> {
        val now = Instant.now()
        val stored = LmsDueStore.load(account)
        val known = stored.filter { it.submitted && !it.exam }.mapTo(HashSet()) { it.courseId to it.activityId }
        val items = dueItems(perCourse, System.currentTimeMillis()).map { d ->
            when {
                d.courseId to d.activityId in known -> d.copy(submitted = true)
                isPast(d, now) -> d
                else -> d.copy(submitted = runCatching { api.getUserSubmitCount(d.activityId) > 0 }
                    .onFailure { Log.w(TAG, "作业 ${d.title} 提交态查询失败：${it.message}") }
                    .getOrDefault(false))
            }
        }
        Log.d(TAG, "作业 ${items.size} 条，未交未截止 ${items.count { !it.submitted && !isPast(it, now) }} 条")
        val exams = examDues(api, perCourse, stored)
        if (isCurrent()) LmsDueStore.save(items + exams, account)
        return items
    }

    /**
     * 距上次采集超过 [ttl] 才跑一轮最新学期，返回这次是否真的跑了。
     *
     * 失败也记时间：学堂登不上时，读缓存的入口不该每次都卡在重登上。
     * 同一时刻只跑一轮，后到的调用等前一轮写完再读缓存。
     */
    suspend fun refreshIfStale(
        account: String,
        site: suspend () -> SiteSession,
        ttl: Duration = REFRESH_TTL,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        if (account.isEmpty()) return false
        return refreshLock.withLock {
            val now = System.currentTimeMillis()
            val last = lastRefresh[account]
            if (last != null && now - last < ttl.inWholeMilliseconds) return@withLock false
            lastRefresh[account] = now
            try {
                val api = LmsApi(site())
                val perCourse = activities(api, newestTermCourses(api.getMyCourses()))
                collect(api, perCourse, account, isCurrent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "作业截止采集失败：${e.message}")
            }
            true
        }
    }

    /** 测试用：清掉节流记录。 */
    internal fun resetRefreshClock() {
        lastRefresh.clear()
    }

    /**
     * 学堂待办里的「测试」。活动列表里没有它的截止和提交态，网页的待办（`/api/todos`）才有，且只列没交的：
     * 之前记过、这次不在了的就是交了，标成已交让它从待办里挪走。待办拉不到时返回空，旧记录原样留着。
     * 没设截止时间的测试进不了待办（待办按截止排、到点自动清），先不收。
     */
    private suspend fun examDues(
        api: LmsApi,
        perCourse: List<Pair<LmsCourseSummary, List<LmsActivity>>>,
        stored: List<LmsDue>,
    ): List<LmsDue> {
        val todos = runCatching { api.getTodos() }
            .onFailure { Log.w(TAG, "学堂待办拉取失败：${it.message}") }
            .getOrNull() ?: return emptyList()
        val fetchedAt = System.currentTimeMillis()
        val courseNames = perCourse.associate { (c, _) -> c.id to c.name }
        val pending = todos.filter { it.type == "exam" }.mapNotNull { t ->
            val deadline = t.endTime?.let { runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull() }
                ?: return@mapNotNull null
            LmsDue(
                courseId = t.courseId, courseName = t.courseName.ifBlank { courseNames[t.courseId].orEmpty() },
                activityId = t.id, title = t.title, deadline = deadline.toString(), fetchedAt = fetchedAt, exam = true,
            )
        }
        val pendingKeys = pending.mapTo(HashSet()) { it.courseId to it.activityId }
        val done = stored
            .filter { it.exam && !it.submitted && it.courseId to it.activityId !in pendingKeys }
            .map { it.copy(submitted = true, fetchedAt = fetchedAt) }
        Log.d(TAG, "测试 未交 ${pending.size} 条，新交 ${done.size} 条")
        return pending + done
    }

    /** 带截止的作业（资料、没截止的不收），提交态先当没交。 */
    fun dueItems(perCourse: List<Pair<LmsCourseSummary, List<LmsActivity>>>, fetchedAt: Long): List<LmsDue> =
        perCourse.flatMap { (c, acts) ->
            acts.filter { it.type == LmsActivityType.HOMEWORK }.mapNotNull { a ->
                val deadline = a.deadlineInstant() ?: return@mapNotNull null
                LmsDue(
                    courseId = c.id, courseName = c.name, activityId = a.id, title = a.title,
                    deadline = deadline.toString(), fetchedAt = fetchedAt,
                    startTime = a.startTime,
                )
            }
        }

    /** 截止已过（解析不了不算过）。已截止的不查提交态：待办本来就不显示它。 */
    internal fun isPast(d: LmsDue, now: Instant) =
        runCatching { Instant.parse(d.deadline) }.getOrNull()?.isAfter(now) == false
}

/**
 * 选出"最新学期"的全部课程。
 *
 * 认不出学期的课（`academic_year` / `semester` 为 null）不参与，也不会被当成最新；
 * 一门都认不出时退化成不筛——宁可多查几门，也别让待办空着。
 */
fun newestTermCourses(courses: List<LmsCourseSummary>): List<LmsCourseSummary> {
    fun order(c: LmsCourseSummary) = lmsTermOrder(c.semester.code, c.academicYear.code)
    val newest = courses.mapNotNull(::order).maxOrNull() ?: return courses
    return courses.filter { order(it) == newest }
}

/**
 * 思源学期排序键：`2026-1` → `202601`，越大越新；认不出返回 null。
 *
 * 为什么不用 `academicYear.sort` + `semester.sort`：实测 `academic_year.sort` 恒为 0
 * （字段在，值不区分学年），退化后只剩 `semester.sort`，而它的顺序是**越旧越大**
 * （2025-1=5 > 2025-2=4 > 2026-1=1），于是"最新学期"恰好选中一年前那个。
 * `semester.name` / `real_name` 实测全是 null，`code` 是唯一单调可比的锚点。
 */
fun lmsTermOrder(semesterCode: String?, academicYearCode: String?): Int? {
    val year = semesterCode?.substringBefore('-')?.trim()?.toIntOrNull()
        ?: academicYearCode?.substringBefore('-')?.trim()?.toIntOrNull()
        ?: return null
    val term = semesterCode?.substringAfter('-', "")?.trim()?.toIntOrNull() ?: 0
    return year * 100 + term
}