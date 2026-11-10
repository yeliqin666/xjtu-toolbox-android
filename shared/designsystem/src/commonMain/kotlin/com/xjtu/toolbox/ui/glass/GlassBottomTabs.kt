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
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** 底栏玻璃按 1/2 分辨率模糊：8dp 的模糊已经比放大后的像素粗，填充量只剩四分之一。 */
private const val GLASS_BAR_DOWNSCALE = 2

/**
 * 底栏的一格。
 *
 * @param key 稳定标识，一般传 tab 的枚举值本身，方便调用方在 [onTabSelected] 里反查。
 * @param tintExempt true = 滑块经过时不染色，染色那一遍里只放同尺寸的空占位——给屁岱用
 *   （见计划 §9.3 第 3 条：屁岱只有一个实例，不能被复制出第二份）。
 * @param content 格子内容（一般是图标 + 文字），`selected` 是当前是否选中这一格。
 */
class GlassTab(
    val key: Any,
    val tintExempt: Boolean = false,
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

    val isDark = LocalIsDarkTheme.current
    val accentColor = MiuixTheme.colorScheme.primary
    val containerColor = MiuixTheme.colorScheme.surfaceContainerHigh.let {
        if (glass) it.copy(alpha = 0.4f) else it
    }
    val barShadow = remember(accentColor, isDark) { floatingGlassShadow(accentColor, isDark) }
    // 滑块静止时的底色
    val restTint = if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.1f)
    // 圆角按设计给的 28dp 写死，不额外依赖 io.github.kyant0:shapes 的 Capsule——那只是
    // backdrop 的传递依赖，不在本项目的编译期 classpath 上（加它要改
    // gradle/libs.versions.toml，是热点文件，见收尾报告的胶合清单）。RoundedCornerShape
    // 是 CornerBasedShape，lens() 支持（计划 §16.1 第 2 条）。
    // 28dp < 本体高的一半（58/2 = 29dp），所以不会被 Compose 夹：两端各留一小段直边，
    // 是圆角矩形而不是满半圆的胶囊。给到 ≥ 29dp 才会被夹成胶囊。
    val pillShape = remember { RoundedCornerShape(28.dp) }

    // 内容行高度 = 本体高 − 上下各 4dp 内衬。本体高取 GLASS_BAR_HEIGHT（MainScreen 的浮空占位
    // 也按它算），滑块、染色层、屁岱真身那几层都跟着它，以后调高度只改那一个常量。
    val innerHeight = GLASS_BAR_HEIGHT - 8.dp

    // 只有 glass = true 才需要这一层：给滑块用，把「强调色染色」那一遍的像素录下来，
    // 让滑块下面透出的是「玻璃 + 强调色」而不是纯色块。
    val tabsBackdrop = if (glass) rememberLayerBackdrop() else null

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val tabWidthPx = with(density) {
            (constraints.maxWidth.toFloat() - 8.dp.toPx()) / tabsCount
        }

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

        val dampedDrag = remember(animationScope, tabsCount) {
            GlassDampedDragAnimation(
                animationScope = animationScope,
                initialValue = currentIndex.coerceAtLeast(0).toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                // 按住时滑块宽度不变、上下略压扁，同时灰色加深一点、图标变淡
                pressedScale = 1f,
                pressedScaleY = 0.94f,
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

        // 静止（没按住、滑块停在整格、形变回位）时，染色层和滑块画出来与「选中格按选中态画 +
        // 一层淡色胶囊」逐像素相同，这时两层都跳过不画
        val glassActive by remember(dampedDrag) {
            derivedStateOf {
                dampedDrag.pressProgress > 0f ||
                    dampedDrag.scaleX != 1f || dampedDrag.scaleY != 1f ||
                    abs(dampedDrag.value - dampedDrag.value.roundToInt()) > 0.001f
            }
        }

        // 每格一个按压来源：真实那一遍与染色那一遍共用，谁接到触摸都一起变淡
        val pressSources = remember(tabsCount) { List(tabsCount) { MutableInteractionSource() } }
        val barDragging = remember { booleanArrayOf(false) }
        // 一按下，滑块就快速跟到那一格（松手才真正切页）；按下后滑走取消了就回到原来那格
        pressSources.forEachIndexed { index, source ->
            LaunchedEffect(source, dampedDrag) {
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> {
                            dampedDrag.press()
                            dampedDrag.animateToValue(index.toFloat())
                        }
                        is PressInteraction.Release -> dampedDrag.release()
                        // 转成整条拖动时也会收到取消：这时按压态和位置都归拖动管
                        is PressInteraction.Cancel -> if (!barDragging[0]) {
                            dampedDrag.release()
                            if (currentIndex >= 0) dampedDrag.animateToValue(currentIndex.toFloat())
                        }
                    }
                }
            }
        }

        // 整条底栏都能拖：在哪一格按下都行，横向滑过触摸阈值就接管，滑块按手指的绝对位置
        // 直接贴在手指下；松手吸到最近的一格并切页。没滑过阈值就是点击，交给各格自己处理。
        val padPx = with(density) { 4.dp.toPx() }
        val dragAnywhere = Modifier.pointerInput(tabsCount, tabWidthPx, isLtr) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                var dragging = false
                fun valueAt(x: Float): Float {
                    val local = (if (isLtr) x else size.width - x) - padPx
                    return (local / tabWidthPx - 0.5f).coerceIn(0f, (tabsCount - 1).toFloat())
                }
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (dragging) {
                            val target = GlassDragMath.snappedIndex(dampedDrag.value, tabsCount)
                            if (currentIndex != target) {
                                currentIndex = target
                                onTabSelectedUpdated(target)
                            }
                            dampedDrag.animateToValue(target.toFloat())
                            change.consume()
                        }
                        break
                    }
                    if (!dragging && abs(change.position.x - down.position.x) > slop) {
                        dragging = true
                        barDragging[0] = true
                        dampedDrag.press()
                    }
                    if (dragging) {
                        dampedDrag.snapToValue(valueAt(change.position.x))
                        change.consume()
                    }
                }
                if (dragging) dampedDrag.release()
                barDragging[0] = false
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
                    GlassNavTabSlot(onClick = tabOnClick(index), pressSource = pressSources[index]) {
                        tab.content(this, index == currentIndex)
                    }
                }
            }
        }

        // 第二遍：每格都按选中态画（强调色）、alpha = 0，只给滑块当采样源用。
        // tintExempt 的格子（屁岱）在这一遍只放一个同尺寸空占位，不重新创建内容。
        val tintedContent: @Composable RowScope.() -> Unit = {
            tabs.forEachIndexed { index, tab ->
                if (tab.tintExempt) {
                    GlassNavTabSlot(onClick = null) {}
                } else {
                    GlassNavTabSlot(onClick = tabOnClick(index), pressSource = pressSources[index]) {
                        tab.content(this, true)
                    }
                }
            }
        }

        // 所有层都包在这一个 Box 里：拖动手势挂在它们共同的祖先上（挂在最上面的兄弟层会吞掉各格的点击）
        Box(Modifier.fillMaxWidth().then(dragAnywhere), contentAlignment = Alignment.CenterStart) {
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
                                blur(8.dp.toPx() / GLASS_BAR_DOWNSCALE)
                            },
                            sampleDownscale = GLASS_BAR_DOWNSCALE,
                            exportedBackdrop = exportedBackdrop,
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
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = realContent,
        )

        if (glass && tabsBackdrop != null) {
            // 当前格被滑块盖着，按住滑块时看到的是这一遍：图标跟着变淡
            CompositionLocalProvider(LocalGlassNavTabFade provides { 1f - 0.5f * dampedDrag.pressProgress }) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .drawWithContent { if (glassActive) drawContent() }
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset }
                        .drawBackdrop(
                            // 有主胶囊导出的那一层（已模糊、已铺底色）就直接采它，省一次对页面的模糊；
                            // 滑块里透出的底也和旁边的胶囊完全一致
                            backdrop = exportedBackdrop ?: backdrop,
                            shape = { pillShape },
                            effects = {
                                if (exportedBackdrop == null) {
                                    padding = maxOf(padding, 16.dp.toPx())
                                    vibrancy()
                                    blur(8.dp.toPx())
                                }
                            },
                            highlight = null,
                            onDrawSurface = if (exportedBackdrop == null) {
                                { drawRect(containerColor) }
                            } else {
                                null
                            },
                        )
                        .height(innerHeight)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = tintedContent,
                )
            }
        }

        // 一格都不高亮（currentIndex = -1）时滑块不画：它没有该停的位置
        if (tabWidthPx > 0f && currentIndex in 0 until tabsCount) {
            if (glass && tabsBackdrop != null) {
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            translationX =
                                if (isLtr) dampedDrag.value * tabWidthPx + panelOffset
                                else size.width - (dampedDrag.value + 1f) * tabWidthPx + panelOffset
                        }
                        .drawWithContent {
                            if (glassActive) drawContent()
                            else drawOutline(pillShape.createOutline(size, layoutDirection, this), restTint)
                        }
                        .drawBackdrop(
                            backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                            shape = { pillShape },
                            effects = {},
                            highlight = null,
                            layerBlock = {
                                scaleX = dampedDrag.scaleX
                                scaleY = dampedDrag.scaleY
                            },
                            onDrawSurface = {
                                drawRect(restTint.copy(alpha = restTint.alpha * (1f + 0.6f * dampedDrag.pressProgress)))
                            },
                        )
                        .height(innerHeight)
                        .fillMaxWidth(1f / tabsCount),
                )
            } else {
                // glass = false：退回一个不透明的强调色胶囊，靠 alpha 15% 的填色区分选中项，
                // 不接 drawBackdrop / 高光着色器（性能模式）。拖动、点击的逻辑和玻璃模式共用。
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            translationX =
                                if (isLtr) dampedDrag.value * tabWidthPx + panelOffset
                                else size.width - (dampedDrag.value + 1f) * tabWidthPx + panelOffset
                            scaleY = dampedDrag.scaleY
                        }
                        .clip(pillShape)
                        .background(accentColor.copy(alpha = 0.15f))
                        .height(innerHeight)
                        .fillMaxWidth(1f / tabsCount),
                )
            }
        }

        // 最上面一层：只画 tintExempt 的格子（屁岱）的真身，其余位置是不接触摸的空占位，
        // 触摸会落到下面那一排和滑块上。这样屁岱始终只有一份，而且永远在滑块上面。
        if (tabs.any { it.tintExempt }) {
            Row(
                Modifier
                    .graphicsLayer { translationX = panelOffset }
                    .height(GLASS_BAR_HEIGHT)
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    if (tab.tintExempt) {
                        // 拖动由外层统一接管，这一格只管点击
                        GlassNavTabSlot(onClick = tabOnClick(index)) {
                            tab.content(this, index == currentIndex)
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
