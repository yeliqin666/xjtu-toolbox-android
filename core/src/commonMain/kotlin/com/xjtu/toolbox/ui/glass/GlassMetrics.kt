package com.xjtu.toolbox.ui.glass

import androidx.compose.ui.unit.dp

/**
 * 手机竖屏玻璃底栏本体的高度。`GlassBottomTabs` 的胶囊与 `MainScreen` 的浮空占位都取它。
 *
 * 常量原本在 :app 的 `main/BottomTab.kt` 里（`internal val`），随 `GlassBottomTabs` 搬进
 * :core 后放在这里：跨模块的 `internal` 不可见，所以必须是 public。
 * 取值逐字未改，:app 侧改为 import 这个常量。
 */
val GLASS_BAR_HEIGHT = 58.dp
