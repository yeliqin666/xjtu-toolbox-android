package com.xjtu.toolbox.ui.glass.coui

import android.graphics.RuntimeShader
import android.os.Looper

/**
 * 同一段 AGSL 全进程只编译一次：`RuntimeShader(String)` 构造即编译，系统不按源码缓存，
 * 每个按钮、课格各建一份时，打开页面那一帧要在主线程编译几十次。
 *
 * 共享前提：主线程使用，且每次用之前把全部 uniform 设好、随即建 RenderEffect 或录制——
 * 两者都会给 uniform 拍快照。uniform 只设一次就长期挂在 Paint 上的 Shader 不能从这里取。
 * 只在 [couiEffectsSupported] 时调用。
 */
internal object CouiShaderCache {
    private val shaders = HashMap<String, RuntimeShader>()

    fun get(source: String): RuntimeShader {
        // 非主线程拿不到共享保证，退回独占实例
        if (Looper.myLooper() != Looper.getMainLooper()) return RuntimeShader(source)
        return shaders.getOrPut(source) { RuntimeShader(source) }
    }
}
