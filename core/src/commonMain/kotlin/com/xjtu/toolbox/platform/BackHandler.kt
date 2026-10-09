package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/**
 * 跨端「返回」处理 —— 交接文档 §4 的「返回键」那一族。
 *
 * 为什么要有这层薄包装（而不是直接调 CMP 的）：
 *   1. CMP 的 `androidx.compose.ui.backhandler.BackHandler` 目前标着**实验 API**，每个调用点都要
 *      `@OptIn`，而 :app 里有 110 处调用。把 opt-in 收在这一处，调用点拿到的就是稳定 API。
 *   2. Android 专属的 `androidx.activity.compose.BackHandler` 在共享 UI 里用不了；CMP 的
 *      android 变体正是**委派**给它的，所以行为不变 —— 只是 import 换一行。
 *
 * 将来 CMP 把它标成稳定 API 时，把这里的 opt-in 删掉即可，调用点一行都不用动。
 */
@Suppress("OPT_IN_USAGE")
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    androidx.compose.ui.backhandler.BackHandler(enabled = enabled, onBack = onBack)
}
