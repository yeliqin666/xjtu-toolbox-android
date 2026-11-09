package com.xjtu.toolbox.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 菜单从哪里长出来。 */
enum class MenuMorph {
    /** 按钮本身变成菜单：全程包住按钮，收起时缩回按钮（顶栏按钮）。 */
    FromIcon,

    /** 在这一行右侧、顶边压着行中线展开，全程是圆角矩形（设置里的下拉项）。 */
    FromRow,

    /** 在锚点正下方居中展开（分段切换器的某一格）。 */
    FromTab,
}

/**
 * 弹出菜单：挂在触发按钮（或整行）所在的那个 Box 里，节奏照 HyperOS 系统菜单。
 *
 * 下拉行：面板贴着行的右上角长大，内容等比缩放，外框高度展开得更慢（[AppDecelerate]）。
 * 按钮：一开始就边变大边往目标方向走，宽度先撑开、高度慢慢展开，内容跟着宽度等比缩放；收起走同一条路径。
 * 收起时边缩边模糊淡出。顶栏按钮直接用 [AppIconMenu]，它把按钮和菜单的交接都管了。
 *
 * [onVisibleChange]：弹层真正上屏 / 收起动画播完时回调，需要跟菜单同步的外部状态读它，不要自己估时间。
 *
 * 弹层铺满整个窗口：起点在菜单外面，弹层只有菜单大的话起点会被窗口裁掉。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopEnd,
    morph: MenuMorph = MenuMorph.FromIcon,
    trigger: (@Composable () -> Unit)? = null,
    onVisibleChange: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    // 收起动画播完之前弹层还得留着
    var shown by remember { mutableStateOf(false) }
    if (expanded) shown = true
    val progress = remember { Animatable(0f) }
    val sub = remember { MenuSub() }
    // 弹层窗口要过几帧才上屏：等它量好再开始，否则最快的开头那段没人看见
    var ready by remember { mutableStateOf(false) }
    val visibleCallback by rememberUpdatedState(onVisibleChange)
    val onScreen = shown && ready
    LaunchedEffect(onScreen) { visibleCallback(onScreen) }
    LaunchedEffect(expanded, ready) {
        if (expanded) {
            if (ready) {
                withFrameNanos { }
                if (morph == MenuMorph.FromIcon) progress.animateTo(1f, tween(ICON_OPEN_MS, easing = LinearEasing))
                else progress.animateTo(1f, tween(MENU_OPEN_MS, easing = AppDecelerate))
            }
        } else if (shown) {
            if (morph == MenuMorph.FromIcon) progress.animateTo(0f, tween(ICON_CLOSE_MS, easing = AppDecelerate))
            else progress.animateTo(0f, tween(MENU_CLOSE_MS, easing = AppDecelerate))
            shown = false
            ready = false
            sub.reset()
        }
    }
    LaunchedEffect(sub.opened) {
        if (sub.opened) {
            sub.anim.animateTo(1f, tween(SUB_OPEN_MS, easing = AppDecelerate))
        } else if (sub.panel != null) {
            sub.anim.animateTo(0f, tween(SUB_CLOSE_MS, easing = AppDecelerate))
            sub.panel = null
        }
    }
    // 锚点（触发按钮所在的那个父布局）在屏幕上的矩形：用一个零尺寸的探针读父布局的坐标。
    // 必须用屏幕坐标：弹层窗口的原点在状态栏下面，和应用窗口的坐标差一个状态栏
    val anchorOnScreen = remember { mutableStateOf<Rect?>(null) }
    Spacer(
        Modifier.onGloballyPositioned { probe ->
            val parent = probe.parentLayoutCoordinates ?: return@onGloballyPositioned
            if (parent.isAttached) {
                anchorOnScreen.value = Rect(parent.positionOnScreen(), parent.size.toSize())
            }
        },
    )
    if (!shown) return

    val alignEnd = alignment == Alignment.TopEnd || alignment == Alignment.BottomEnd
    // 二级面板开着时，返回键和点外面先退回上一层
    val dismiss = { if (sub.opened) sub.opened = false else onDismissRequest() }
    Popup(
        popupPositionProvider = FullWindowPopup,
        onDismissRequest = dismiss,
        properties = PopupProperties(focusable = true),
    ) {
        // 弹层自己在屏幕上的原点：锚点减掉它，就是锚点在弹层坐标里的位置
        var popupOrigin by remember { mutableStateOf<Offset?>(null) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    popupOrigin = it.positionOnScreen()
                    ready = true
                }
                // 点到菜单以外：收起（菜单项自己的点击先被它们吃掉）
                .pointerInput(Unit) { detectTapGestures { dismiss() } },
        ) {
            val screen = anchorOnScreen.value
            val origin = popupOrigin
            if (screen != null && origin != null) {
                val local = screen.translate(-origin)
                val bounds = IntRect(
                    local.left.roundToInt(), local.top.roundToInt(),
                    local.right.roundToInt(), local.bottom.roundToInt(),
                )
                MorphMenu(bounds, { progress.value }, !expanded, alignEnd, morph, modifier, trigger, sub, content)
            }
        }
    }
}

/**
 * 顶栏图标按钮 + 它弹出的菜单：按钮本身变成菜单。弹层在屏幕上时真按钮藏起来，图标由面板带着走；
 * 弹层撤掉的那一刻按钮才回来，这时图标已经落回原位，两者重合。
 */
