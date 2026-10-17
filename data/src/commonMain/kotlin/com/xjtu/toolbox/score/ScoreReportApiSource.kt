package com.xjtu.toolbox.score

import com.xjtu.toolbox.auth.SiteSession

/** 成绩屏的取数装配（`:core` 的 [ScoreReportSource] 在直连这一侧的实现） */
fun scoreReportSource(site: SiteSession, studentId: String): ScoreReportSource =
    ScoreReportApiSource(ScoreReportApi(site), studentId)

private class ScoreReportApiSource(
    private val api: ScoreReportApi,
    private val studentId: String,
) : ScoreReportSource {
    override suspend fun grades(): List<ReportedGrade> = api.getReportedGrade(studentId)
}
