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

/**
 * 学生评教（Web 端入口）。
 *
 * 屏与 ViewModel 是 `:core` 的那一份（`judge/JudgeListScreen.kt`）；这里只把 campus-api 的
 * `/api/jwxt/evaluations` 包成 [com.xjtu.toolbox.judge.JudgeSource]。
 *
 * **只读**：两个后端的评教模块都不实现提交/撤销（campus-api 的 status 端点与契约 §5.3 都这么写），
 * 所以 `CampusJudgeApi.canSubmit = false` ⇒ 屏上不出现「一键全部好评」与撤回按钮。
 * 用户能看见「哪些课还没评」——这正是这一屏在 Web 上仍然有用的部分。
 *
 * @param mode serve 模式下走契约 §5.3 的形状（`items[]` + `course`/`type` 两个键名），
 *   见 `CampusJudgeApi` 的 `fetchRows`。
 * @param client 与整页同一个客户端：serve 模式下它带着 `Authorization` 头。
 */
@Composable
fun WebJudgeScreen(client: HttpClient, mode: ApiMode, onBack: () -> Unit) {
    val source = remember(client, mode) { CampusJudgeApi(client, API_BASE, mode) }
    val vm: JudgeViewModel<CampusQuestionnaire> = viewModel { JudgeViewModel(source) }
    JudgeListScreen("学生评教（只读）", vm, onBack)
}
