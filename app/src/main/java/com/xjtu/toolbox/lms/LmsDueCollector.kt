package com.xjtu.toolbox.lms

import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.ZonedDateTime

/**
 * 思源作业截止的采集：逐门课拉活动 → 挑出带截止的作业 → 补提交态 → 写进 [LmsDueStore]。
 *
 * 首页刷新（最新学期全部课）和截止提醒 Worker（全部课）都走这里，待办覆盖哪些课只取决于传进来的课程。
 */
object LmsDueCollector {
    private const val TAG = "LmsDue"

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
        ctx: Context,
        api: LmsApi,
        perCourse: List<Pair<LmsCourseSummary, List<LmsActivity>>>,
        account: String,
        isCurrent: () -> Boolean,
    ): List<LmsDue> {
        val now = Instant.now()
        val stored = LmsDueStore.load(ctx, account)
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
        if (isCurrent()) LmsDueStore.save(ctx, items + exams, account)
        return items
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

    internal fun dueItems(perCourse: List<Pair<LmsCourseSummary, List<LmsActivity>>>, fetchedAt: Long): List<LmsDue> =
        perCourse.flatMap { (c, acts) ->
            acts.filter { it.type == LmsActivityType.HOMEWORK }.mapNotNull { a ->
                val deadline = a.deadlineInstant() ?: return@mapNotNull null
                LmsDue(
                    courseId = c.id, courseName = c.name, activityId = a.id, title = a.title,
                    deadline = deadline.toString(), fetchedAt = fetchedAt,
                )
            }
        }

    /** 截止已过（解析不了不算过）。已截止的不查提交态：待办本来就不显示它。 */
    internal fun isPast(d: LmsDue, now: Instant) =
        runCatching { Instant.parse(d.deadline) }.getOrNull()?.isAfter(now) == false
}
