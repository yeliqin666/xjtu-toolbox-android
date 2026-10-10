package com.xjtu.toolbox.judge

import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 两个 [JudgeSource] 实现（本科 / 研究生）。
 *
 * 本科吃 okhttp + `SiteSession`（教务评教），研究生吃 gste + gmis 两个站点会话。两者都已搬进
 * `:data`（站点内核与解析都在这里），所以这两个类也跟过来 —— `:app` 与桌面端要的是**同一个适配器**，
 * 而不是各写一份。
 *
 * ## 为什么是 public（原来是 `internal`）
 *
 * `:desktop` 是另一个模块：`internal` 的类它看不见，而这两条正是它要注册到 `AppRoute.Judge` 上的
 * 数据源（本科那条由桌面端的 `UndergraduateJudgeSource(site, 学号)` 直接构造，研究生那条给
 * `SessionManager` 让它自己 `ensureSite`）。**将来能不能收回去**：收回去的前提是「没有任何
 * 跨模块使用者」—— 现在桌面端就是那个使用者，所以只要桌面端还直接构造它们，就收不回去；
 * 若日后桌面端改走一个宿主侧的工厂（由 `:app`/桌面各自实现），就可以再收回 `internal`。
 *
 * 屏与 ViewModel 仍然在 `:core`（`judge/JudgeListScreen.kt` + `JudgeViewModel.kt`）；这两个类
 * 本身**一行逻辑未改** —— 本科那个只是把 `JudgeApi` 包成端口，研究生那个多了学位课清单的缓存。
 */

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

/**
 * 两个站都在用户进入本页时按需登录（允许弹短信验证），gmis 到一键评教时才登。
 *
 * 桌面端那条路**不需要** `DesktopSiteGate`：这个类自己 [ensureSite] —— 与 `:app` 的导航层
 * 「先 `ensureSite` 再跳」是同一条语义，只是把宿主那一发缓到真正取数的时候。
 */
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
