package com.xjtu.toolbox.judge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession

/**
 * 两个评教入口（本科 / 研究生）—— **只剩装配**：屏与 ViewModel 在 :core
 * （`judge/JudgeListScreen.kt` + `JudgeViewModel.kt`），这里只把宿主能力包成 [JudgeSource]。
 *
 * 提交/撤回是 Android 才有的能力（campus-api 的 evaluations 模块永不实现提交），
 * `JudgeSource.canSubmit` 默认 true ⇒ 这两处 UI 照旧出现，行为逐字不变。
 */

/** 本科评教。 */
@Composable
fun JudgeScreen(site: SiteSession, username: String, onBack: () -> Unit) {
    val source = remember(site, username) { UndergraduateJudgeSource(site, username) }
    val vm: JudgeViewModel<Questionnaire> = viewModel { JudgeViewModel(source) }
    JudgeListScreen("本科评教", vm, onBack)
}

/** 研究生评教：数据来自 gste，填问卷要用的课程信息来自 gmis。 */
@Composable
fun GraduateJudgeScreen(sessionManager: SessionManager, onBack: () -> Unit) {
    val source = remember(sessionManager) { GraduateJudgeSource(sessionManager) }
    val vm: JudgeViewModel<GraduateQuestionnaire> = viewModel { JudgeViewModel(source) }
    JudgeListScreen("研究生评教", vm, onBack)
}
