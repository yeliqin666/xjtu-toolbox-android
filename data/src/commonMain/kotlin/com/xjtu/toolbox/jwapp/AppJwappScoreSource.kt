package com.xjtu.toolbox.jwapp

import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.judge.JudgeApi
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.score.ScoreReportApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `:core` 的三个端口（[JwappScoreSource] / [JwxtScoreSupplement]）在 `:data` 里的实现 ——
 * Android 与桌面侧共用，包住原来的 [JwappApi]（okhttp 抓 `jwapp.xjtu.edu.cn`）与教务那半三件
 * （[CjcxApi] 精确成绩 / [JudgeApi] 未评教名单 / [ScoreReportApi] 成绩报表）。
 *
 * 它从 `:app` 搬进 `:data`（桌面端第 19 条真数据路由：桌面要自己登 jwapp 与教务、自己取成绩）。
 * 取数的类名与包路径一字未改，跟着搬的只有四件事：
 *
 * 1. **调度器**：搬迁前是 ViewModel 每次调用自己 `withContext(Dispatchers.IO)`，现在 VM 不再替
 *    实现挑调度器（见 [JwappScoreSource] 的 KDoc），由本类自己包 —— **同一层、同一个调度器**；
 * 2. **站点解析**：搬迁前 VM 构造时就 `getSiteOrNull("jwapp")` / `getSiteOrNull("jwxt")`；
 *    这里调用时现取（会话管家是同一个对象，站点实例不随账号切换重建）；
 * 3. **强制重登**：搬迁前 VM 里那一枪要读宿主凭据，现在由本类从会话管家拿（见 [reAuthenticate]）；
 * 4. **会话失效**：`:data` 抛 `SessionExpiredException`（`:app` 的 `AuthExpiredException` 是它的
 *    子类，两端的同一个 `catch (e: SessionExpiredFailure)` 都认）。
 *
 * 一个类实现两个端口是刻意的：这条路由的上游本来就是「jwapp 一站 + 教务一站」，而两个端口都只需
 * 要同一份会话管家 —— 拆成两个类只会让两端各写一遍站点解析。
 *
 * ⚠️ 与搬迁前的一处**不可达**差别：App 那边原来用 `getSiteOrNull("jwapp")` 的 null 表示
 * 「这一端没有 jwapp 会话」（`canRefresh = false`，只读缓存）。现在由调用方按
 * 「有没有会话管家」传不传这个源；站点本身始终在 `AppLoginState` / `DesktopAuth` 里注册过，
 * 所以「管家在、站点不在」这一格只会在装配出错时出现，那时抛 `SessionExpiredException`
 * 让屏说实话，而不是静默退化成「暂无成绩缓存」。
 */
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

    /**
     * 错误页「重试」时先强制重登一次 —— 搬迁前写在 VM 里的那一枪
     *（`login.sessionManager?.credentials?.let { (u, p) -> site.ensureLogin(u, p, force = true, userInitiated = true) }`），
     * 参数与语义逐字相同：凭据取不到就什么都不做（`?.let` 的语义），失败照旧抛给调用方
     *（VM 那边 `runCatching`，与搬迁前同一个）。
     */
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
