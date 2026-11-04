package com.xjtu.toolbox.degree

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.schedule.JwxtScheduleApi
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.ScheduleSourceRouter
import kotlinx.coroutines.CancellationException

private const val TERMS_WITH_COURSES = 2
private const val MAX_TERMS_BACK = 4

/**
 * :app 侧的学业进度装配：课表缓存在这一层。
 *
 * 取最近两个有课的学期：进小学期时上学期成绩常常还没出，只看当前学期会漏。
 * 小学期可能没课，所以往回找、跳过空学期，最多看 [MAX_TERMS_BACK] 个。
 * 只取教务课表，不含用户自建的日程，那些不是课。
 */
fun appDegreeSource(context: Context, sessionManager: SessionManager): DegreeSource {
    val app = context.applicationContext
    return AppDegreeSource(sessionManager) {
        val cache = DataCache(app, AccountContext.activeAccountId)
        suspend fun coursesOf(term: String?): List<com.xjtu.toolbox.schedule.CourseItem> = try {
            if (!ScheduleCache.isReady(cache, term)) {
                val site = sessionManager.ensureSite("jwxt", silent = true)
                ScheduleSourceRouter.ensureCached(cache, JwxtScheduleApi(site), sessionManager, term)
            }
            (term ?: ScheduleCache.readCurrentTerm(cache))?.let { ScheduleCache.readCourses(cache, it) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 某个学期取不到不影响其他学期
            emptyList()
        }
        val first = coursesOf(null)
        var term = ScheduleCache.readCurrentTerm(cache)
        val found = ArrayList<DegreeCurrentCourse>()
        var termsWithCourses = 0
        var looked = 0
        while (term != null && looked < MAX_TERMS_BACK && termsWithCourses < TERMS_WITH_COURSES) {
            val code = term
            val courses = if (looked == 0) first else coursesOf(code)
            if (courses.isNotEmpty()) {
                termsWithCourses++
                courses.mapTo(found) { DegreeCurrentCourse(it.courseCode, it.courseName, code) }
            }
            looked++
            term = DegreeBuilder.previousTerm(code)
        }
        // 同一门课两学期都有时留新的那条
        found.distinctBy { it.code.ifBlank { it.name } }
    }
}