@Composable
fun AppIconMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var menuOnScreen by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.graphicsLayer { alpha = if (menuOnScreen) 0f else 1f },
        ) { icon() }
        AppDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            trigger = icon,
            onVisibleChange = { menuOnScreen = it },
            content = content,
        )
    }
}

/** 弹层铺满整个窗口（变形起点在菜单外面，弹层只有菜单大的话起点会被裁掉）。 */
private object FullWindowPopup : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/**
 * 菜单本体：排在最终位置，由图层把它变到当前的矩形（内容等比缩放、外框裁切），进度只在绘制阶段读，
 * 不逐帧重组、不重排。
 */
@Composable
private fun MorphMenu(
    anchor: IntRect,
    progress: () -> Float,
    closing: Boolean,
    alignEnd: Boolean,
    morph: MenuMorph,
    modifier: Modifier,
    trigger: (@Composable () -> Unit)?,
    sub: MenuSub,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val hairline = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val ltr =LocalLayoutDirection.current == LayoutDirection.Ltr
    val safeTop = WindowInsets.statusBars.getTop(density)
    val endRadius = with(density) { MENU_RADIUS.toPx() }
    val rowDrop = with(density) { ROW_START_DROP.toPx() }
    val closeBlur = with(density) { MENU_CLOSE_BLUR.toPx() }
    val end = alignEnd == ltr
    // 菜单最终的矩形（窗口坐标），量完时填进来，绘制阶段读
    val target = remember { mutableStateOf(Rect.Zero) }

    // 起点：按钮那颗圆；或贴着菜单右上角的一小条（宽约七成、高一成，内容按宽度缩放）
    fun startRect(t: Rect): Rect = when (morph) {
        MenuMorph.FromIcon -> {
            val d = minOf(anchor.width, anchor.height).toFloat().coerceAtLeast(1f)
            val c = anchor.center
            Rect(c.x - d / 2f, c.y - d / 2f, c.x + d / 2f, c.y + d / 2f)
        }
        MenuMorph.FromRow -> {
            val w = t.width * 0.7f
            val top = t.top + rowDrop
            if (end) Rect(t.right - w, top, t.right, top + t.height * 0.1f)
            else Rect(t.left, top, t.left + w, top + t.height * 0.1f)
        }
        MenuMorph.FromTab -> {
            val w = t.width * 0.7f
            Rect(t.center.x - w / 2f, t.top, t.center.x + w / 2f, t.top + t.height * 0.1f)
        }
    }

    // 下拉行：四条边走同一条曲线。
    // 按钮：宽高分开走（见 iconHeight / iconWidth）。带图标的连位置一起移动；
    // 没传图标的真按钮藏不了，面板贴边长大、全程盖住它
    fun current(p: Float, t: Rect): Rect {
        val s = startRect(t)
        if (morph != MenuMorph.FromIcon) {
            return Rect(lerp(s.left, t.left, p), lerp(s.top, t.top, p), lerp(s.right, t.right, p), lerp(s.bottom, t.bottom, p))
        }
        val ph = iconHeight(p)
        val pw = iconWidth(p)
        if (trigger == null) {
            return Rect(lerp(s.left, t.left, pw), lerp(s.top, t.top, ph), lerp(s.right, t.right, pw), lerp(s.bottom, t.bottom, ph))
        }
        val cx = lerp(s.center.x, t.center.x, iconMoveX(p))
        val cy = lerp(s.center.y, t.center.y, iconMoveY(p))
        val w = lerp(s.width, t.width, pw)
        val h = lerp(s.height, t.height, ph)
        return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    Layout(
        content = {
            Column(
                modifier
                    // 按最长那一项收紧宽度：菜单项都 fillMaxWidth，不收的话会一路撑到上限
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 160.dp, max = 280.dp)
                    .heightIn(max = 400.dp)
                    .graphicsLayer {
                        val p = progress().coerceIn(0f, 1f)
                        val t = target.value
                        if (t.width <= 0f) return@graphicsLayer
                        val r = current(p, t)
                        val s = (r.width / t.width).coerceAtLeast(0.01f)
                        transformOrigin = TransformOrigin(if (end) 1f else 0f, 0f)
                        // 二级面板展开时，这一层退到后面
                        val recede = 1f - (1f - SUB_PARENT_SCALE) * sub.anim.value
                        scaleX = s * recede
                        scaleY = s * recede
                        translationX = if (end) r.right - t.right else r.left - t.left
                        translationY = r.top - t.top
                        val fromIcon = morph == MenuMorph.FromIcon
                        val startRadius = if (fromIcon) startRect(t).minDimension / 2f else endRadius
                        val radius = lerp(startRadius, endRadius, if (fromIcon) iconHeight(p) else p)
                            .coerceAtMost(r.minDimension / 2f)
                        // 轮廓写在缩放前的坐标里
                        shape = OffsetRoundRectShape(Rect(0f, 0f, t.width, r.height / s), radius / s)
                        clip = true
                        shadowElevation = MENU_ELEVATION.toPx() * p
                        ambientShadowColor = MenuAmbientShadow
                        spotShadowColor = MenuSpotShadow
                        // 展开几乎立刻显形；收起提前淡出，同时糊掉内容
                        // 按钮：面板最后才褪去（内容和图标的交接都在它上面完成）。下拉行：收起时整块糊掉淡出
                        val a = when {
                            !closing -> (p / if (morph == MenuMorph.FromIcon) 0.04f else 0.1f).coerceIn(0f, 1f)
                            morph == MenuMorph.FromIcon -> ((p - 0.02f) / 0.1f).coerceIn(0f, 1f)
                            else -> ((p - 0.15f) / 0.5f).coerceIn(0f, 1f)
                        }
                        alpha = a
                        val blur = if (closing && morph != MenuMorph.FromIcon) closeBlur * (1f - a) else 0f
                        renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
                    }
                    .background(surface)
                    // 一圈极细的描边：面板和页面同色时也分得出层次
                    .border(0.5.dp, hairline, RoundedCornerShape(MENU_RADIUS))
                    // 内容：按钮还小时先是一块干净的面板，撑开后才由模糊变清晰地显出来
                    .graphicsLayer {
                        if (morph == MenuMorph.FromIcon) {
                            val p = progress()
                            alpha = ((p - CONTENT_IN) / 0.14f).coerceIn(0f, 1f)
                            val blur = MENU_CONTENT_BLUR.toPx() * (1f - ((p - CONTENT_IN) / 0.2f).coerceIn(0f, 1f))
                            renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
                        }
                    }
                    // 菜单空白处的点击不算「点到外面」
                    .pointerInput(Unit) { detectTapGestures { } }
                    // 退到后面时：里面的项不接点击，点这一层任意处和点菜单外一样，收起二级、回到这一层
                    .pointerInput(sub.opened) {
                        if (sub.opened) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                    if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Release) sub.opened = false
                                }
                            }
                        }
                    }
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                CompositionLocalProvider(LocalMenuClosing provides closing, LocalMenuSub provides sub) { content() }
            }
            // 按钮的图标：在小面板上和内容交接（不与面板的出现 / 褪去同时发生），跟着面板走
            if (trigger != null) {
                Box(
                    Modifier.graphicsLayer {
                        val p = progress().coerceIn(0f, 1f)
                        val t = target.value
                        if (t.width <= 0f) return@graphicsLayer
                        val c = current(p, t).center
                        translationX = c.x - size.width / 2f
                        translationY = c.y - size.height / 2f
                        alpha = (1f - (p - 0.04f) / 0.08f).coerceIn(0f, 1f)
                    },
                    contentAlignment = Alignment.Center,
                ) { trigger() }
            }
            sub.panel?.let { panel ->
                val drop = with(density) { SUB_DROP.toPx() }
                val inset = with(density) { 12.dp.toPx() }
                Column(
                    Modifier
                        .graphicsLayer {
                            val q = sub.anim.value
                            translationY = -drop * (1f - q)
                            // 文字不缩放：面板从标题行往下展开，只动裁切的高度
                            val height = lerp(sub.rowHeight + inset, size.height, q)
                            shape = OffsetRoundRectShape(Rect(0f, 0f, size.width, height), endRadius)
                            clip = true
                            shadowElevation = MENU_ELEVATION.toPx()
                            ambientShadowColor = MenuAmbientShadow
                            spotShadowColor = MenuSpotShadow
                            val leaving = if (closing) ((progress() - 0.45f) / 0.4f).coerceIn(0f, 1f) else 1f
                            alpha = (q / 0.08f).coerceIn(0f, 1f) * leaving
                        }
                        .background(surface)
                        .border(0.5.dp, hairline, RoundedCornerShape(MENU_RADIUS))
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(vertical = 6.dp),
                ) {
                    MenuSubRow(panel.title, panel.summary, panel.leadingIcon, { -90f * sub.anim.value }) { sub.opened = false }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).height(0.5.dp).background(hairline))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        CompositionLocalProvider(LocalMenuClosing provides closing) { panel.content(this) }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val windowW = constraints.maxWidth
        val windowH = constraints.maxHeight
        val margin = 8.dp.roundToPx()
        val loose = Constraints(maxWidth = windowW, maxHeight = windowH)
        val menu = measurables[0].measure(loose)
        val icon = if (trigger != null) measurables[1].measure(loose) else null
        val inset = if (morph == MenuMorph.FromRow) 12.dp.roundToPx() else 0
        val x = when {
            morph == MenuMorph.FromTab -> anchor.center.x - menu.width / 2
            end -> anchor.right - inset - menu.width
            else -> anchor.left + inset
        }
            .coerceIn(margin, (windowW - menu.width - margin).coerceAtLeast(margin))
        val top = safeTop + margin
        val y = when (morph) {
            // 顶边略高于这一行的中线，盖住当前值；碰到屏幕底就往上让
            MenuMorph.FromRow -> (anchor.center.y - ROW_OVERLAP.roundToPx())
                .coerceIn(top, (windowH - margin - menu.height).coerceAtLeast(top))
            MenuMorph.FromTab -> (anchor.bottom + 4.dp.roundToPx())
                .coerceIn(top, (windowH - margin - menu.height).coerceAtLeast(top))
            // 顶边压在按钮的顶边上，全程包住按钮（按钮变成了菜单）；往下放不下才往上挪
            MenuMorph.FromIcon -> anchor.top
                .coerceIn(top, (windowH - margin - menu.height).coerceAtLeast(top))
        }
        target.value = Rect(x.toFloat(), y.toFloat(), (x + menu.width).toFloat(), (y + menu.height).toFloat())
        // 二级面板和上一层同宽，高度可以超过上一层，到屏幕底才在里面滚
        val panelY = y + sub.rowTop.roundToInt() + SUB_DROP.roundToPx()
        val panel = if (sub.panel != null) {
            measurables.last().measure(
                Constraints(
                    minWidth = menu.width,
                    maxWidth = menu.width,
                    maxHeight = (windowH - panelY - margin).coerceAtLeast(0),
                ),
            )
        } else {
            null
        }
        layout(windowW, windowH) {
            menu.placeWithLayer(x, y)
            icon?.placeWithLayer(0, 0)
            panel?.placeWithLayer(x, panelY)
        }
    }
}

