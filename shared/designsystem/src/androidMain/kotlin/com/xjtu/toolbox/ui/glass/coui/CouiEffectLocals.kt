package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.runtime.compositionLocalOf

/** 统一控制 COUI 描边与内光晕, 不控制模糊、材质、跟手光或外投影。 */
val LocalCouiContourGlowEnabled = compositionLocalOf { true }
