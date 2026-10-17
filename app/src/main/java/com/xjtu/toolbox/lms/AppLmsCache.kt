package com.xjtu.toolbox.lms

import android.content.Context
import com.xjtu.toolbox.data.DataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 思源学堂课程/活动列表落盘缓存在 Android 侧的填法 —— `:core` 的 [LmsCacheStore] 在`:app` 的实现。 */
class AppLmsCache(context: Context, accountId: String?) : LmsCacheStore {

    private val cache = DataCache(context, accountId)

    override suspend fun readCourses(): List<LmsCourseSummary>? =
        withContext(Dispatchers.IO) { readSafe(COURSES_KEY) }

    override suspend fun writeCourses(courses: List<LmsCourseSummary>) =
        withContext(Dispatchers.IO) { writeSafe(COURSES_KEY, courses) }

    override suspend fun readActivities(courseId: Int): List<LmsActivity>? =
        withContext(Dispatchers.IO) { readSafe(activityKey(courseId)) }

    override suspend fun writeActivities(courseId: Int, activities: List<LmsActivity>) =
        withContext(Dispatchers.IO) { writeSafe(activityKey(courseId), activities) }

    private inline fun <reified T> readSafe(key: String): T? =
        runCatching { cache.read<T>(key, DataCache.TERM_TTL_MS) }.getOrNull()

    private inline fun <reified T> writeSafe(key: String, value: T) {
        runCatching { cache.write(key, value) }
            .onFailure { android.util.Log.w("AppLmsCache", "write $key failed", it) }
    }

    private fun activityKey(courseId: Int) = ACTIVITIES_KEY_PREFIX + courseId

    private companion object {
        const val COURSES_KEY = "lms_courses"
        const val ACTIVITIES_KEY_PREFIX = "lms_activities_"
    }
}