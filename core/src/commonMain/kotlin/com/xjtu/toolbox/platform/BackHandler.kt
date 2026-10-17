package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/** 跨端「返回」处理。 */
@Suppress("OPT_IN_USAGE")
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    androidx.compose.ui.backhandler.BackHandler(enabled = enabled, onBack = onBack)
}
