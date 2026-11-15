package com.xjtu.toolbox.judge

import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [JudgeSource] 实现：安卓端走 QMS；教务 / gste 两个旧源只剩桌面端在用。 */

class QmsJudgeSource(site: SiteSession) : JudgeSource<QmsQuestionnaire> {
    private val api = QmsJudgeApi(site)
    override val confirmText get() = "（主观题统一填写「${QmsJudgeApi.DEFAULT_COMMENT}」），确定继续？"
    override suspend fun load() = api.questionnaires()
    override fun card(q: QmsQuestionnaire) = JudgeCard(
        key = q.responseId,
        course = q.courseName,
        teacher = q.lecturers.joinToString("、").ifEmpty { "未知教师" },
        tag = listOf("期中", "期末", "过程").firstOrNull { it in q.planTitle }?.let { "${it}评教" } ?: "评教",
    )
    override suspend fun judge(q: QmsQuestionnaire) = api.submitBest(q)
    override val undo: suspend (QmsQuestionnaire) -> String? = { q -> api.recall(q); null }
}

class UndergraduateJudgeSource(site: SiteSession, private val username: String) : JudgeSource<Questionnaire> {
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
class GraduateJudgeSource(private val sessions: SessionManager) : JudgeSource<GraduateQuestionnaire> {
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
