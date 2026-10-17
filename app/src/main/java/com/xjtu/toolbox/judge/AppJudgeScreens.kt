package com.xjtu.toolbox.judge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession

/** 两个评教入口（本科 / 研究生） */

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
