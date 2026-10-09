package com.xjtu.toolbox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.judge.CampusJudgeApi
import com.xjtu.toolbox.judge.CampusQuestionnaire
import com.xjtu.toolbox.judge.JudgeListScreen
import com.xjtu.toolbox.judge.JudgeViewModel
import io.ktor.client.HttpClient

/**
 * 学生评教（桌面端入口）。
 *
 * 与 `:web` 的 `WebJudgeScreen` 同一个做法、同一批理由：屏与 ViewModel 是 `:core` 的那一份
 *（`judge/JudgeListScreen.kt`），这里只把 campus-api 的 `/api/jwxt/evaluations` 包成
 * `JudgeSource`。**只读**：`CampusJudgeApi.canSubmit = false` ⇒ 屏上不出现「一键全部好评」与撤回按钮，
 * 用户能看见的是「哪些课还没评」。
 *
 * 与 Web 的唯一差别是取数基址（那边同源，这边直连 `127.0.0.1:3099`）。
 */
@Composable
fun DesktopJudgeScreen(onBack: () -> Unit, client: HttpClient) {
    val source = remember(client) { CampusJudgeApi(client, SCAFFOLD_API_BASE) }
    val vm: JudgeViewModel<CampusQuestionnaire> = viewModel { JudgeViewModel(source) }
    JudgeListScreen("学生评教（只读）", vm, onBack)
}
