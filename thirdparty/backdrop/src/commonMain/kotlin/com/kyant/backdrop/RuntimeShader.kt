package com.kyant.backdrop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
expect fun RuntimeShader(shaderString: String): RuntimeShader

expect fun RuntimeShader.asComposeShader(): Shader

/**
 * [vendor] 图片转成双线性采样、边缘夹取的着色器，给 [RuntimeShader.setInputShader] 当子着色器用。
 * 不直接用 Compose 的 `ImageShader`：子着色器里取样时拿不到画笔的过滤标志，Android 上会退成最近邻，放大时一格一格的。
 */
expect fun androidx.compose.ui.graphics.ImageBitmap.toLinearShader(): Shader

interface RuntimeShader {

    /** [vendor] 给着色器里的 `uniform shader name;` 接一个子着色器（比如 [toLinearShader] 的图）。 */
    fun setInputShader(name: String, shader: Shader)


    fun setFloatUniform(name: String, value: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float)
    fun setFloatUniform(name: String, values: FloatArray)

    fun setIntUniform(name: String, value: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int)
    fun setIntUniform(name: String, values: IntArray)

    fun setColorUniform(name: String, color: Color)
}
