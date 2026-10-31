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
    // serve 形态下评教**真能提交**（契约 §5.12 的 `POST /api/jwxt/evaluations/submit`，与 `:app` 的
    // 「本科评教」是同一件事：自动填答后提交，上游拒就回 409）⇒ 标题不再写「只读」；
    // campus-api 那一形态仍然只读（那条老路没有提交端点，`canSubmit` 为 false，屏上那个块也不画）。
    JudgeListScreen(if (mode == ApiMode.SERVE) "本科评教" else "学生评教（只读）", vm, onBack)
}
