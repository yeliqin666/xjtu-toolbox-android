// 参考 Kyant0/AndroidLiquidGlass 示例 App 的 LiquidBottomTab.kt 改写
// （分支 kmp，app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt）
// 许可证 Apache License 2.0；改动：改名 + 改包名，接受任意 content 而不依赖 Kyant 的
// LocalLiquidBottomTabScale；按压改成常见的微暗 + 缩小。
package com.xjtu.toolbox.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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

/** 滑块被按住时染色那一遍图标的缩放（只有 [GlassBottomTabs] 的 `tintedContent` 会 provide）。 */
val LocalGlassNavTabScale = staticCompositionLocalOf { { 1f } }

/**
 * 底栏里的一格：占满高度、按 `weight(1f)` 均分宽度，点击直接走 [onClick]。
 *
 * 按下时微暗 + 缩小。[pressSource] 由底栏按格子共用：触摸可能落在透明的染色那一遍上，
 * 共用同一个来源，看得见的那一遍才会跟着按下去。
 */
@Composable
fun RowScope.GlassNavTabSlot(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    pressSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = LocalGlassNavTabScale.current
    val pressed = pressSource?.collectIsPressedAsState()?.value == true
    val press = animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 700f), label = "tabPress")
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
                val p = press.value
                val s = scale() * (1f - 0.1f * p)
                scaleX = s
                scaleY = s
                alpha = 1f - 0.35f * p
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
