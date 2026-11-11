package com.xjtu.toolbox.ui.glass.coui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.ceil

/** 随显示密度缓存平台模糊, 不在滚动时重建滤镜。 */
class CouiCoverageBlurEffect(radiusPx: Float) {
    val padding: Float = ceil(radiusPx)
    private val blur = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.DECAL)

    val effect: RenderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        normalizeCoverage(blur)
    } else {
        blur
    }
}

/**
 * 顶部栏采样包含不透明页面背景, 平台模糊在屏幕边缘会混入透明外沿。
 * 在渐隐遮罩之前恢复有效样本的颜色与覆盖度, 避免原始正文从顶边透出。
 * 此处只读取一次已模糊结果, 卷积与降采样均交由平台执行。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun normalizeCoverage(blur: RenderEffect): RenderEffect {
    val shader = RuntimeShader(
        """
            uniform shader content;

            float4 main(float2 coord) {
                float4 blurred = content.eval(coord);
                if (blurred.a > 0.0) {
                    return float4(blurred.rgb / blurred.a, 1.0);
                }
                return float4(0.0);
            }
        """.trimIndent(),
    )
    return RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(shader, "content"),
        blur,
    )
}
