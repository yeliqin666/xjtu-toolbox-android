package com.xjtu.toolbox.score

import android.content.Context
import com.xjtu.toolbox.data.DataCache

/** `:app` 侧的成绩屏缓存装配 —— `:core` 的 [ScoreReportCache] 在 Android 上的实现。 */
fun appScoreReportCache(context: Context, accountId: String?, studentId: String): ScoreReportCache =
    DataCacheScoreReportCache(context.applicationContext, accountId, studentId)

private class DataCacheScoreReportCache(
    context: Context,
    accountId: String?,
    studentId: String,
) : ScoreReportCache {
    private val cache = DataCache(context, accountId)
    private val key = "score_report_$studentId"

    override fun read(): List<ReportedGrade>? = cache.read(key, DataCache.DEFAULT_TTL_MS)

    override fun write(grades: List<ReportedGrade>) = cache.write(key, grades)
}
