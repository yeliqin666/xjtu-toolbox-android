package com.xjtu.toolbox.judge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.auth.SiteSession

/** 学生评教（QMS），本科研究生同一个入口。 */
@Composable
fun JudgeScreen(site: SiteSession, onBack: () -> Unit) {
    val source = remember(site) { QmsJudgeSource(site) }
    val vm: JudgeViewModel<QmsQuestionnaire> = viewModel { JudgeViewModel(source) }
    JudgeListScreen("学生评教", vm, onBack)
}
