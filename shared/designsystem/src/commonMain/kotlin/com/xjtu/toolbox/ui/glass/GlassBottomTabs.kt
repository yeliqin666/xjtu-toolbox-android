// 参考 Kyant0/AndroidLiquidGlass 示例 App 的 LiquidBottomTabs.kt 改写
// （分支 kmp，app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt，288 行）
// 许可证 Apache License 2.0。改动（对照计划 §9.3）：
//   1. 强调色从写死的 iOS 蓝改成 MiuixTheme.colorScheme.primary；容器色改成跟随本项目主题的
//      surfaceContainerHigh，深浅色模式跟随本项目的 LocalIsDarkTheme（不是 isSystemInDarkTheme）。
//   2. 「染色那一遍」改成每格按选中态画（不再整行 ColorFilter，徽标保持原色）；是否参与由调用方
//      按格子指定（[GlassTab.tintExempt]）；被排除的格子在染色那一遍里只画一个同尺寸空占位，不重新创建一份
//      内容（原版两遍都创建同一份 content，给屁岱用会跑出两个实例，见计划 §9.3 第 3 条）。
//   3. 原版只接收一个 `content: @Composable RowScope.() -> Unit`，点击行为由外部各自的
//      LiquidBottomTab(onClick = ...) 决定；这里改成显式的 `tabs: List<GlassTab>` +
//      `onTabSelected`，点击和拖动松手都统一走这一个回调（契约 §9.3A）。
//   4. 加了 `glass: Boolean` 开关：为 false 时完全不调用 drawBackdrop / 高光着色器，退回一个
//      不透明胶囊（用于「界面风格：经典」，见计划 §16.1 第 5 条）。
//   5. 加了 `exportedBackdrop`，透传给底栏本体的 drawBackdrop，供屁岱主动气泡（Y5）用
//      exportedBackdrop 采样「页面内容 + 底栏」合成的一层（计划 §16.6）。
package com.xjtu.toolbox.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.effects.lens
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * 底栏玻璃按 1/2 分辨率模糊。
 *
 * 半径按 COUI 对齐：它的 RenderEffect 模糊是 25px（约 9.5dp @2.625x），这里同样取 9.5dp，
 * 采样层降一半分辨率后再放大，填充量只剩四分之一。
 */
private const val GLASS_BAR_DOWNSCALE = 2

/** COUI 材质的模糊半径（px）。 */
private const val GLASS_BLUR_DP = 9.5f

/** 胶囊按压时的缩放，对齐 COUI `CouiPressMotion` 对宽条的取值（约 0.92）。 */
private const val GLASS_BAR_PRESS_SCALE = 0.93f

/**
 * 底栏的一格。
 *
 * @param key 稳定标识，一般传 tab 的枚举值本身，方便调用方在 [onTabSelected] 里反查。
 * @param tintExempt true = 滑块经过时不染色，染色那一遍里只放同尺寸的空占位——给屁岱用
 *   （见计划 §9.3 第 3 条：屁岱只有一个实例，不能被复制出第二份）。
 *   只对 kyant 回退路径有意义；COUI 路径只录一遍内容，不存在第二份实例。
 * @param title 格子的无障碍/默认文案（COUI 路径用来构造 `CouiNavigationItem`）。
 * @param icon 格子的默认图标（同上；[content] 整格替换时不显示）。
 * @param content 格子内容（一般是图标 + 文字），`selected` 是当前是否选中这一格。
 */
class GlassTab(
    val key: Any,
    val tintExempt: Boolean = false,
    val title: String = "",
    val icon: ImageVector? = null,
    val content: @Composable ColumnScope.(selected: Boolean) -> Unit,
)

/**
 * 玻璃底栏：大号、可以按住滑块拖着走的悬浮胶囊，效果移植自 Kyant `LiquidBottomTabs`。
 *
 * 用法（R2 在 `MainScreen.kt` 接入，示例见 A4 的收尾报告「R2 接入指南」一节）：
 * ```
 * val backdrop = LocalAppBackdrop.current
 * if (backdrop != null) {
 *     GlassBottomTabs(
 *         tabs = BottomTab.entries.map { tab -> GlassTab(key = tab, tintExempt = tab == BottomTab.PIDAI) { selected -> ... } },
 *         selectedIndex = selectedTabOrdinal,
 *         onTabSelected = { index -> ... },
 *         backdrop = backdrop,
 *         glass = interfaceStyle == "glass",
 *     )
 * }
 * ```
 *
 * @param selectedIndex 选中那一格的序号。**-1 = 一格都不高亮**（滑块不画、每格都按未选中态画）：
 *   当前页不属于任何一格时就是这样（见 `ShellNavState.selected`，例如桌面上一条非 tab 路由）；
 *   拖动 / 点击仍会把选中落到某一格上（那时这个值已经变了）。
 */
