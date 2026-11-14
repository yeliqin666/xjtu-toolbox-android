package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.LoadedFontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 完整紧凑顶栏；内部处理安全区和槽位布局，点击行为由宿主提供。 */
@Composable
fun TopAppBar(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    dark: Boolean = CouiTheme.isDark,
    colors: CouiTopAppBarColors = CouiTopAppBarDefaults.colors(dark = dark),
    windowInsets: WindowInsets = CouiTopAppBarDefaults.windowInsets,
    backdrop: CouiBackdrop = checkNotNull(LocalCouiBackdrop.current) {
        "顶栏需要显式 backdrop 或 ProvideCouiBackdrop 作用域"
    },
    /** 见 [LargeTopAppBar] 同名参数。 */
    bottomContent: (@Composable () -> Unit)? = null,
) {
    CouiTopAppBarSurface(modifier.fillMaxWidth(), backdrop) {
        CompositionLocalProvider(LocalCouiDarkTheme provides dark) {
            Column {
                AppBarLayout(title, navigationIcon, actions, colors, windowInsets,
                    CouiTopAppBarDefaults.height, CouiTopAppBarDefaults.height, null)
                bottomContent?.invoke()
            }
        }
    }
}

/**
 * 大标题缩为固定小标题；省略 scrollBehavior 时保持展开。
 * 连接挂到正文祖先，正文通过 scrollBehavior.state.contentPadding 补偿逻辑滚动与可见弹簧的差值。
 * 高度不含 windowInsets；expandedHeight 必须大于正数 collapsedHeight。
 * expandedTitleStyle 配置展开标题，collapsedTitleStyle 在完全收起时覆盖展开样式。
 * 收起默认按起始侧导航槽是否占用空间选择字号。
 * 显式字号必须是有限正数 sp；title 槽位须继承组件提供的样式。
 */
@Composable
fun LargeTopAppBar(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: CouiTopAppBarScrollBehavior? = null,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiTopAppBarColors = CouiTopAppBarDefaults.colors(dark = dark),
    windowInsets: WindowInsets = CouiTopAppBarDefaults.windowInsets,
    expandedHeight: Dp = CouiTopAppBarDefaults.expandedHeight,
    collapsedHeight: Dp = CouiTopAppBarDefaults.collapsedHeight,
    backdrop: CouiBackdrop = checkNotNull(LocalCouiBackdrop.current) {
        "顶栏需要显式 backdrop 或 ProvideCouiBackdrop 作用域"
    },
    expandedTitleStyle: TextStyle = TextStyle.Default,
    collapsedTitleStyle: TextStyle = TextStyle.Default,
    /** 集成侧扩展：标题行下面的一排部件（分段控件、分类胶囊），放在同一块模糊表面里、采它的模糊层。 */
    bottomContent: (@Composable () -> Unit)? = null,
) {
    require(expandedHeight.value.isFinite() && collapsedHeight.value.isFinite() &&
        collapsedHeight > 0.dp && expandedHeight > collapsedHeight) {
        "展开高度必须大于收起高度，且高度必须是有限正数"
    }
    val expandedSize = expandedTitleStyle.fontSize
    val collapsedSize = collapsedTitleStyle.fontSize
    require(expandedSize == TextUnit.Unspecified ||
        expandedSize.isSp && expandedSize.value.isFinite() && expandedSize.value > 0f) {
        "展开字号必须为 Unspecified 或有限正数 sp"
    }
    require(collapsedSize == TextUnit.Unspecified ||
        collapsedSize.isSp && collapsedSize.value.isFinite() && collapsedSize.value > 0f) {
        "收起字号必须为 Unspecified 或有限正数 sp"
    }
    val density = LocalDensity.current
    val state = scrollBehavior?.state
    SideEffect {
        state?.updateRange(density, hasSearchBar = false, expandedHeight = expandedHeight, collapsedHeight = collapsedHeight)
        state?.updateSearch(expanded = false, active = false)
    }
    CouiTopAppBarSurface(modifier.fillMaxWidth(), backdrop,
        referenceBlurRadius = with(density) { CouiCollapsingAppBarDefaults.referenceBlurRadiusPx.toDp() },
        bottomExtension = CouiCollapsingAppBarDefaults.titleOnlyBlurExtension,
        gradientSpeed = 3f,
        topExtension = {
            with(density) {
                val range = (expandedHeight.roundToPx() - collapsedHeight.roundToPx()).coerceAtLeast(0)
                (state?.offsetPx ?: 0f).coerceIn(0f, range.toFloat()).toDp()
            }
        }) {
        CompositionLocalProvider(LocalCouiDarkTheme provides dark) {
            Column {
                AppBarLayout(title, navigationIcon, actions, colors, windowInsets, expandedHeight, collapsedHeight, state,
                    expandedTitleStyle, collapsedTitleStyle)
                bottomContent?.invoke()
            }
        }
    }
}

