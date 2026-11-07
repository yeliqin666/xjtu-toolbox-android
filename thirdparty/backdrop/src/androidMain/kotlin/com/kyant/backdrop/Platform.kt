package com.kyant.backdrop

import android.os.Build

// 上游这两个函数上还有 @ChecksSdkIntAtLeast（只给 lint 用）；vendor 时去掉了，免得为了一个
// 注解引入 androidx.annotation:annotation-experimental 依赖。判等逻辑与上游 2.0.1 一字不差。
actual fun isRenderEffectSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

actual fun isRuntimeShaderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
