package com.xjtu.toolbox.score

import com.xjtu.toolbox.auth.SiteSession

/**
 * 成绩屏的**取数装配**（`:core` 的 [ScoreReportSource] 在直连这一侧的实现）：
 * 取数走 [ScoreReportApi]（帆软报表 HTML 解析，okhttp，行为与搬迁前一致），
 * **学号闭在适配器里**，所以共享屏不需要认识它。
 *
 * 它原来在 `:app`（叫 `appScoreReportSource`）—— 搬进 `:data` 是因为桌面端也要用它，
 * 而它一行 Android 都不依赖（原来与它同一个文件的 `appScoreReportCache` 要 `Context` +
 * `DataCache`，那部分留在 `:app` 的 `ScoreReportApp.kt` 里）。
 *
 * 为什么改名：名字里带 `App` 在共享层会撒谎 —— 它现在两端共用。缓存那一档仍叫
 * `appScoreReportCache`（那个真的只属于 App）。
 */
fun scoreReportSource(site: SiteSession, studentId: String): ScoreReportSource =
    ScoreReportApiSource(ScoreReportApi(site), studentId)

private class ScoreReportApiSource(
    private val api: ScoreReportApi,
    private val studentId: String,
) : ScoreReportSource {
    override suspend fun grades(): List<ReportedGrade> = api.getReportedGrade(studentId)
}
