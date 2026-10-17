package com.xjtu.toolbox.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 底栏 / 侧栏里「某一格自己画」的缝。 */
interface ShellTabArt {

    /** [tab] 这一格要不要自绘。false = 走壳默认的图标 + 文字（也不豁免染色）。 */
    fun handles(tab: ShellTab): Boolean

    /** 画底栏里的 [tab]（经典底栏 / 玻璃胶囊 / 悬浮胶囊三处共用）。 */
    @Composable
    fun barTab(current: ShellTab?, tab: ShellTab, selected: Boolean, variant: ShellTabVariant)

    /** 画侧栏（宽屏）里的 [tab]。 */
    @Composable
    fun railTab(current: ShellTab?, tab: ShellTab, expanded: Boolean, variant: ShellTabVariant)

    companion object {

        /** 这一端没有自绘的格子：每格都按 [ShellTab.icon] + label 画，也不豁免玻璃底栏的染色。 */
        val None: ShellTabArt = object : ShellTabArt {
            override fun handles(tab: ShellTab): Boolean = false

            @Composable
            override fun barTab(current: ShellTab?, tab: ShellTab, selected: Boolean, variant: ShellTabVariant) = Unit

            @Composable
            override fun railTab(current: ShellTab?, tab: ShellTab, expanded: Boolean, variant: ShellTabVariant) = Unit
        }
    }
}

/** 自绘那一格要跟着底栏变的几样。 */
class ShellTabVariant(
    val diameter: Dp,
    val paper: Color,
    val modifier: Modifier = Modifier,
    val liftUp: Dp = 0.dp,
    val vertical: Boolean = false,
    val onClick: () -> Unit = {},
)
