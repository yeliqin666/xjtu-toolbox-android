package com.xjtu.toolbox.score

import android.content.Context
import com.xjtu.toolbox.data.DataCache

/**
 * `:app` 侧的成绩屏**缓存**装配 —— `:core` 的 [ScoreReportCache] 在 Android 上的实现。
 *
 * 取数那一半（`scoreReportSource(site, studentId)`）已经搬进 `:data`（与 `ScoreReportApi`
 * 同一个包，桌面端也用同一份），这里只剩缓存：它是唯一依赖 `Context` + `DataCache` 的一档。
 *
 * 缓存 key 保持迁移前的 `score_report_$studentId`（换账号 / 换学号都得换键，不然会串号），
 * TTL 取 `DataCache.DEFAULT_TTL_MS` —— 与搬迁前同一套参数。
 */
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
