package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.pressScale

/**
 * 有色玻璃卡片：给课程表课格这类「带颜色的块」用的 COUI 表面。
 *
 * Android 玻璃风格下复用 COUI 有色浮层（`CouiFabDefaults` 的材质与投影）：
 * AGSL 材质、边缘光、内光晕、轮廓投影、按压弹簧都来自原厂实现；
 * **不挂跟手光、也不做跟手形变**，按压只做弹簧缩放。
 *
 * 拿不到顶栏采样源时（经典风格 / 非 Android 平台）退回半透明色块，
 * 颜色、圆角、点击与按压反馈与玻璃版保持一致。
 */
@Composable
expect fun CouiGlassCard(
    color: Color,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
)

/** 没有 AGSL 采样源时的色块：半透明底色 + 按压缩放。 */
@Composable
internal fun CouiGlassCardFallback(
    color: Color,
    modifier: Modifier,
    cornerRadius: Dp,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(color.copy(alpha = 0.85f))
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            enabled = enabled,
                            onClick = onClick,
                        )
                        .pressScale(interaction, pressed = 0.96f)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.TopStart,
    ) {
        content()
    }
}
