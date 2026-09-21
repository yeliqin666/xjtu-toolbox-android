// 参考 Kyant0/AndroidLiquidGlass 示例 App 的 LiquidBottomTabs.kt 改写
// （分支 kmp，app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt，288 行）
// 许可证 Apache License 2.0。改动（对照计划 §9.3）：
//   1. 强调色从写死的 iOS 蓝改成 MiuixTheme.colorScheme.primary；容器色改成跟随本项目主题的
//      surfaceContainerHigh，深浅色模式跟随本项目的 LocalIsDarkTheme（不是 isSystemInDarkTheme）。
//   2. 每一格是否在「染色那一遍」里被染色由调用方按格子指定（[GlassTab.tintExempt]），
//      不再是整行统一染色；被排除的格子在染色那一遍里只画一个同尺寸空占位，不重新创建一份
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
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
import kotlin.math.sign

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
    // 用百分比圆角凑出胶囊形状，不额外依赖 io.github.kyant0:shapes 的 Capsule——
    // 那只是 backdrop 的传递依赖，不在本项目的编译期 classpath 上（加它要改
    // gradle/libs.versions.toml，是热点文件，见收尾报告的胶合清单）。RoundedCornerShape
    // 是 CornerBasedShape，lens() 支持（计划 §16.1 第 2 条），和 MainScreen.kt 里经典
    // FloatingNavigationBar 用的 `RoundedCornerShape(50)` 一致，视觉上也是胶囊。
    val pillShape = remember { RoundedCornerShape(50) }

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
        var currentIndex by remember { mutableIntStateOf(selectedIndex.coerceIn(0, tabsCount - 1)) }
        val onTabSelectedUpdated by rememberUpdatedState(onTabSelected)

        val dampedDrag = remember(animationScope, tabsCount) {
            GlassDampedDragAnimation(
                animationScope = animationScope,
                initialValue = currentIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = GlassDragMath.snappedIndex(targetValue, tabsCount)
                    if (currentIndex != targetIndex) {
                        currentIndex = targetIndex
                        onTabSelectedUpdated(targetIndex)
                    }
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    val deltaX = if (isLtr) dragAmount.x else -dragAmount.x
                    updateValue(GlassDragMath.dragTargetValue(targetValue, deltaX, tabWidthPx, tabsCount))
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
            )
        }

        // 选中项被外部改动（比如程序切 tab）时，把滑块同步过去，但不重复回调 onTabSelected。
        LaunchedEffect(selectedIndex, tabsCount) {
            val target = selectedIndex.coerceIn(0, tabsCount - 1)
            if (currentIndex != target) {
                currentIndex = target
                dampedDrag.animateToValue(target.toFloat())
            }
        }

        val interactiveHighlight = remember(animationScope, isLtr, dampedDrag) {
            GlassInteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        x = if (isLtr) {
                            (dampedDrag.value + 0.5f) * tabWidthPx + panelOffset
                        } else {
                            size.width - (dampedDrag.value + 0.5f) * tabWidthPx + panelOffset
                        },
                        y = size.height / 2f,
                    )
                },
            )
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
                    GlassNavTabSlot(onClick = tabOnClick(index)) {
                        tab.content(this, index == currentIndex)
                    }
                }
            }
        }

        // 第二遍：整体强调色染色、alpha = 0，只给滑块当采样源用。
        // tintExempt 的格子（屁岱）在这一遍只放一个同尺寸空占位，不重新创建内容。
        val tintedContent: @Composable RowScope.() -> Unit = {
            tabs.forEachIndexed { index, tab ->
                if (tab.tintExempt) {
                    GlassNavTabSlot(onClick = null) {}
                } else {
                    GlassNavTabSlot(onClick = tabOnClick(index)) {
                        tab.content(this, index == currentIndex)
                    }
                }
            }
        }

        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .then(
                    if (glass) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                // 采样范围往外扩一圈，胶囊边缘处也有真实内容可混（见 glassBarSurface）
                                padding = maxOf(padding, 16.dp.toPx())
                                vibrancy()
                                blur(8.dp.toPx())
                                lens(24.dp.toPx(), 24.dp.toPx())
                            },
                            layerBlock = {
                                val progress = dampedDrag.pressProgress
                                val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, progress)
                                scaleX = scale
                                scaleY = scale
                            },
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
                .then(if (glass) interactiveHighlight.modifier else Modifier)
                .height(64.dp)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = realContent,
        )

        if (glass && tabsBackdrop != null) {
            CompositionLocalProvider(LocalGlassNavTabScale provides { lerp(1f, 1.2f, dampedDrag.pressProgress) }) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                val progress = dampedDrag.pressProgress
                                // 和上面的主胶囊同一套采样范围，指示器透出来的底才对得上
                                padding = maxOf(padding, 16.dp.toPx())
                                vibrancy()
                                blur(8.dp.toPx())
                                lens(24.dp.toPx() * progress, 24.dp.toPx() * progress)
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = dampedDrag.pressProgress)
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                        .then(interactiveHighlight.modifier)
                        .height(56.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                    verticalAlignment = Alignment.CenterVertically,
                    content = tintedContent,
                )
            }
        }

        if (tabWidthPx > 0f) {
            if (glass && tabsBackdrop != null) {
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            translationX =
                                if (isLtr) dampedDrag.value * tabWidthPx + panelOffset
                                else size.width - (dampedDrag.value + 1f) * tabWidthPx + panelOffset
                        }
                        .then(interactiveHighlight.gestureModifier)
                        .then(dampedDrag.modifier)
                        .drawBackdrop(
                            backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                            shape = { pillShape },
                            effects = {
                                val progress = dampedDrag.pressProgress
                                lens(10.dp.toPx() * progress, 14.dp.toPx() * progress, chromaticAberration = true)
                            },
                            highlight = { Highlight.Default.copy(alpha = dampedDrag.pressProgress) },
                            layerBlock = {
                                scaleX = dampedDrag.scaleX
                                scaleY = dampedDrag.scaleY
                                val v = dampedDrag.velocity / 10f
                                scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = dampedDrag.pressProgress
                                drawRect(
                                    color = if (!isDark) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f),
                                    alpha = 1f - progress,
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            },
                        )
                        .height(56.dp)
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
                        }
                        .then(dampedDrag.modifier)
                        .clip(pillShape)
                        .background(accentColor.copy(alpha = 0.15f))
                        .height(56.dp)
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
                    .height(64.dp)
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    if (tab.tintExempt) {
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
