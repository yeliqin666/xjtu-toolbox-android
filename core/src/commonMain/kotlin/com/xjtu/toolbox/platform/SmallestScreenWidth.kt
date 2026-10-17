package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/** 屏幕的「最小宽度」dp —— Android 的 `smallestScreenWidthDp` 语义。 */
@Composable
expect fun smallestScreenWidthDp(): Int
