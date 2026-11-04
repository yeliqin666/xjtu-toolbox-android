package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

val LocalTabBottomPadding = compositionLocalOf { 0.dp }

@Composable
fun tabContentPadding(padding: PaddingValues = PaddingValues()): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = padding.calculateStartPadding(direction),
        top = padding.calculateTopPadding(),
        end = padding.calculateEndPadding(direction),
        bottom = padding.calculateBottomPadding() + LocalTabBottomPadding.current,
    )
}

@Composable
fun Modifier.tabContentBottomPadding(): Modifier = padding(bottom = LocalTabBottomPadding.current)
