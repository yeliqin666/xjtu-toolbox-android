package com.xjtu.toolbox.ui.liquid_glass_theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class LiquidGlassNavItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
fun LiquidGlassNavigationBar(
    items: List<LiquidGlassNavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val isDark = isSystemInDarkTheme()
    val accentColor = MiuixTheme.colorScheme.primary
    val contentColor = if (isDark) Color.White else Color(0xFF111318)
    val secondaryContentColor = contentColor.copy(alpha = 0.58f)
    val containerColor =
        if (isDark) Color(0xFF111318).copy(alpha = 0.46f)
        else Color.White.copy(alpha = 0.44f)
    val indicatorColor =
        if (isDark) Color.White.copy(alpha = 0.12f)
        else Color.Black.copy(alpha = 0.08f)
    val containerShape = RoundedCornerShape(32.dp)

    BoxWithConstraints(
        modifier = modifier
            .height(68.dp)
            .fillMaxWidth()
    ) {
        val tabWidth = maxWidth / items.size
        val clampedIndex = selectedIndex.coerceIn(0, items.lastIndex)
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * clampedIndex.toFloat(),
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
            label = "liquid-nav-indicator"
        )

        Box(
            Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { containerShape },
                    effects = {
                        vibrancy()
                        blur(10.dp.toPx())
                        lens(
                            refractionHeight = 18.dp.toPx(),
                            refractionAmount = 24.dp.toPx(),
                            depthEffect = true,
                            chromaticAberration = true
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.72f) },
                    shadow = { Shadow(alpha = 0.6f) },
                    innerShadow = {
                        InnerShadow(
                            radius = 14.dp,
                            color = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f),
                            alpha = 0.72f
                        )
                    },
                    onDrawSurface = {
                        drawRect(containerColor)
                        drawRect(accentColor.copy(alpha = if (isDark) 0.10f else 0.07f))
                    }
                )
        )

        Box(
            Modifier
                .padding(4.dp)
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .padding(horizontal = 2.dp)
                .then(Modifier)
                .clip(RoundedCornerShape(28.dp))
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(28.dp) },
                    effects = {
                        lens(
                            refractionHeight = 10.dp.toPx(),
                            refractionAmount = 16.dp.toPx(),
                            depthEffect = true,
                            chromaticAberration = true
                        )
                    },
                    highlight = { Highlight.Ambient.copy(alpha = 0.8f) },
                    shadow = { Shadow(radius = 18.dp, alpha = 0.35f) },
                    innerShadow = { InnerShadow(radius = 8.dp, alpha = 0.55f) },
                    onDrawSurface = {
                        drawRect(indicatorColor)
                        drawRect(accentColor.copy(alpha = if (isDark) 0.14f else 0.11f))
                    }
                )
        )

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == clampedIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(28.dp))
                        .clickable(
                            role = Role.Tab,
                            indication = null,
                            interactionSource = null
                        ) { onItemSelected(index) },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.label,
                        modifier = Modifier.size(22.dp),
                        tint = if (selected) accentColor else secondaryContentColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) accentColor else secondaryContentColor
                    )
                }
            }
        }
    }
}
