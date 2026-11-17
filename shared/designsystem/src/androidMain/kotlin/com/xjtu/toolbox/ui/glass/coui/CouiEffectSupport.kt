package com.xjtu.toolbox.ui.glass.coui

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * COUI 的 AGSL / Mesh 特效只在 Android 15 起开启。
 * 更低版本保留布局与手势，材质退成普通模糊或纯色，光效、焦散与投影不画。
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
val couiEffectsSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
