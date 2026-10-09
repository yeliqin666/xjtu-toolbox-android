package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/**
 * 屏幕的「最小宽度」dp —— Android 的 `smallestScreenWidthDp` 语义：**设备物理屏幕的短边**，
 * 用来区分手机与平板（`:app` 的 `WindowSize.kt` 用它做 <600dp 判断）。
 *
 * 为什么必须做成切口而不是直接换掉：其他端**没有这个概念**。浏览器里「屏幕」就是窗口，
 * 没有物理短边；所以非 Android 端用窗口短边近似 —— 语义最接近，且不会在桌面大窗口上误判成手机。
 */
@Composable
expect fun smallestScreenWidthDp(): Int
