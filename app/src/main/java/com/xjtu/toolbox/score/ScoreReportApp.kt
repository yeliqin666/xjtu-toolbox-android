package com.xjtu.toolbox.score

import android.content.Context
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.data.DataCache

/**
 * `:app` 侧的成绩屏装配：
 *  1. **取数** → [ScoreReportApi]（帆软报表 HTML 解析，okhttp，行为与搬迁前一致）；
 *     学号闭在适配器里，所以共享屏不需要认识它；
 *  2. **SWR 缓存** → [DataCache]（按账号隔离的 JSON 文件缓存）。
 *
 * 缓存 key 保持迁移前的 `score_report_$studentId`（换账号 / 换学号都得换键，不然会串号），
 * TTL 取 `DataCache.DEFAULT_TTL_MS` —— 与搬迁前同一套参数。
 */
fun appScoreReportSource(site: SiteSession, studentId: String): ScoreReportSource =
    ScoreReportApiSource(ScoreReportApi(site), studentId)

private class ScoreReportApiSource(
    private val api: ScoreReportApi,
    private val studentId: String,
) : ScoreReportSource {
    override suspend fun grades(): List<ReportedGrade> = api.getReportedGrade(studentId)
}

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