/** 按给定矩形与圆角裁切（矩形可以在图层自身的范围之外）。 */
private class OffsetRoundRectShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * 菜单项：按下时只有这一行（四周留着白边）慢慢压灰，松手再缓缓退掉。
 */
@Composable
fun AppDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // 点中后菜单开始收起：这一项的灰色留着，一起收走；点了菜单不收（原地切换的项）就照常退掉
    var clicked by remember { mutableStateOf(false) }
    val closing by rememberUpdatedState(LocalMenuClosing.current)
    // 点完没让菜单收起（打开二级、原地切换）就忘掉这次点击，否则之后收起菜单时它会再灰一次
    LaunchedEffect(clicked) {
        if (clicked) {
            withFrameNanos { }
            withFrameNanos { }
            if (!closing) clicked = false
        }
    }
    val active = pressed || (clicked && closing)
    val press by animateFloatAsState(
        if (active) 1f else 0f,
        if (active) tween(240, easing = LinearOutSlowInEasing) else tween(480, easing = FastOutSlowInEasing),
        label = "menuItemPress",
    )
    val tint = MiuixTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .drawBehind { if (press > 0f) drawRect(tint.copy(alpha = 0.08f * press)) }
            .clickable(interactionSource = source, indication = null, enabled = enabled) {
                clicked = true
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) {
            text()
        }
        if (trailingIcon != null) {
            Spacer(Modifier.width(12.dp))
            trailingIcon()
        }
    }
}

