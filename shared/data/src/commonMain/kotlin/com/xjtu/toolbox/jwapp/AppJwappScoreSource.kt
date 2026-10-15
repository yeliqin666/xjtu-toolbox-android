package com.xjtu.toolbox.jwapp

import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.judge.JudgeApi
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.score.ScoreReportApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `:core` 的三个端口（[JwappScoreSource] / [JwxtScoreSupplement]）在 `:data` 里的实现。 */
class AppJwappScoreSource(private val sessionManager: SessionManager) :
    JwappScoreSource, JwxtScoreSupplement {

    private fun jwappSite() = sessionManager.getSiteOrNull(JWAPP_SITE_KEY)
        ?: throw SessionExpiredException("移动教务", "移动教务站点会话不存在")

    private fun jwxtSite() = sessionManager.getSiteOrNull(JWXT_SITE_KEY)
        ?: throw SessionExpiredException("教务", "教务站点会话不存在")

    // ── jwapp（移动教务）那半 ────────────────────────────────────────

    override suspend fun getGrade(termCode: String?): List<TermScore> =
        withContext(Dispatchers.IO) { JwappApi(jwappSite()).getGrade(termCode) }

    override suspend fun getScoreDetail(courseId: String): ScoreDetail =
        withContext(Dispatchers.IO) { JwappApi(jwappSite()).getDetail(courseId) }

    /** 错误页「重试」时先强制重登一次。 */
    override suspend fun reAuthenticate() {
        val creds = sessionManager.credentials ?: return
        sessionManager.getSiteOrNull(JWAPP_SITE_KEY)?.ensureLogin(
            creds.first,
            creds.second,
            force = true,
            userInitiated = true,
        )
    }

    // ── 教务（jwxt）那半 ─────────────────────────────────────────────

    override suspend fun preciseScores(): List<CjcxScore> =
        withContext(Dispatchers.IO) { CjcxApi(jwxtSite()).getAllScores() }

    override suspend fun unevaluatedCourseNames(): Set<String> = withContext(Dispatchers.IO) {
        JudgeApi(jwxtSite()).unfinishedQuestionnaires().map { it.KCM }.toSet()
    }

    override suspend fun reportedGrades(studentId: String): List<ReportedGrade> =
        withContext(Dispatchers.IO) { ScoreReportApi(jwxtSite()).getReportedGrade(studentId) }


    private companion object {
        const val JWAPP_SITE_KEY = "jwapp"
        const val JWXT_SITE_KEY = "jwxt"
    }
}