@Composable
private fun AppBarLayout(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    colors: CouiTopAppBarColors,
    windowInsets: WindowInsets,
    expandedHeight: Dp,
    collapsedHeight: Dp,
    state: CouiCollapsingAppBarState?,
    expandedTitleStyle: TextStyle = TextStyle.Default,
    collapsedTitleStyle: TextStyle = TextStyle.Default,
) {
    val large = expandedHeight > collapsedHeight
    val density = LocalDensity.current
    val compactTitleStyle = CouiTopAppBarDefaults.titleTextStyle()
    val defaultTitleStyle = if (large) CouiTopAppBarDefaults.expandedTitleTextStyle() else compactTitleStyle
    val expandedStyle = defaultTitleStyle.copy(color = colors.titleColor)
        .withTitleOverrides(expandedTitleStyle, density, defaultTitleStyle.fontFamily)
    val collapsedSize = collapsedTitleStyle.fontSize
    val collapsedOverrides = if (collapsedSize == TextUnit.Unspecified) collapsedTitleStyle
        else collapsedTitleStyle.copy(fontSize = TextUnit.Unspecified)
    val useCollapsedStyle = collapsedOverrides != TextStyle.Default &&
        remember(state) { derivedStateOf { state?.collapsedFraction == 1f } }.value
    val titleStyle = if (useCollapsedStyle) {
        expandedStyle.withTitleOverrides(collapsedTitleStyle, density, defaultTitleStyle.fontFamily)
    } else expandedStyle
    Layout(modifier = Modifier.windowInsetsPadding(windowInsets), content = {
        Box {
            CompositionLocalProvider(LocalCouiToolbarContentColor provides colors.navigationIconColor) {
                ProvideCouiContent(color = colors.navigationIconColor, content = navigationIcon)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalCouiToolbarContentColor provides colors.actionIconColor) {
                ProvideCouiContent(color = colors.actionIconColor) { actions() }
            }
        }
        Box(Modifier.semantics { heading() }) {
            ProvideCouiContent(color = titleStyle.color.takeOrElse { colors.titleColor }, textStyle = titleStyle,
                mergeTextStyle = false, content = title)
        }
    }) { nodes, constraints ->
        require(constraints.hasBoundedWidth) { "顶栏需要有界宽度" }
        val width = constraints.maxWidth
        val expanded = expandedHeight.roundToPx()
        val collapsed = collapsedHeight.roundToPx()
        val range = (expanded - collapsed).coerceAtLeast(0)
        val offset = (state?.offsetPx ?: 0f).coerceIn(0f, range.toFloat())
        val height = constraints.constrainHeight((expanded - offset).roundToInt())
        val toolbarHeight = if (large) minOf(collapsed, height) else height
        val padding = CouiTopAppBarDefaults.horizontalPadding.roundToPx().coerceAtMost(width / 2)
        val gap = CouiTopAppBarDefaults.titleGap.roundToPx()
        val slotWidth = (width - 2 * padding).coerceAtLeast(0)
        val navigation = nodes[0].measure(Constraints(maxWidth = slotWidth, maxHeight = toolbarHeight))
        val action = nodes[1].measure(Constraints(maxWidth = slotWidth - navigation.width, maxHeight = toolbarHeight))
        val hasNavigationIcon = navigation.width > 0
        val progress = if (large)
            CouiCollapsingAppBarDefaults.titleEasing.transform(state?.collapsedFraction ?: 0f) else 0f
        val collapsedScale = if (large) {
            val targetSize = if (collapsedSize != TextUnit.Unspecified) collapsedSize
                else if (hasNavigationIcon) compactTitleStyle.fontSize else CouiTopAppBarDefaults.titleOnlyCollapsedSize
            with(density) { targetSize.toPx() / titleStyle.fontSize.toPx() }
        } else 1f
        val scale = if (!large) 1f else if (useCollapsedStyle) collapsedScale else 1f + (collapsedScale - 1f) * progress
        val compactStart = if (large && !hasNavigationIcon) CouiCollapsingAppBarDefaults.titleOnlyStart.toPx()
            else (padding + navigation.width + if (hasNavigationIcon) gap else 0).toFloat()
        val compactEnd = padding + action.width + if (action.width > 0) gap else 0
        val expandedStart = CouiTopAppBarDefaults.expandedTitleStart.toPx()
        val curvedTitle = large && hasNavigationIcon
        // 弧线偏移在两端的位置与变化率均为零，保留标题原有的起步和收尾节奏。
        val arcEnvelope = progress * (1f - progress)
        val arcOffset = if (curvedTitle) 0.8f * arcEnvelope * arcEnvelope else 0f
        val horizontalProgress = progress + arcOffset
        val start = if (large)
            expandedStart + (compactStart - expandedStart) * horizontalProgress else compactStart
        val end = if (large && action.width == 0) CouiCollapsingAppBarDefaults.titleOnlyStart.toPx()
            else compactEnd.toFloat()
        // 集成侧改动：标题按展开、收起两端都放得下的固定宽度量（收起端按缩放折算），整个折叠过程不变。
        // 原版逐帧按「当前可用宽 ÷ 当前缩放」量，长标题的自动缩字号会在相邻档位间来回跳，看着像抽搐着避让按钮。
        val expandedRoom = width - (if (large) expandedStart else compactStart) - end
        val collapsedRoom = (width - compactStart - end) / collapsedScale
        val titleWidth = minOf(expandedRoom, collapsedRoom).coerceAtLeast(0f).roundToInt()
        val titleNode = nodes[2].measure(Constraints(maxWidth = titleWidth))
        val titleOrigin = TransformOrigin(if (layoutDirection == LayoutDirection.Rtl) 1f else 0f, 0f)
        layout(width, height) {
            val navigationY = (toolbarHeight - navigation.height) / 2
            navigation.placeRelative(padding, navigationY, zIndex = 1f)
            action.placeRelative(width - padding - action.width, (toolbarHeight - action.height) / 2, zIndex = 1f)
            val titleY = if (large) {
                // 默认高度下两种算法一致；栏加高（集成侧给带副标题的标题让出一行）时标题顶仍停在原位，
                // 多出来的高度留给标题下面的副标题，而不是把整块标题往下推出栏外
                val expandedY = minOf(
                    expanded - (CouiTopAppBarDefaults.expandedHeight - CouiCollapsingAppBarDefaults.titleOnlyTop).toPx(),
                    CouiCollapsingAppBarDefaults.titleOnlyTop.toPx(),
                )
                val collapsedY = (collapsed - titleNode.height * scale) / 2f
                if (curvedTitle) {
                    val verticalProgress = progress - arcOffset
                    expandedY + (collapsedY - expandedY) * verticalProgress
                } else {
                    expandedY + (range + collapsedY - expandedY) * progress - offset
                }
            } else (height - titleNode.height) / 2f
            titleNode.placeRelativeWithLayer(start.roundToInt(),
                (if (curvedTitle) titleY.coerceAtLeast(0f) else
                    titleY.coerceIn(0f, (height - titleNode.height * scale).coerceAtLeast(0f))).roundToInt()) {
                scaleX = scale
                scaleY = scale
                transformOrigin = titleOrigin
                clip = true
            }
        }
    }
}

private fun TextStyle.withTitleOverrides(
    overrides: TextStyle,
    density: Density,
    defaultFamily: FontFamily?,
): TextStyle {
    val size = overrides.fontSize
    val resized = if (size == TextUnit.Unspecified || size == fontSize) this else {
        val resizedLineHeight = with(density) { (lineHeight.toPx() * size.toPx() / fontSize.toPx()).toSp() }
        copy(fontSize = size, lineHeight = resizedLineHeight)
    }
    if (overrides == TextStyle.Default) return resized
    // 已加载的 Typeface 固定了字重和斜体。单独改这两项时，交给 Compose 重新解析字体。
    val family = if (overrides.fontFamily == null && fontFamily is LoadedFontFamily &&
        (overrides.fontWeight != null || overrides.fontStyle != null)) FontFamily.SansSerif else overrides.fontFamily
    // Compose 解析新字体时会应用系统字重补偿，传入未补偿的默认字重。
    val weight = overrides.fontWeight ?: if (family != null && fontFamily == defaultFamily) FontWeight.Bold else null
    return resized.merge(overrides.copy(fontSize = TextUnit.Unspecified, fontFamily = family, fontWeight = weight))
}
