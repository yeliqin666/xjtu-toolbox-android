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

    override suspend fun readBulletins(courseId: Int): List<LmsBulletin>? =
        withContext(Dispatchers.IO) { readSafe(BULLETINS_KEY_PREFIX + courseId) }

    override suspend fun writeBulletins(courseId: Int, bulletins: List<LmsBulletin>) =
        withContext(Dispatchers.IO) { writeSafe(BULLETINS_KEY_PREFIX + courseId, bulletins) }

    /**
     * 「作业交没交」的结论。
     *
     * 用的 TTL 比课程/活动列表**短得多**：这条只用来首屏先画出「已提交」，实时结论永远由
     * `syncSubmitted` 现查覆盖，所以过期早一点没坏处，过期久了反而会在老师撤掉提交后
     * 让人以为还交着。一周足够覆盖「刚看完作业、过几天再进来还认得」这个场景。
     */
    override suspend fun readSubmitted(): Map<Int, Boolean>? =
        withContext(Dispatchers.IO) { readSafe<Map<String, Boolean>>(SUBMITTED_KEY, SUBMITTED_TTL_MS) }
            ?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }
            ?.toMap()

    /**
     * 键还是写成字符串再存：`AppJson` 的 map 键是字符串，int 键的值类在 wasm / ios
     * 上序列化器也不同；这里显式转一次，比让 `Map<Int, Boolean>` 直存来得稳。
     */
    override suspend fun writeSubmitted(byId: Map<Int, Boolean>) = withContext(Dispatchers.IO) {
        writeSafe(SUBMITTED_KEY, byId.mapKeys { it.key.toString() })
    }

    private inline fun <reified T> readSafe(key: String, ttlMs: Long = DataCache.TERM_TTL_MS): T? =
        runCatching { cache.read<T>(key, ttlMs) }.getOrNull()

    private inline fun <reified T> writeSafe(key: String, value: T) {
        runCatching { cache.write(key, value) }
            .onFailure { android.util.Log.w("AppLmsCache", "write $key failed", it) }
    }

    private fun activityKey(courseId: Int) = ACTIVITIES_KEY_PREFIX + courseId

    private companion object {
        const val COURSES_KEY = "lms_courses"
        const val ACTIVITIES_KEY_PREFIX = "lms_activities_"
        const val BULLETINS_KEY_PREFIX = "lms_bulletins_"

        /** 作业提交态：见 [readSubmitted] 的说明。 */
        const val SUBMITTED_KEY = "lms_submitted"
        const val SUBMITTED_TTL_MS = 7L * 24 * 60 * 60 * 1000L
    }
}
