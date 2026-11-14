package com.xjtu.toolbox.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 页面内容的采样源。Android 由 COUI 的 `CouiBackdrop` 实现（GraphicsLayer 录制 + 逐帧重绘），
 * 其它平台没有实现，[rememberCouiGlassBarBackdrop] 返回 null。
 */
interface CouiGlassBarBackdrop {
    /** 把采样层挂到页面内容节点上。同一子树只能挂一次。 */
    fun recordInto(modifier: Modifier): Modifier

    /**
     * 把这层采样源当普通面板采样用（[CouiGlassPanel] 的 `pageBackdrop`）。
     * Android 上就是同一个 `CouiBackdrop`；其它平台没有实现。
     */
    fun asPageBackdrop(): CouiPageBackdrop?
}

/**
 * COUI 材质分支的总开关。
 *
 * AGSL 管线整包移植在 androidMain/coui：COUI-Kit 0.0.1-alpha10 的浮层材质、边缘光、内发光、
 * 轮廓光、阴影与焦散、融球、弹簧与跟手光，外加 `NavigationBar` 本体（三主体底栏）。
 * 页面采样层由 [rememberCouiGlassBarBackdrop] 挂在内容节点上，底栏按 `CouiFloatingSurface` 的
 * bounds 采样。关掉即回退到 kyant 玻璃（非 Android 平台始终走那条）。
 */
const val couiGlassBarMaterialEnabled: Boolean = true

/** 当前平台能不能跑 COUI 的 AGSL 浮层材质（只有 Android）。 */
val couiGlassBarSupported: Boolean get() = couiGlassBarPlatformSupported()

expect fun couiGlassBarPlatformSupported(): Boolean

/** 页面背后的采样源；非 Android 平台返回 null（底栏回退 kyant 玻璃）。 */
@Composable
expect fun rememberCouiGlassBarBackdrop(): CouiGlassBarBackdrop?

/**
 * 整条 COUI 底栏：Android 的 actual 是 COUI-Kit `NavigationBar` 的完整移植——三主体
 * `CouiFloatingRenderer` + `couiFloatingSurfaces` 融球渲染、Skia 材质滤镜、网格投影、
 * `CouiTouchMotion` 拖动形变与 `CouiPressMotion` 按压，布局参数与手势规则全部照原厂。
 *
 * 只在 [couiGlassBarSupported] 为真且 [backdrop] 非 null 时由 `GlassBottomTabs` 调用；
 * 其它平台的 actual 是空实现，永远不会到达。
 *
 * @param tabs 底栏各格；[GlassTab.content] 装进原厂每格的容器（48dp 高、顶 4dp、导航反馈）
 * @param selectedIndex 选中格序号，-1 = 一格都不高亮（移植时放宽的宿主接缝，原厂不允许 -1）
 */
@Composable
expect fun CouiGlassBottomBar(
    backdrop: CouiGlassBarBackdrop,
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
)

/** 供宿主把采样源一路传到底栏；null 表示走原来的 kyant 玻璃。 */
val LocalCouiGlassBarBackdrop = staticCompositionLocalOf<CouiGlassBarBackdrop?> { null }
