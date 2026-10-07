package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/**
 * 平台能力：**屏幕常亮**（把当前窗口标记为 keep-screen-on）。
 *
 * 通话、视频、棋盘/游戏这类"用户盯着看且不碰屏"的界面需要它。Android = `View.keepScreenOn`；
 * jvm / Web 没有这个概念（桌面窗口由系统电源策略管、浏览器有 Screen Wake Lock 但需另接），
 * 空实现即可，UI 无需降级。
 *
 * [enabled] 变化时自动跟随，离开组合时恢复——调用方只声明"我现在要不要常亮"。
 */
@Composable
expect fun KeepScreenOn(enabled: Boolean)
