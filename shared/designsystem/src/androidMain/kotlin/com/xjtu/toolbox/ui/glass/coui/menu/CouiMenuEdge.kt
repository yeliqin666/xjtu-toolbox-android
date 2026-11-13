package com.xjtu.toolbox.ui.glass.coui.menu

import android.graphics.RenderEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.xjtu.toolbox.ui.glass.coui.CouiEdge
import com.xjtu.toolbox.ui.glass.coui.CouiEdgeStyle
import com.xjtu.toolbox.ui.glass.coui.LocalCouiContourGlowEnabled

@Composable
internal fun rememberCouiMenuEdge(dark: Boolean, radiusPx: Float, cornerWeight: Float, mix: Color, blend: Color): CouiMenuEdge {
    val contourGlowEnabled = LocalCouiContourGlowEnabled.current
    return remember(dark, radiusPx, cornerWeight, mix, blend, contourGlowEnabled) {
        CouiMenuEdge(
            CouiEdge(
                style = if (dark) CouiEdgeStyle.Dark else CouiEdgeStyle.Light,
                radiusPx = radiusPx,
                cornerWeight = cornerWeight,
                mix = mix,
                blend = blend,
                innerGlowAlpha = 0.6f * if (dark) 0.1f else 0.9f,
                contourGlowEnabled = contourGlowEnabled,
                lineAlpha = if (dark) 0.60f else 0.66f,
            ),
        )
    }
}

/** 菜单只提供材质配色并缓存 Compose effect, 原始模糊图直接进入公共 Shader。 */
internal class CouiMenuEdge(private val edge: CouiEdge) {
    private var lastEdge: RenderEffect? = null
    private var cachedEffect: androidx.compose.ui.graphics.RenderEffect? = null

    fun renderEffect(size: Size, density: Float): androidx.compose.ui.graphics.RenderEffect {
        val effect = edge.renderEffect(size, density)
        cachedEffect?.let { if (lastEdge === effect) return it }
        return effect.asComposeRenderEffect().also {
            lastEdge = effect
            cachedEffect = it
        }
    }
}
