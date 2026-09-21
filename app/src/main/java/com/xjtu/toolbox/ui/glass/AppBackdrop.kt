package com.xjtu.toolbox.ui.glass

import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * 整个主界面共用的玻璃采样源。
 *
 * `MainScreen` 在内容根上挂一次 `Modifier.layerBackdrop(backdrop)`，再用这个 Local 往下传；
 * 底栏、搜索浮层、屁岱气泡这些玻璃都从这里采样背后的页面内容。
 *
 * **为 null 的含义是「不要玻璃」**：用户选了「经典」界面风格，或者当前界面根本没有提供采样源
 * （比如二级页里）。拿到 null 的组件一律退回原来的不透明样式，不要自己另建一个采样源凑合——
 * 两个采样源互相嵌套会形成环，RenderThread 直接 SIGSEGV。
 *
 * 二级页（比如校园卡）要做玻璃顶栏时，用那一页自己的 `rememberLayerBackdrop()`，不走这里。
 */
val LocalAppBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
