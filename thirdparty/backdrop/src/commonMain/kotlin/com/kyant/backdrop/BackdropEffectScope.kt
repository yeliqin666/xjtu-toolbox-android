package com.kyant.backdrop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

sealed interface BackdropEffectScope : Density, RuntimeShaderCache {

    val size: Size

    val layoutDirection: LayoutDirection

    val shape: Shape

    var padding: Float

    var renderEffect: RenderEffect?
}

internal abstract class BackdropEffectScopeImpl : BackdropEffectScope, RuntimeShaderCache {

    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var size: Size = Size.Unspecified
    override var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    override var padding: Float = 0f
    override var renderEffect: RenderEffect? = null

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaderCache.obtainRuntimeShader(key, string)
    }

    /**
     * 采样层按 [scale] 缩着录（1 = 全分辨率）。
     *
     * 这一句把「特效坐标系」整体换到缩过的那一套里：`density` 乘上 scale（`8.dp.toPx()` 于是
     * 得到缩过之后的像素）、`size` 也乘上 scale（折射的 SDF、圆角半径都按层里的像素算）。
     * 于是调用方一行不改，模糊半径 / 折射位移 / padding 的外观尺寸都不变，只是真的少算了像素。
     */
    fun update(scope: DrawScope, scale: Float = 1f): Boolean {
        val newDensity = scope.density * scale
        val newFontScale = scope.fontScale
        val newSize = if (scale == 1f) scope.size else Size(scope.size.width * scale, scope.size.height * scale)
        val newLayoutDirection = scope.layoutDirection

        val changed = newDensity != density ||
                newFontScale != fontScale ||
                newSize != size ||
                newLayoutDirection != layoutDirection

        if (changed) {
            density = newDensity
            fontScale = newFontScale
            size = newSize
            layoutDirection = newLayoutDirection
        }

        return changed
    }

    fun apply(effects: BackdropEffectScope.() -> Unit) {
        padding = 0f
        renderEffect = null
        effects()
    }

    fun reset() {
        density = 1f
        fontScale = 1f
        size = Size.Unspecified
        layoutDirection = LayoutDirection.Ltr
        padding = 0f
        renderEffect = null
        runtimeShaderCache.clear()
    }
}
