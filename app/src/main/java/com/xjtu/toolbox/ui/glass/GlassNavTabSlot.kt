// 参考 Kyant0/AndroidLiquidGlass 示例 App 的 LiquidBottomTab.kt 改写
// （分支 kmp，app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt）
// 许可证 Apache License 2.0；改动：改名 + 改包名，接受任意 content 而不依赖 Kyant 的
// LocalLiquidBottomTabScale（按压放大效果改由 [GlassBottomTabs] 统一在外层用
// graphicsLayer scale 实现，见该文件里 dampedDrag.pressProgress 的用法）。
package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.clickable
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

/**
 * 被滑块盖住的那一格轻微放大的比例（0 时不放大）。只有染色那一遍（[GlassBottomTabs] 里
 * `tintedContent`）会 provide 非默认值，真实那一遍永远是 1f——原版 `LocalLiquidBottomTabScale`
 * 就是这么用的：放大效果只在「玻璃 + 强调色」那一层可见，避免真实图标本身跳动。
 */
internal val LocalGlassNavTabScale = staticCompositionLocalOf { { 1f } }

/** 底栏里的一格：占满高度、按 `weight(1f)` 均分宽度，点击直接走 [onClick]。 */
@Composable
internal fun RowScope.GlassNavTabSlot(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = LocalGlassNavTabScale.current
    Column(
        modifier
            .fillMaxHeight()
            .weight(1f)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