/**
 * 带二级菜单的一行：点开后这一层退到后面，二级面板从这一行往下展开，箭头由 > 转成 ^。
 * 只能放在 [AppDropdownMenu] 里。
 */
@Composable
fun AppDropdownSubMenu(
    title: String,
    summary: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sub = LocalMenuSub.current ?: return
    // 位置各记各的：一个菜单可以有好几行二级，点到哪行才交给面板
    val placed = remember { floatArrayOf(0f, 0f) }
    Box(
        Modifier.onPlaced {
            placed[0] = it.positionInParent().y
            placed[1] = it.size.height.toFloat()
        },
    ) {
        MenuSubRow(title, summary, leadingIcon, { 0f }) {
            sub.rowTop = placed[0]
            sub.rowHeight = placed[1]
            sub.panel = MenuPanel(title, summary, leadingIcon, content)
            sub.opened = true
        }
    }
}

@Composable
private fun MenuSubRow(
    title: String,
    summary: String?,
    leadingIcon: @Composable (() -> Unit)?,
    chevronRotation: () -> Float,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    AppDropdownMenuItem(
        text = {
            Column {
                Text(title, style = MiuixTheme.textStyles.body1, color = colors.onSurfaceContainer)
                if (summary != null) {
                    Text(summary, style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
                }
            }
        },
        leadingIcon = leadingIcon,
        trailingIcon = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = chevronRotation() },
            )
        },
        onClick = onClick,
    )
}

