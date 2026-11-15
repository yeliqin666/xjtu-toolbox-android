package com.xjtu.toolbox.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.interfaces.HoldDownInteraction

/**
 * 全局按压反馈（替掉 miuix 默认那条很急的弹簧）：缓缓压灰，松手慢慢退掉。
 * 通过 `LocalIndication` 提供，列表行、卡片这些 miuix 组件都走它。
 * 手指按下和 miuix 的「按住」（`holdDownState`，比如下拉菜单开着时那一行）都算压着。
 */
class SlowPressIndication(private val tint: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        Node(interactionSource, tint)

    override fun equals(other: Any?): Boolean = other is SlowPressIndication && other.tint == tint

    override fun hashCode(): Int = tint.hashCode()

    private class Node(
        private val source: InteractionSource,
        private val tint: Color,
    ) : Modifier.Node(), DrawModifierNode {
        private val press = Animatable(0f)
        private var running: Job? = null

        override fun onAttach() {
            coroutineScope.launch {
                var active = 0
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press, is HoldDownInteraction.HoldDown -> active++
                        is PressInteraction.Release, is PressInteraction.Cancel, is HoldDownInteraction.Release ->
                            active = (active - 1).coerceAtLeast(0)
                        else -> return@collect
                    }
                    val down = active > 0
                    running?.cancel()
                    running = launch {
                        if (down) {
                            press.animateTo(1f, tween(PRESS_MS, easing = LinearOutSlowInEasing)) { invalidateDraw() }
                        } else {
                            // 轻点也要看得见：至少压到一半再退
                            if (press.value < 0.5f) press.animateTo(0.5f, tween(90)) { invalidateDraw() }
                            press.animateTo(0f, tween(RELEASE_MS, easing = FastOutSlowInEasing)) { invalidateDraw() }
                        }
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val p = press.value
            if (p > 0f) drawRect(tint.copy(alpha = PRESS_ALPHA * p))
        }
    }

    private companion object {
        const val PRESS_MS = 220
        const val RELEASE_MS = 460
        const val PRESS_ALPHA = 0.08f
    }
}
