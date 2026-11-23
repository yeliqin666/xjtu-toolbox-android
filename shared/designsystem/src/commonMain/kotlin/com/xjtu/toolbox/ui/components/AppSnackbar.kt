package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.basic.Snackbar
import top.yukonga.miuix.kmp.basic.SnackbarDefaults
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全 App 统一的 Snackbar 宿主。miuix 默认底色取 `onSecondaryVariant`，深色模式下是一块发灰的亮条，
 * 这里深色时改用最高一层容器色配正文色。
 */
@Composable
fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val colors = if (dark) {
        SnackbarDefaults.snackbarColors(
            containerColor = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurface,
        )
    } else {
        SnackbarDefaults.snackbarColors()
    }
    SnackbarHost(state, modifier) { data -> Snackbar(data, colors = colors) }
}

/** 先收掉还在显示的提示再弹新的：miuix 允许多条并存，连续操作时会越摞越高。 */
suspend fun SnackbarHostState.showReplacing(
    message: String,
    actionLabel: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Short,
): SnackbarResult {
    repeat(4) { newestSnackbarData()?.dismiss() }
    return showSnackbar(message, actionLabel = actionLabel, duration = duration)
}