/** 单选菜单项（学期、时间范围这类）：选中的那项主题色加粗、右侧打勾。 */
@Composable
fun AppDropdownOption(text: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
    AppDropdownMenuItem(
        text = {
            Text(
                text,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
            )
        },
        trailingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = "已选", tint = color, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        onClick = onClick,
    )
}

/** 菜单是否正在收起：菜单项据此决定点中后的灰色要不要留着。 */
private val LocalMenuClosing = compositionLocalOf { false }

private val LocalMenuSub = compositionLocalOf<MenuSub?> { null }

private class MenuPanel(
    val title: String,
    val summary: String?,
    val leadingIcon: @Composable (() -> Unit)?,
    val content: @Composable ColumnScope.() -> Unit,
)

/** 一个菜单的二级面板状态。[rowTop]、[rowHeight] 是触发行在上一层里的位置。 */
@Stable
private class MenuSub {
    var panel by mutableStateOf<MenuPanel?>(null)
    var opened by mutableStateOf(false)
    val anim = Animatable(0f)
    var rowTop = 0f
    var rowHeight = 0f

    suspend fun reset() {
        opened = false
        panel = null
        anim.snapTo(0f)
    }
}

private const val SUB_OPEN_MS = 420
private const val SUB_CLOSE_MS = 340
private const val SUB_PARENT_SCALE = 0.95f

