package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize

/**
 * 当前窗口的一张已经模糊过的低分辨率快照：弹出菜单铺在面板下面当毛玻璃底。
 * [image] 按 [fullSize]（窗口原尺寸，px）拉伸着画；[originOnScreen] 是窗口左上角在屏幕上的位置。
 */
class WindowSnapshot(val image: ImageBitmap, val fullSize: IntSize, val originOnScreen: Offset)

/**
 * 在**被盖住的那个窗口**里调用（不是弹层里）：[capture] 变成 true 时截一次，变回 false 时丢掉。
 * 截不到（系统太旧、桌面、Web）返回 null，调用方保持面板不透明。
 */
@Composable
expect fun rememberWindowSnapshot(capture: Boolean): WindowSnapshot?
