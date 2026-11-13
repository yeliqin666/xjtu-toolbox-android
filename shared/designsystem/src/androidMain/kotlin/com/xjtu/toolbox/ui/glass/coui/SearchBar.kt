package com.xjtu.toolbox.ui.glass.coui

import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.xjtu.toolbox.ui.glass.coui.CouiIcon as Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import kotlin.math.roundToInt

/**
 * 设置式常驻搜索与分裂取消胶囊；在所属页面的浮层使用，backdrop 不包含当前搜索。
 * 宿主持有查询、展开和结果；取消只请求关闭，清空只提交空查询，不持有导航。
 * enabled 为 false 时停止输入、点击和交互反馈，释放自身焦点；查询和展开目标仍由宿主持有。
 */
@Composable
fun SearchBar(
    modifier: Modifier = Modifier,
    state: CouiSearchBarState,
    query: String,
    onQueryChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    placeholder: String,
    cancelText: String,
    clearDescription: String,
    dark: Boolean = CouiTheme.isDark,
    backdrop: CouiBackdrop = checkNotNull(LocalCouiToolbarBackdrop.current ?: LocalCouiBackdrop.current) {
        "搜索需要显式 backdrop 或独立的顶栏作用域"
    },
    onSearch: () -> Unit = {},
    shadowProgress: Float = 0f,
    colors: CouiSearchBarColors = CouiSearchBarDefaults.colors(dark),
    enabled: Boolean = true,
    /**
     * 宿主接缝：是否带右侧分裂出来的「取消」胶囊。
     *
     * COUI 原版始终为 true（搜索栏在顶栏里收起/展开）。学辅页把搜索框直接放在正文里、
     * 要求一直是展开的输入态，没有「退出搜索」这个动作，传 false 让输入胶囊铺满整行；
     * 清空仍走输入框内部的圆点叉。默认值保持原版行为。
     */
    showCancel: Boolean = true,
    /**
     * 宿主接缝：展开时是否主动要焦点并弹键盘。
     *
     * 原版由点击从收起态展开，所以要焦点是对的。学辅页的常驻搜索一开始就是展开态，
     * 进页面不该自动弹键盘；传 false 后点输入框仍由 BasicTextField 正常取焦。
     */
    requestFocusOnExpand: Boolean = true,
) {
    val density = LocalDensity.current
    // 设置的搜索位于 AppBarBlurOverlay 之后；采样完成渐进处理的背景，不包含标题/搜索。
    val surfaceBackdrop = LocalCouiToolbarBackdrop.current ?: backdrop
    val layoutDirection = LocalLayoutDirection.current
    val rtl = layoutDirection == LayoutDirection.Rtl
    val textStyle = rememberCouiTextStyle(CouiTextKind.Search)
    val configuration = LocalConfiguration.current
    val cancelStyle = remember(configuration, density) {
        val fontScale = configuration.fontScale
        val pixels = with(density) { 14.sp.toPx().roundToInt() }
        val base = (pixels / fontScale).roundToInt()
        val size = base * if (fontScale < 1.15f) 1f else 1.15f
        val weight = 500
        val typeface = Typeface.create(Typeface.create("sans-serif-medium", Typeface.NORMAL), weight, false)
        val lineSpacing = if (Build.VERSION.SDK_INT >= 35) 1.2245f else 1.1f
        couiTextStyle(typeface, weight, size, lineSpacing, includeFontPadding = true, density = density)
    }
    val textMeasurer = rememberTextMeasurer()
    val cancelWidth = remember(cancelText, cancelStyle, density, layoutDirection, textMeasurer) { with(density) {
        (textMeasurer.measure(cancelText, cancelStyle, maxLines = 1).size.width +
            2 * CouiSearchBarDefaults.cancelPadding.roundToPx()).coerceAtMost(CouiSearchBarDefaults.cancelMaxWidth.roundToPx())
    } }
    // 不带取消胶囊时，取消占位为 0，输入胶囊直接铺满；取消面仍随浮层链保留（可见性由 showCancel 关掉）。
    val cancelSlot = if (showCancel) cancelWidth else 0
    val surfaceHeight = with(density) { CouiSearchBarDefaults.surfaceHeight.roundToPx() }
    val gap = with(density) { CouiSearchBarDefaults.cancelGap.roundToPx() }
    var boundsSize by remember { mutableStateOf(IntSize.Zero) }
    val shape = CouiSearchBarDefaults.shape
    val bodyLayer = rememberGraphicsLayer()
    val cancelLayer = rememberGraphicsLayer()
    val renderer = if (Build.VERSION.SDK_INT >= 34) remember { CouiFloatingRenderer.pair() } else null
    val bodySource = remember { MutableInteractionSource() }
    val cancelSource = remember { MutableInteractionSource() }
    val press = rememberCouiPressMotion()
    val touch = rememberCouiTouchMotion(enabled && state.expanded && !state.running)
    // 输入胶囊的跟手位移与宽度动画可同时运行。
    val bodyTouch = rememberCouiTouchMotion(enabled)
    val bodyPress = rememberCouiPressMotion()
    val (bodyLightInput, bodyLight) = rememberCouiSpotlight(enabled, SpotlightPreset.OpaqueMedium2, dark)
    val (cancelLightInput, cancelLight) = rememberCouiSpotlight(enabled && state.expanded, SpotlightPreset.OpaqueMedium2, dark)
    val foreground = colors.foreground
    val secondary = colors.secondary
    val background = colors.fallbackBackground
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var ownsFocus by remember { mutableStateOf(false) }
    fun releaseInput() {
        if (ownsFocus) {
            keyboard?.hide()
            focus.clearFocus()
        }
    }
    val cancelSearch = { if (enabled && state.expanded) { releaseInput(); onExpandedChange(false) } }
    LaunchedEffect(state.expanded, enabled) {
        if (enabled && state.expanded) {
            if (requestFocusOnExpand) { if (requester.requestFocus()) keyboard?.show() }
        } else {
            releaseInput()
        }
    }
    fun bodySurface(): CouiFloatingSurface {
        val width = (boundsSize.width - (cancelSlot + gap) * state.widthProgress).roundToInt().coerceAtLeast(1).toFloat()
        val y = ((boundsSize.height - surfaceHeight) / 2).toFloat()
        val bodyX = if (rtl) boundsSize.width - width else 0f
        return CouiFloatingSurface(Rect(bodyX + bodyTouch.translationX.value, y + bodyTouch.translationY.value,
            bodyX + bodyTouch.translationX.value + width, y + bodyTouch.translationY.value + surfaceHeight),
            scaleX = bodyTouch.scaleX.value + bodyPress.scale.value - 1f,
            scaleY = bodyTouch.scaleY.value + bodyPress.scale.value - 1f, cornerWeight = 1f)
    }
    fun cancelSurface(): CouiFloatingSurface {
        val f = state.cancelProgress
        val y = ((boundsSize.height - surfaceHeight) / 2).toFloat()
        val cancelX = if (rtl) 0f else boundsSize.width - cancelSlot.toFloat()
        val translation = CouiSearchBarDefaults.cancelTranslationPx * (1f - f) * if (rtl) -1f else 1f
        val scale = (.5f + .5f * f).coerceAtLeast(.001f)
        return CouiFloatingSurface(Rect(cancelX + translation + touch.translationX.value,
            y + touch.translationY.value, cancelX + translation + touch.translationX.value + cancelSlot,
            y + touch.translationY.value + surfaceHeight),
            scaleX = scale * (touch.scaleX.value + press.scale.value - 1f),
            scaleY = scale * (touch.scaleY.value + press.scale.value - 1f), cornerWeight = 1f,
            alpha = (f / .2f).coerceIn(0f, 1f), visible = showCancel && state.active)
    }
    val contour = LocalCouiContourGlowEnabled.current
    val themeTint = CouiTheme.materialTintFor(dark)
    var normalShadow by remember { mutableFloatStateOf(shadowProgress) }
    if (!state.active) SideEffect { normalShadow = shadowProgress }
    // 首页在搜索弹簧期间暂停滚动投影回调；不能用搜索主动折叠产生的 offset 提前点亮投影。
    val retainedShadow = if (state.active) normalShadow else shadowProgress
    val keepScrollShadow = if (state.active) retainedShadow >= 1f || (state.expanded && retainedShadow > .5f)
        else retainedShadow > .5f
    val drop = if (keepScrollShadow) retainedShadow.coerceIn(0f, 1f)
        else ((state.widthProgress - .8f) / .2f).coerceIn(0f, 1f)
    val baseShadow = CouiTopAppBarButtonDefaults.shadow(dark, true, density.density, contour, search = true, alphaScale = drop)
    val shadow = baseShadow.copy(causticColor = baseShadow.causticColor.copy(alpha = baseShadow.causticColor.alpha * drop))
    val surfaceModifier = if (Build.VERSION.SDK_INT >= 34 && renderer != null) Modifier.couiFloatingPair(
        surfaceBackdrop, renderer, listOf(CouiTopAppBarButtonDefaults.systemMaterial(dark, true).copy(index = 1,
            blurRadius = CouiSearchBarDefaults.blurRadius, extension = with(density) { 4.dp.roundToPx() }, themeTint = themeTint),
            CouiTopAppBarButtonDefaults.systemMaterial(dark, true).copy(index = 2,
                blurRadius = CouiSearchBarDefaults.blurRadius, extension = with(density) { 4.dp.roundToPx() }, themeTint = themeTint)),
        listOf(shadow, shadow), listOf(shape, shape), listOf(bodyLayer, cancelLayer), contour,
        // 原厂 bringChildToFront(functional) 后再提升 searchBarBackgroundView。
        // 调整整层材质与内容的顺序，不能只把透明文字层放到上面。
        drawOrder = listOf(1, 0),
        // 双胶囊只叠前景，不以整块 Src 回填擦掉下方渐进背景或兄弟投影。
        foregroundComposition = true,
        beforeContent = { index, path ->
            val node = if (index == 0) bodySurface() else cancelSurface()
            val draw = if (index == 0) bodyLight else cancelLight
            if (draw != null && node.visible) inset(node.bounds.left, node.bounds.top,
                size.width - node.bounds.right, size.height - node.bounds.bottom) {
                withTransform({ scale(node.scaleX, node.scaleY, center) }) { clipPath(path) { draw() } }
            }
        }, surfaces = { listOf(bodySurface(), cancelSurface()) })
        else Modifier
    Layout(modifier = modifier.fillMaxWidth().height(CouiSearchBarDefaults.height)
        .padding(horizontal = CouiSearchBarDefaults.horizontalMargin).onSizeChanged { boundsSize = it }
        .then(CouiSearchRelocationBoundary)
        .then(surfaceModifier), content = {
        val recordBody = if (renderer != null) Modifier.drawWithContent { bodyLayer.record { this@drawWithContent.drawContent() } }
            else Modifier.background(background, shape).clip(shape).drawWithContent { bodyLight?.invoke(this); drawContent() }
        Box(Modifier.graphicsLayer {
            val node = bodySurface()
            translationX = bodyTouch.translationX.value
            translationY = bodyTouch.translationY.value
            scaleX = node.scaleX
            scaleY = node.scaleY
        }.couiTouchMotion(bodyTouch, enabled)
            .couiPressMotion(bodyPress, enabled && !state.expanded && !state.running)
            .then(bodyLightInput).then(recordBody).clip(shape).couiToolbarFeedback(dark, bodySource, enabled && !state.expanded)
            .then(if (!state.expanded) Modifier.semantics { contentDescription = placeholder }
                .clickable(bodySource, null, enabled = enabled, role = Role.Button) { onExpandedChange(true) } else Modifier)) {
            // 装饰也属于输入本体，完整胶囊的触摸与语义不能穿过 padding 命中下层取消。
            BasicTextField(query, { if (enabled && state.expanded) onQueryChange(it) }, Modifier.fillMaxSize().focusRequester(requester)
                .onFocusChanged { ownsFocus = it.hasFocus }
                .then(if (!state.expanded) Modifier.clearAndSetSemantics {} else Modifier),
                enabled = enabled && state.expanded, readOnly = !enabled || !state.expanded, singleLine = true,
                textStyle = textStyle.copy(color = foreground), cursorBrush = SolidColor(foreground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (enabled && state.expanded) { keyboard?.hide(); onSearch() } }),
                decorationBox = { field ->
                    Row(Modifier.fillMaxSize().padding(start = CouiSearchBarDefaults.iconStart,
                        end = CouiSearchBarDefaults.textEnd), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(CouiSearchBarDefaults.innerIconSlot), contentAlignment = Alignment.Center) {
                            Icon(modifier = Modifier.size(CouiSearchBarDefaults.iconSize), imageVector = CouiSearchIcon.imageVector,
                                contentDescription = null, tint = secondary)
                        }
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) BasicText(placeholder, style = textStyle.copy(color = secondary), maxLines = 1)
                            field()
                        }
                        if (state.expanded && query.isNotEmpty()) {
                            Canvas(Modifier.size(CouiSearchBarDefaults.innerIconSlot).semantics { contentDescription = clearDescription }
                                .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, role = Role.Button) { onQueryChange("") }) {
                                val unit = this.size.width / 36f
                                drawCircle(colors.clear, radius = 9 * unit)
                                val p = Path().apply {
                                    moveTo(18f, 16.868f); lineTo(15.031f, 13.898f); lineTo(13.899f, 15.03f)
                                    lineTo(16.869f, 18f); lineTo(13.898f, 20.97f); lineTo(15.03f, 22.101f)
                                    lineTo(18f, 19.131f); lineTo(20.97f, 22.101f); lineTo(22.102f, 20.969f)
                                    lineTo(19.132f, 18f); lineTo(22.101f, 15.03f); lineTo(20.969f, 13.899f); this.close()
                                }
                                withTransform({ scale(unit, unit, Offset.Zero) }) { drawPath(p, Color.White) }
                            }
                        }
                    }
                })
        }
        val recordCancel = if (renderer != null) Modifier.drawWithContent { cancelLayer.record { this@drawWithContent.drawContent() } }
            else Modifier.background(background, shape)
                .clip(shape).drawWithContent { cancelLight?.invoke(this); drawContent() }
        Box(Modifier.width(with(density) { cancelWidth.toDp() }).height(CouiSearchBarDefaults.surfaceHeight)
            .graphicsLayer {
                val node = cancelSurface()
                translationX = node.bounds.left - (if (rtl) 0f else boundsSize.width - cancelWidth.toFloat())
                translationY = touch.translationY.value
                scaleX = node.scaleX
                scaleY = node.scaleY
                alpha = if (renderer != null) node.alpha else state.cancelProgress.coerceIn(0f, 1f)
            }
            .couiTouchMotion(touch, enabled && state.expanded && !state.running).couiPressMotion(press, enabled && state.expanded)
            .then(cancelLightInput).then(recordCancel).clip(shape).couiToolbarFeedback(dark, cancelSource, enabled && state.expanded)
            .couiMovingActionClick(cancelSource, enabled && state.expanded, state.running, cancelSearch),
            contentAlignment = Alignment.Center) {
            val textAlpha = (foreground.alpha * 255f * state.cancelProgress.coerceIn(0f, 1f)).roundToInt() / 255f
            val blur = (1f - state.cancelProgress).coerceAtLeast(0f) * CouiSearchBarDefaults.cancelContentBlurRadius
            BasicText(cancelText, Modifier.graphicsLayer {
                renderEffect = if (blur > 0f) android.graphics.RenderEffect.createBlurEffect(blur, blur,
                    android.graphics.Shader.TileMode.DECAL).asComposeRenderEffect() else null
            }, style = cancelStyle.copy(color = foreground.copy(alpha = textAlpha)), maxLines = 1)
        }
    }) { children, constraints ->
        val width = (constraints.maxWidth - (cancelSlot + gap) * state.widthProgress).roundToInt().coerceAtLeast(1)
        val body = children[0].measure(Constraints.fixed(width, surfaceHeight))
        val button = children[1].measure(Constraints.fixed(cancelSlot, surfaceHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val y = (constraints.maxHeight - surfaceHeight) / 2
            if (showCancel && state.active && state.cancelProgress > 0f)
                button.placeRelative(constraints.maxWidth - cancelWidth, y, zIndex = 1f)
            body.placeRelative(0, y)
        }
    }
}