/** 二级面板比触发行低多少，上一层的顶边从这里露出来。 */
private val SUB_DROP = 8.dp

private val MENU_RADIUS = 18.dp
private const val MENU_OPEN_MS = 440
private const val MENU_CLOSE_MS = 340
private const val ICON_OPEN_MS = 660
private const val ICON_CLOSE_MS = 500

// 按钮 → 菜单（入参是时间进度）：位置在前段走完，横向先于纵向，路径带弧线。
// 尺寸是两段叠加：前段随位移撑开，后段提前柔和接入，轻轻越过目标再落稳。
private fun iconMoveX(p: Float): Float = iconEaseOut(p / 0.36f)
private fun iconMoveY(p: Float): Float = iconEaseOut(p / 0.44f)
private fun iconWidth(p: Float): Float = iconGrow(p, firstShare = 0.72f)
private fun iconHeight(p: Float): Float = iconGrow(p, firstShare = 0.58f)

private fun iconGrow(p: Float, firstShare: Float): Float =
    firstShare * iconEaseOut(p / 0.4f) +
        (1f - firstShare) * iconSizeSettle((p - 0.22f) / 0.78f)

// 起步速度为 0，避免第二段突然加速；终点速度、加速度都为 0，回落后自然停住。
// 第二段最高到 1.02214，乘上它在宽高中的份额后，面板的过冲不到 1%。
// 位置仍走原来的单调曲线，回落只发生在面板尺寸上，内容继续等比缩放。
private fun iconSizeSettle(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    val rest = 1f - t
    return 1f - rest * rest * rest * (1f + 3f * t - 8f * t * t)
}

private fun iconEaseOut(x: Float): Float {
    val rest = 1f - x.coerceIn(0f, 1f)
    return 1f - rest * rest * rest
}

/** 按钮菜单的内容从这个进度开始显出来。 */
private const val CONTENT_IN = 0.07f

/** 按钮菜单内容的模糊：只在过渡早期出现。 */
private val MENU_CONTENT_BLUR = 7.dp

/** 下拉行：菜单顶边高出行中线多少。 */
private val ROW_OVERLAP = 11.dp

/** 下拉行：起点比菜单最终顶边低多少。 */
private val ROW_START_DROP = 24.dp

/** 收起时内容的最大模糊半径。 */
private val MENU_CLOSE_BLUR = 10.dp

/**
 * 菜单投影：一圈柔和的环境光加一层往下的投影。系统还会把这里的透明度乘上自己的低系数
 * （环境光约 4%、投影约 19%），所以写得比看上去的深。
 */
private val MENU_ELEVATION = 24.dp
private val MenuAmbientShadow = Color.Black
private val MenuSpotShadow = Color.Black.copy(alpha = 0.5f)
