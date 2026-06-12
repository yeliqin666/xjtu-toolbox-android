package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.utils.SinkFeedback

@Composable
fun AmbientGlow(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .blur(size / 3)
            .background(
                Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.42f), Color.Transparent),
                ),
            ),
    )
}

@Composable
fun ExpressiveIcon(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
    iconSize: Dp = 27.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .squircleSurface(
                color = color.copy(alpha = 0.16f),
                cornerRadius = size * 0.31f,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size * 0.7f)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.2f), Color.Transparent),
                    ),
                ),
        )
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun ExpressivePanel(
    modifier: Modifier = Modifier,
    accent: Color,
    cornerRadius: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .squircleSurface(
                color = accent.copy(alpha = 0.08f),
                cornerRadius = cornerRadius,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = SinkFeedback(),
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}
