package com.xjtu.toolbox.ui.glass.coui.menu

import android.animation.ArgbEvaluator
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.ui.glass.coui.CouiStateProgress

/** 普通菜单项沿用 COUIMaskEffectDrawable 的色值、弹簧和短按最低进度。 */
@Composable
internal fun couiMenuItemMask(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    sharedMaskVisible: Boolean,
    dark: Boolean,
): Modifier {
    val pressColor = if (dark) 0x33FFFFFF else 0x1F000000
    val hoverColor = if (dark) 0x26FFFFFF else 0x14000000
    val evaluator = remember { ArgbEvaluator() }
    val pressProgress = remember(interactionSource, enabled) { CouiStateProgress() }
    val hoverProgress = remember(interactionSource, enabled) { CouiStateProgress() }
    DisposableEffect(pressProgress, hoverProgress) {
        onDispose { pressProgress.dispose(); hoverProgress.dispose() }
    }

    LaunchedEffect(interactionSource, enabled) {
        if (!enabled) return@LaunchedEffect
        val presses = mutableSetOf<PressInteraction.Press>()
        val hovers = mutableSetOf<HoverInteraction.Enter>()
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press,
                is PressInteraction.Release,
                is PressInteraction.Cancel -> {
                    when (interaction) {
                        is PressInteraction.Press -> presses.add(interaction)
                        is PressInteraction.Release -> presses.remove(interaction.press)
                        is PressInteraction.Cancel -> presses.remove(interaction.press)
                    }
                    pressProgress.setActive(presses.isNotEmpty(), minimumPress = true)
                }
                is HoverInteraction.Enter,
                is HoverInteraction.Exit -> {
                    when (interaction) {
                        is HoverInteraction.Enter -> hovers.add(interaction)
                        is HoverInteraction.Exit -> hovers.remove(interaction.enter)
                    }
                    hoverProgress.setActive(hovers.isNotEmpty())
                }
                else -> Unit
            }
        }
    }
    return Modifier
        .hoverable(interactionSource, enabled)
        .drawBehind {
            // 共享预选框独占交互高亮; 保留状态收集, 退场后恢复当前悬停状态。
            if (!sharedMaskVisible) {
                // StateEffectAnimator 从透明黑到目标色使用 ArgbEvaluator, 同步保留 RGB 插值。
                drawRect(Color(evaluator.evaluate(hoverProgress.value.coerceIn(0f, 1f), 0, hoverColor) as Int))
                drawRect(Color(evaluator.evaluate(pressProgress.value.coerceIn(0f, 1f), 0, pressColor) as Int))
            }
        }
}