@Composable
fun GlassBottomTabs(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    exportedBackdrop: LayerBackdrop? = null,
    glass: Boolean = true,
) {
    val tabsCount = tabs.size
    if (tabsCount == 0) return

    // 整条交给 COUI 的 NavigationBar 移植（三主体融球、材质、投影、形变全部原厂实现）；
    // 只有非 Android 平台 / 显式关玻璃 / 没有采样源时才走下面 kyant 的玻璃实现。
    val couiBackdrop = LocalCouiGlassBarBackdrop.current
    if (couiGlassBarMaterialEnabled && glass && couiBackdrop != null && couiGlassBarSupported) {
        CouiGlassBottomBar(couiBackdrop, tabs, selectedIndex, onTabSelected, modifier)
        return
    }

    val isDark = LocalIsDarkTheme.current
    val accentColor = MiuixTheme.colorScheme.primary
    // 材质：容器色压低到 0.28，配合更大的模糊半径，让正文能像 COUI 那样透出来
    val containerColor = MiuixTheme.colorScheme.surfaceContainerHigh.let {
        if (glass) it.copy(alpha = 0.28f) else it
    }
    val barShadow = remember(accentColor, isDark) { floatingGlassShadow(accentColor, isDark) }
    // 形状改用 COUI 的 sdCapsule 轮廓（两端真半圆，曲率连续），不再是 28dp 圆角矩形。
    // 代价：Outline.Generic 不是 CornerBasedShape，`lens()`（折射）对这个形状不可用。
    val pillShape = remember { GlassCapsuleShape }

    // 内容行高度 = 本体高 − 上下各 4dp 内衬。本体高取 GLASS_BAR_HEIGHT（MainScreen 的浮空占位
    // 也按它算），滑块、染色层、屁岱真身那几层都跟着它，以后调高度只改那一个常量。
    val innerHeight = GLASS_BAR_HEIGHT - GLASS_BAR_PADDING_V * 2


    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val padHPx = with(density) { GLASS_BAR_PADDING_H.toPx() }
        val gapPx = with(density) { GLASS_BAR_ITEM_GAP.toPx() }
        // 每格宽 = (本体宽 − 左右内边距 − 格间距) / 格数，对齐 COUI 的 12dp 内边距 + 8dp 间距
        val tabWidthPx = ((constraints.maxWidth.toFloat() - padHPx * 2f - gapPx * (tabsCount - 1))
            .coerceAtLeast(0f)) / tabsCount
        // 相邻两格中心之间的距离：滑块的位移与拖动的命中判定都按它算
        val stepPx = tabWidthPx + gapPx

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, constraints.maxWidth) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth.toFloat()).coerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        // -1 = 一格都不高亮（见 selectedIndex 的 KDoc）：索引本身允许是 -1，只有滑块的位置要它 ≥ 0
        var currentIndex by remember { mutableIntStateOf(selectedIndex.takeIf { it in 0 until tabsCount } ?: -1) }
        val onTabSelectedUpdated by rememberUpdatedState(onTabSelected)
        // 下面的拖动状态机只建一次，宽度、方向变了（转屏、改语言）要读最新的
        val latestTabWidthPx by rememberUpdatedState(tabWidthPx)
        val latestLtr by rememberUpdatedState(isLtr)

        // COUI 的按压是「整颗胶囊一起缩」，不是每格淡出：这里给本体一个弹簧缩放
        var barPressed by remember { mutableStateOf(false) }
        val barScale by animateFloatAsState(
            targetValue = if (barPressed) GLASS_BAR_PRESS_SCALE else 1f,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "glassBarPressScale",
        )
        // 折射与边缘高光随按压进度浮出来（7ed7a4d4 之前就是这个行为）：
        // 静止时 lens 传 0 会直接 return，不跑着色器，滚动时不多付一遍全条采样
        val pressProgress by animateFloatAsState(
            targetValue = if (barPressed) 1f else 0f,
            animationSpec = tween(if (barPressed) 220 else 360),
            label = "glassBarPressProgress",
        )
        // 拖动时的预选格（COUI 的 previewSelection）：只影响高亮，松手才提交
        var previewIndex by remember { mutableIntStateOf(-1) }
        // COUI 的橡皮筋位移 + 形变：拖动时胶囊跟着手指走、并被拉长压扁
        val deform = rememberGlassBarDeform()
        fun isHighlighted(index: Int) = index == (if (previewIndex >= 0) previewIndex else currentIndex)

        val dampedDrag = remember(animationScope, tabsCount) {
            GlassDampedDragAnimation(
                animationScope = animationScope,
                initialValue = currentIndex.coerceAtLeast(0).toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                // COUI 按压时整颗胶囊缩，滑块本身不变形，所以两轴都不缩
                pressedScale = 1f,
                pressedScaleY = 1f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = GlassDragMath.snappedIndex(targetValue, tabsCount)
                    if (currentIndex != targetIndex) {
                        currentIndex = targetIndex
                        onTabSelectedUpdated(targetIndex)
                    }
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
                    }
                },
                onDrag = { _, dragAmount ->
                    val deltaX = if (latestLtr) dragAmount.x else -dragAmount.x
                    updateValue(GlassDragMath.dragTargetValue(targetValue, deltaX, latestTabWidthPx, tabsCount))
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
            )
        }

        // 选中项被外部改动（比如程序切 tab）时，把滑块同步过去，但不重复回调 onTabSelected。
        LaunchedEffect(selectedIndex, tabsCount) {
            val target = selectedIndex.takeIf { it in 0 until tabsCount } ?: -1
            if (currentIndex != target) {
                currentIndex = target
                if (target >= 0) dampedDrag.animateToValue(target.toFloat())
            }
        }

        // 手势照抄 COUI：整条胶囊由一根手指接管，Main pass，每个事件都 consume；
        // 按下记 down，移动改预选（跳格，不跟手滑动），抬起按 COUI 的三条规则提交。
        // 这里不挂 clickable——各格只留无障碍语义（GlassNavTabSlot 的 clickable = false）。
        val dragAnywhere = Modifier.pointerInput(tabsCount, stepPx, isLtr) {
            fun hitAt(x: Float): Int {
                val local = (if (isLtr) x else size.width - x) - padHPx
                return (local / stepPx).toInt().coerceIn(0, tabsCount - 1)
            }
            awaitPointerEventScope {
                val velocity = VelocityTracker()
                var activeId: PointerId? = null
                var downIndex = -1
                var pendingIndex = -1
                var preview = currentIndex
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val tracked = event.changes.firstOrNull { it.id == activeId }
                    val pointer = tracked?.takeIf { it.pressed }
                        ?: event.changes.firstOrNull { it.pressed }
                        ?: tracked ?: continue
                    if (activeId == null) {
                        downIndex = hitAt(pointer.position.x)
                        preview = currentIndex
                        // 上一次手势若被父级抢走（没有收到 up），预选会残留，这里清掉
                        previewIndex = -1
                        barPressed = true
                        velocity.resetTracking()
                        velocity.addPosition(pointer.uptimeMillis, pointer.position)
                        deform.begin(pointer.position, size)
                    } else if (pointer.pressed && pointer.position != pointer.previousPosition) {
                        velocity.addPosition(pointer.uptimeMillis, pointer.position)
                        deform.move(pointer.position)
                        val index = hitAt(pointer.position.x)
                        if (index != preview) {
                            preview = index
                            pendingIndex = index
                            previewIndex = index
                            dampedDrag.snapToValue(index.toFloat())
                        }
                    }
                    activeId = pointer.id
                    if (!pointer.pressed) {
                        val released = velocity.calculateVelocity()
                        deform.release(Offset(released.x, released.y))
                        val up = hitAt(pointer.position.x)
                        // 同 COUI couiNavigationReleaseTarget 的三条规则
                        val target = when {
                            up != downIndex && up != preview -> up
                            pendingIndex >= 0 && pendingIndex == preview -> pendingIndex
                            up == downIndex && up != preview -> up
                            else -> -1
                        }
                        previewIndex = -1
                        if (target >= 0 && target != currentIndex) {
                            currentIndex = target
                            onTabSelectedUpdated(target)
                        }
                        dampedDrag.snapToValue((if (target >= 0) target else currentIndex).coerceAtLeast(0).toFloat())
                        activeId = null
                        pendingIndex = -1
                        downIndex = -1
                        barPressed = false
                    }
                    // 每个事件都吃掉：与 COUI 一致，避免下层再起第二套手势
                    event.changes.forEach { it.consume() }
                }
            }
        }

        fun tabOnClick(index: Int): () -> Unit = {
            if (currentIndex != index) {
                currentIndex = index
                onTabSelectedUpdated(index)
            }
            dampedDrag.animateToValue(index.toFloat())
        }

        // 第一遍：正常显示的那一排，点击、按住拖动都从这里发生。
        // tintExempt 的格子（屁岱）这里只放空占位，真身画在最上面那一层（见文件末尾的 exemptOverlay）：
        // 滑块采样的是「页面 + 染色那一遍」，染色那一遍里屁岱是空的，
        // 屁岱要是画在滑块下面，滑块一经过就把它盖成空白（点屁岱、拖到屁岱都会这样）。
        val realContent: @Composable RowScope.() -> Unit = {
            tabs.forEachIndexed { index, tab ->
                if (tab.tintExempt) {
                    GlassNavTabSlot(onClick = null) {}
                } else {
                    GlassNavTabSlot(
                        onClick = tabOnClick(index),
                        selected = isHighlighted(index),
                        clickable = false,
                    ) {
                        tab.content(this, isHighlighted(index))
                    }
                }
            }
        }

        val barWidthPx = constraints.maxWidth.toFloat()
        val tabWidthDp = with(density) { tabWidthPx.toDp() }
        // 第 index 格左边缘在父容器里的 x（RTL 时从右边算），给滑块定位用
        fun tabStartPx(index: Float): Float =
            if (isLtr) padHPx + index * stepPx
            else barWidthPx - padHPx - tabWidthPx - index * stepPx

        // 所有层都包在这一个 Box 里：拖动手势挂在它们共同的祖先上（挂在最上面的兄弟层会吞掉各格的点击）；
        // 按压缩放也挂在这里，等于整颗胶囊一起缩（COUI 的按压方式）。
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = deform.translationX
                    translationY = deform.translationY
                    scaleX = deform.scaleX * barScale
                    scaleY = deform.scaleY * barScale
                }
                .then(dragAnywhere),
            contentAlignment = Alignment.CenterStart,
        ) {
            // kyant 玻璃（非 Android / 关玻璃时的回退）：整条胶囊一次采样 + 染色
            Row(
                Modifier
                    .graphicsLayer { translationX = panelOffset }
                    .then(
                        if (glass) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { pillShape },
                                effects = {
                                    // 采样范围往外扩一圈，胶囊边缘处也有真实内容可混
                                    padding = maxOf(padding, 16.dp.toPx())
                                    vibrancy()
                                    blur(GLASS_BLUR_DP.dp.toPx() / GLASS_BAR_DOWNSCALE)
                                    // 液态玻璃的折射：按住时从 0 长到 24dp，静止为 0 不跑 shader
                                    lens(24.dp.toPx() * pressProgress, 24.dp.toPx() * pressProgress)
                                },
                                sampleDownscale = GLASS_BAR_DOWNSCALE,
                                exportedBackdrop = exportedBackdrop,
                                // 胶囊边缘的高光：日间淡、夜间亮，和 COUI 的 edgeAlpha 同一档
                                highlight = {
                                    Highlight.Default.copy(
                                        alpha = (if (isDark) 0.8f else 0.35f) + 0.4f * pressProgress,
                                    )
                                },
                                // 悬浮胶囊是真的浮在内容上面，该有影子；但不要 kyant 默认那圈
                                // 死黑（24dp、10% 黑）。改成带一点主题色的环境光阴影：更散、更低，
                                // 颜色跟着主题走，像胶囊被页面的光托着，而不是压了一块灰。
                                shadow = { barShadow },
                                onDrawSurface = { drawRect(containerColor) },
                            )
                        } else {
                            Modifier
                                .clip(pillShape)
                                .background(containerColor)
                        },
                    )
                    .height(GLASS_BAR_HEIGHT)
                    .fillMaxWidth()
                    .padding(horizontal = GLASS_BAR_PADDING_H, vertical = GLASS_BAR_PADDING_V),
                horizontalArrangement = Arrangement.spacedBy(GLASS_BAR_ITEM_GAP),
                verticalAlignment = Alignment.CenterVertically,
                content = realContent,
            )


            // 最上面一层：只画 tintExempt 的格子（屁岱）的真身，其余位置是不接触摸的空占位，
            // 触摸会落到下面那一排和滑块上。这样屁岱始终只有一份，而且永远在滑块上面。
            if (tabs.any { it.tintExempt }) {
                Row(
                    Modifier
                        .graphicsLayer { translationX = panelOffset }
                        .height(GLASS_BAR_HEIGHT)
                        .fillMaxWidth()
                        .padding(horizontal = GLASS_BAR_PADDING_H, vertical = GLASS_BAR_PADDING_V),
                    horizontalArrangement = Arrangement.spacedBy(GLASS_BAR_ITEM_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        if (tab.tintExempt) {
                            // 拖动由外层统一接管，这一格只管点击
                            GlassNavTabSlot(
                                onClick = tabOnClick(index),
                                selected = isHighlighted(index),
                                clickable = false,
                            ) {
                                tab.content(this, isHighlighted(index))
                            }
                        } else {
                            GlassNavTabSlot(onClick = null) {}
                        }
                    }
                }
            }
        }
    }
}
