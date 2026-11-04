package com.xjtu.toolbox.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
 * 「从这一块打开某页」的应用级入口：Android 端在 ROM 支持一镜到底、页面能独立托管、会话就绪时
 * 走系统级转场，否则照常压路由。没有提供（桌面、Web、一镜到底的独立宿主里）时为 null。
 */
val LocalSeamlessOpener = staticCompositionLocalOf<((AppRoute, SeamlessCardSource?) -> Unit)?> { null }

/**
 * 一块「点了打开某页」的卡片：把 [modifier] 挂在转场要从它长出来的那一块上，点击时调 [open]。
 * 坐标只在点击那一刻读，滚动时没有额外开销。
 */
@Stable
class SeamlessOrigin internal constructor(
    private val source: ExpandOriginSource,
    private val density: Density,
    private val radius: Dp,
    private val opener: ((AppRoute, SeamlessCardSource?) -> Unit)?,
) {
    val modifier: Modifier = Modifier.expandOriginSource(source)

    /**
     * 先登记 Compose 放大转场的起点（走 KIT 时会被清掉，回落时正好用上），再交给 [LocalSeamlessOpener]；
     * 这一端没有它时调 [fallback]（各页原来的打开方式）。
     */
    fun open(route: AppRoute, fallback: () -> Unit) {
        source.arm(route.id, radius, density, null)
        val opener = opener ?: return fallback()
        opener(route, source.boundsInWindow()?.let { SeamlessCardSource(it, radius) })
    }
}

@Composable
fun rememberSeamlessOrigin(radius: Dp): SeamlessOrigin {
    val source = rememberExpandOriginSource()
    val density = LocalDensity.current
    val opener = LocalSeamlessOpener.current
    return remember(source, density, radius, opener) { SeamlessOrigin(source, density, radius, opener) }
}
