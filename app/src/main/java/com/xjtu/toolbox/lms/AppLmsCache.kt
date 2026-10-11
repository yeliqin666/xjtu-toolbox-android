package com.xjtu.toolbox.lms

import android.content.Context
import com.xjtu.toolbox.data.DataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 思源学堂课程/活动列表**落盘缓存在 Android 侧的填法** —— `:core` 的 [LmsCacheStore] 在
 * `:app` 的实现。
 *
 * 方法体是搬屏时从 `LmsPageCache`（原来在 `:app/lms/LmsScreen.kt`）**原样搬出来的**：
 * 三个键（`lms_courses` / `lms_activities_<courseId>`）、TTL（`DataCache.TERM_TTL_MS`）、
 * 按账号分命名空间（`DataCache(context, accountId)`）、写失败只记日志不打断取数 —— 全都逐字保留，
 * 所以老缓存读得出来、写盘时机不变。IO 调度由这里自己包（[LmsCacheStore] 的 KDoc 写明：
 * 实现方是阻塞式 SharedPreferences，读写在 `Dispatchers.IO` 上，与搬迁前的调用形状一致）。
 */
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