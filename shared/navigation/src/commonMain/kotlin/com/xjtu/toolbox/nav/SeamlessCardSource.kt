package com.xjtu.toolbox.nav

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp

/**
 * 一次「卡片 → 页面」无缝转场的源信息。
 *
 * 只描述几何：卡片在**窗口坐标系**里的矩形 + 圆角。真正的截图与动画由宿主（Android 端）负责：
 * - ROM 支持金标联盟 SharedViewKit 时，宿主用 [boundsInWindow] 截一张 Bitmap 交给 KIT；
 * - 不支持时走 [ExpandOrigins] 的 Compose 转场，矩形与快照也用它。
 *
 * 放进 `:shared:navigation` 是为了让 `:feature:*`（commonMain）与 `:app` 共用同一个类型，
 * 又不把 Android 类型带进共享层。
 */
data class SeamlessCardSource(
    /** 卡片在窗口里的矩形（px 浮点，Compose `boundsInWindow()` 的值）。 */
    val boundsInWindow: Rect,
    /** 卡片圆角；宿主按屏幕密度换算成 px。 */
    val cornerRadius: Dp,
)

/**
 * 卡片/图标矩形的“非 Compose 状态”记录器。
 *
 * 首页是整页滚动，`onGloballyPositioned` 在滚动/拖动时每帧都会回调；把这些矩形写进
 * `mutableStateOf` 会让每张可见卡片每帧重组一次，明显的掉帧来源。这里只存普通字段，
 * 点击时再读，不参与重组。
 */
class CardBoundsRef(var value: Rect = Rect.Zero)

