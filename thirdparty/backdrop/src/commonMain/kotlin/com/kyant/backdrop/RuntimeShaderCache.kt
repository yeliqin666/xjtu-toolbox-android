package com.kyant.backdrop

sealed interface RuntimeShaderCache {

    fun obtainRuntimeShader(key: String, string: String): RuntimeShader
}

/**
 * [岱宗盒子 vendor 改动] 整个进程每种着色器只编译一次。上游是每个玻璃节点各编一份，首页几十张卡
 * 在第一次绘制那一帧里各编一遍（Haze #1351 实测几块玻璃就要主线程约 9ms）。
 *
 * 共用是安全的：每处使用前都把这种着色器的全部 uniform 重设一遍，RenderEffect 与画笔取用时
 * 拿的是当时参数的快照，之后别处再改不影响已经录下的。只在 UI 线程使用。
 */
internal object SharedRuntimeShaders : RuntimeShaderCache {

    private val runtimeShaders = mutableMapOf<String, RuntimeShader>()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaders.getOrPut(key) { RuntimeShader(string) }
    }
}
