package com.xjtu.toolbox.web

import com.xjtu.toolbox.core.net.ApiMode
import io.ktor.client.HttpClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.judge.CampusJudgeApi
import com.xjtu.toolbox.judge.CampusQuestionnaire
import com.xjtu.toolbox.judge.JudgeListScreen
import com.xjtu.toolbox.judge.JudgeViewModel

/** 学生评教（Web 端入口）。 */
@Composable
fun WebJudgeScreen(client: HttpClient, mode: ApiMode, onBack: () -> Unit) {
    val source = remember(client, mode) { CampusJudgeApi(client, API_BASE, mode) }
    val vm: JudgeViewModel<CampusQuestionnaire> = viewModel { JudgeViewModel(source) }
    JudgeListScreen("学生评教（只读）", vm, onBack)
}
