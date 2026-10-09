package com.xjtu.toolbox.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.judge.CampusJudgeApi
import com.xjtu.toolbox.judge.CampusQuestionnaire
import com.xjtu.toolbox.judge.JudgeListScreen
import com.xjtu.toolbox.judge.JudgeViewModel

/**
 * 学生评教（Web 端入口）。
 *
 * 屏与 ViewModel 是 `:core` 的那一份（`judge/JudgeListScreen.kt`）；这里只把 campus-api 的
 * `/api/jwxt/evaluations` 包成 [com.xjtu.toolbox.judge.JudgeSource]。
 *
 * **只读**：campus-api 的评教模块永不实现提交/撤销（它自己的 status 端点就这么写），
 * 所以 `CampusJudgeApi.canSubmit = false` ⇒ 屏上不出现「一键全部好评」与撤回按钮。
 * 用户能看见「哪些课还没评」——这正是这一屏在 Web 上仍然有用的部分。
 */
@Composable
fun WebJudgeScreen(onBack: () -> Unit) {
    val client = remember { toolboxWebClient() }
    val source = remember(client) { CampusJudgeApi(client) }
    val vm: JudgeViewModel<CampusQuestionnaire> = viewModel { JudgeViewModel(source) }
    JudgeListScreen("学生评教（只读）", vm, onBack)
}
