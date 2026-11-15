package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalCouiBackdrop = staticCompositionLocalOf<CouiBackdrop?> { null }

/** 提供页面正文采样源；宿主仍须将 couiBackdrop 挂到唯一正文节点，并排除其消费者。 */
@Composable
fun ProvideCouiBackdrop(backdrop: CouiBackdrop, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCouiBackdrop provides backdrop, content = content)
}
