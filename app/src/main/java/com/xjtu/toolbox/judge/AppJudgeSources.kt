package com.xjtu.toolbox.judge

import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 两个 [JudgeSource] 实现（本科 / 研究生）—— **:app 专属**，原样从 `JudgeViewModel.kt` 搬出来。
 *
 * 它们要 okhttp + `SiteSession`（本科）、gste + gmis 两个站点会话（研究生），
 * 所以留在 :app；屏与 ViewModel 在 :core。**代码一行未改**。
 */

internal class UndergraduateJudgeSource(site: SiteSession, private val username: String) : JudgeSource<Questionnaire> {
    private val api = JudgeApi(site)
    override val confirmText get() = "，确定继续？"
    override suspend fun load() = api.unfinishedQuestionnaires() to api.finishedQuestionnaires()
    override fun card(q: Questionnaire) = JudgeCard(
        key = "${q.WJDM}_${q.JXBID}_${q.BPR}",
        course = q.KCM,
        teacher = q.BPJS,
        tag = when (q.PGLXDM) { "01" -> "期末评教"; "05" -> "过程评教"; else -> "评教" },
    )
    override suspend fun judge(q: Questionnaire) { api.submitQuestionnaire(q, api.autoFillQuestionnaire(q, username)) }
    override val undo: suspend (Questionnaire) -> String? = { q ->
        val (ok, msg) = api.editQuestionnaire(q, username)
        if (ok) null else msg
    }
}

/** 两个站都在用户进入本页时按需登录（允许弹短信验证），gmis 到一键评教时才登。 */
internal class GraduateJudgeSource(private val sessions: SessionManager) : JudgeSource<GraduateQuestionnaire> {
    private val mutex = Mutex()
    private var api: GraduateJudgeApi? = null
    private var degreeCourses: Set<String> = emptySet()

    private suspend fun api(): GraduateJudgeApi = mutex.withLock {
        api ?: GraduateJudgeApi(
            gste = sessions.ensureSite("gste", userInitiated = true),
            gmisProvider = { sessions.ensureSite("gmis", userInitiated = true) },
        ).also { api = it }
    }

    override val confirmText get() = "（系统不允许全部「优秀」，会有一项自动改为「良好」），确定继续？"
    override suspend fun load() = api().getQuestionnaires().let { all ->
        all.filter { it.assessment == "allow" } to all.filter { it.finished }
    }
    override fun card(q: GraduateQuestionnaire) = JudgeCard(
        key = q.key,
        course = q.kcmc,
        teacher = q.jsxm + if (q.skls_duty.isNotBlank()) "（${q.skls_duty}）" else "",
        tag = q.termname,
    )
    // 学位课 / 选修课整张成绩页取一次，所有问卷共用
    override suspend fun prepare() { degreeCourses = api().getDegreeCourseNames() }
    override suspend fun judge(q: GraduateQuestionnaire) = api().autoJudge(q, degreeCourses)
}
