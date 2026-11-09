// 参考 Kyant0/AndroidLiquidGlass 示例 App 的 LiquidBottomTab.kt 改写
// （分支 kmp，app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt）
// 许可证 Apache License 2.0；改动：改名 + 改包名，接受任意 content 而不依赖 Kyant 的
// LocalLiquidBottomTabScale；按压改成常见的微暗 + 缩小。
package com.xjtu.toolbox.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** 滑块被按住时染色那一遍图标的透明度系数（只有 [GlassBottomTabs] 的 `tintedContent` 会 provide）。 */
val LocalGlassNavTabFade = staticCompositionLocalOf { { 1f } }

/**
 * 底栏里的一格：占满高度、按 `weight(1f)` 均分宽度，点击直接走 [onClick]。
 *
 * 按下时图标变淡（不缩放）。[pressSource] 由底栏按格子共用：触摸可能落在透明的染色那一遍上，
 * 共用同一个来源，看得见的那一遍才会跟着变淡。直接听按压事件而不是「是否按着」的状态：
 * 快速点一下时按下与抬起常落在同一帧，只看状态就一帧都画不出来。
 */
@Composable
fun RowScope.GlassNavTabSlot(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    pressSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fade = LocalGlassNavTabFade.current
    val press = remember { Animatable(0f) }
    if (pressSource != null) {
        LaunchedEffect(pressSource) {
            var down: Job? = null
            pressSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> down = launch { press.animateTo(1f, tween(180)) }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        val pending = down
                        launch {
                            pending?.join()
                            press.animateTo(0f, tween(380))
                        }
                    }
                }
            }
        }
    }
    Column(
        modifier
            .fillMaxHeight()
            .weight(1f)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = pressSource,
                        indication = null,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .graphicsLayer {
                alpha = (1f - 0.5f * press.value) * fade()
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
