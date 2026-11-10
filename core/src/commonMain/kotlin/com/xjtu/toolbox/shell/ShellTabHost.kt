package com.xjtu.toolbox.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import com.xjtu.toolbox.ui.components.LocalEnterAnimations
import com.xjtu.toolbox.nav.ButtonZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.xjtu.toolbox.ui.components.LocalPageVisible

/**
 * 页面底色跟着内容一起画。
 *
 * 这一层同时是 COUI 底栏的采样源：以前底色由外层 Scaffold 铺，「我的」这种自己不透底的页
 * 录出来是一片透明像素，材质拿不到颜色（旧色滤会自己补 alpha，所以只表现为一块死灰；
 * 换成按真实 alpha 混的 AGSL 材质后就是整块消失）。
 */
private fun Modifier.pageBackdropBase(color: Color): Modifier = drawWithContent {
    drawRect(color)
    drawContent()
}

/** 当前页那一层：画哪几页由 [ShellPageHost] 说。 */
@Composable
fun ShellTabHost(
    nav: ShellNavState,
    pageHost: ShellPageHost = ShellPageHost.VISITED_TABS,
    modifier: Modifier = Modifier,
    /** 切页放大的进度与几何；null = 这一形态不做放大（比如侧栏）。 */
    zoom: ShellTabZoom? = null,
    content: @Composable (ShellPage) -> Unit,
) {
    val pageBackdropColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
    // 只有当前这一页：不常驻、不横移（横移缩放只对「同层的几格」成立）
    if (!nav.keepsVisitedTabPages(pageHost)) {
        val page = nav.currentShellPage()
        Box(modifier.fillMaxSize().pageBackdropBase(pageBackdropColor)) { content(page) }
        return
    }

    val selectedId = nav.selected?.id
    // 隐藏的 tab 还留在组合里，输入框的焦点不会自己走：不清掉的话键盘跟着切到别的页，
    // 从别的应用切回来时系统还会照这个焦点把键盘弹出来（#353）
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focusedTabId by remember { mutableStateOf(selectedId) }
    LaunchedEffect(selectedId) {
        if (focusedTabId != selectedId) {
            focusedTabId = selectedId
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    var composedIds by remember { mutableStateOf(setOfNotNull(selectedId)) }
    LaunchedEffect(selectedId) { composedIds = composedIds + setOfNotNull(selectedId) }

    val selectedIndex = nav.tabs.indexOf(selectedId)
    val pageColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
    val tabSlideDistance = with(LocalDensity.current) { 28.dp.toPx() }
    Box(modifier.fillMaxSize().pageBackdropBase(pageBackdropColor).onSizeChanged { zoom?.hostSize = it.toSize() }) {
        // 别在 key {} 里 return@key：Kotlin 2.4.20 会生成 R8 无法转 dex 的方法名
        nav.tabs.tabs.filter { it.id in composedIds || it.id == selectedId }.forEach { tab ->
            key(tab) {
                val isActive = selectedId == tab.id
                // zoom 为空 = 这一形态不做切页放大；此时整页切换也不演任何过渡（淡入淡出、横移、缩放、
                // 入场全部瞬时到位），四个页签之间是硬切。
                val animated = zoom != null
                val tabAlpha by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0f,
                    animationSpec = if (animated) tween(if (isActive) 340 else 260) else snap(),
                    label = "tabAlpha",
                )
                // 第一次打开的页签也要走入场：animateFloatAsState 的初值就是目标值，不会有过渡
                val openedBySwitch = remember { tab.id !in composedIds }
                val firstEntrance = remember { Animatable(if (openedBySwitch && animated) 0f else 1f) }
                val isUnder = zoom != null && !isActive && tab.id == zoom.underId
                // 返回时正缩回按钮的那页；它在收的时候，当前这页（退回来的）从后面浮上来
                val isClosing = zoom != null && tab.id == zoom.closingId
                LaunchedEffect(Unit) { if (animated) firstEntrance.animateTo(1f, tween(340)) }
                val tabOffset by animateFloatAsState(
                    targetValue = when {
                        isActive -> 0f
                        nav.tabs.indexOf(tab.id) < selectedIndex -> -tabSlideDistance
                        else -> tabSlideDistance
                    },
                    animationSpec = if (animated) tween(260) else snap(),
                    label = "tabOffset",
                )
                val tabScale by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0.96f,
                    animationSpec = if (animated) tween(360) else snap(),
                    label = "tabScale",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        // 隐藏的 tab 冻结测量约束，侧栏动画时不跟着逐帧重排
                        .freezeLayoutWhile { !isActive && tabAlpha == 0f }
                        .zIndex(if (isClosing) 2f else if (isActive) 1f else 0f)
                        .graphicsLayer {
                            val z = zoom?.progress ?: 1f
                            val returningUnder = isActive && zoom?.closingId != null
                            when {
                                isClosing -> zoom?.let { it.applyTo(this, it.closingIndex) }
                                returningUnder -> {
                                    val s = 1f + (ButtonZoom.RECEDE_SCALE - 1f) * z
                                    scaleX = s
                                    scaleY = s
                                }
                                isActive && z < 1f -> zoom?.applyTo(this)
                                isUnder && z < 1f -> {
                                    // 上一页整体很快淡出，不留在底下看着控件一个个消失
                                    alpha = (1f - z / UNDER_FADE_END).coerceIn(0f, 1f)
                                    val s = 1f + (ButtonZoom.RECEDE_SCALE - 1f) * z
                                    scaleX = s
                                    scaleY = s
                                }
                                else -> {
                                    alpha = tabAlpha * firstEntrance.value
                                    translationX = tabOffset
                                    val entranceScale = 0.96f + 0.04f * firstEntrance.value
                                    scaleX = tabScale * entranceScale
                                    scaleY = tabScale * entranceScale
                                }
                            }
                        }
                        .drawWithContent {
                            val z = zoom?.progress ?: 1f
                            val returningUnder = isActive && zoom?.closingId != null
                            // 放大中 / 收回中的那页垫一层实底：飞行时才是一块看得见的东西，而不是几张散的卡片
                            if (isClosing || (isActive && z < 1f && !returningUnder)) drawRect(pageColor)
                            drawContent()
                            // 底下那页的压暗：打开时随新页长大加深，返回时随旧页缩走变浅
                            if ((isUnder && z < 1f) || returningUnder) {
                                drawRect(Color.Black.copy(alpha = ButtonZoom.RECEDE_DIM * z))
                            }
                        }
                        .pointerInput(isActive) {
                            if (!isActive) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
                ) {
                    // 让隐藏 tab 里的常驻动画停下
                    CompositionLocalProvider(
                        LocalPageVisible provides (isActive && LocalPageVisible.current),
                        // 切过来的页签不演内部入场：整页淡入已经够了，控件再各自动一遍会显得很吵
                        LocalEnterAnimations provides !openedBySwitch,
                    ) {
                        // 同一份对象：host 重组（选中项变化）时，没变的页不跟着重组
                        content(remember(tab) { ShellPage(tab.target, tab) })
                    }
                }
            }
        }
    }
}

private const val TAB_ZOOM_MS = 820

/** 返回时当前页缩回按钮：比打开快，像收起而不是再演一遍。 */
private const val TAB_CLOSE_MS = 420
private const val UNDER_FADE_END = 0.3f

/**
 * 切页时整页的放大（顶栏也在页面容器里，见 [ShellBarPage]）。
 * [origin] 给出底栏当前格按钮的矩形，坐标系与页面容器相同。
 */
@Stable
class ShellTabZoom internal constructor(
    private val selectedIndex: () -> Int,
    private val tabCount: () -> Int,
) {
    internal var underId: String? by mutableStateOf(null)

    /** 返回时正缩回自己按钮的那一页（[closingIndex] 是它在底栏上的位置）；null = 没在收。 */
    internal var closingId: String? by mutableStateOf(null)
    internal var closingIndex = -1
    internal var run by mutableStateOf(Animatable(1f))
    internal var hostSize by mutableStateOf(Size.Zero)
    var origin: ((index: Int, count: Int, size: Size) -> Rect)? = null

    /** 0 = 还在按钮上，1 = 铺满。只在绘制阶段读。打开时 0→1，返回收起时 1→0。 */
    val progress: Float get() = run.value

    fun applyTo(scope: GraphicsLayerScope, index: Int = selectedIndex()) {
        val full = hostSize
        val from = origin?.invoke(index, tabCount(), full)
        if (from != null && full.width > 0f) ButtonZoom.apply(scope, from, full, progress, 0f, risesFromBottom = true)
    }
}

/** [enabled] 为假（这一形态没有底栏按钮）时返回 null，切页走普通淡入。 */
@Composable
fun rememberShellTabZoom(nav: ShellNavState, enabled: Boolean): ShellTabZoom? {
    if (!enabled) return null
    val selectedId = nav.selected?.id
    val zoom = remember(nav) { ShellTabZoom({ nav.tabs.indexOf(nav.selected?.id) }, { nav.tabs.tabs.size }) }
    // 在组合里同步切到新一轮：第一帧就是起始状态，不会闪一下全屏
    val shownId = remember { object { var id = selectedId } }
    remember(selectedId) {
        val previous = shownId.id
        if (previous != selectedId) {
            if (nav.returning && previous != null) {
                // 返回：刚才那页缩回自己的按钮，退回去的这页本来就在，只是从后面浮上来
                zoom.underId = null
                zoom.closingId = previous
                zoom.closingIndex = nav.tabs.indexOf(previous)
                zoom.run = Animatable(1f)
            } else {
                zoom.closingId = null
                zoom.underId = previous
                zoom.run = Animatable(0f)
            }
        }
    }
    SideEffect { shownId.id = selectedId }
    LaunchedEffect(zoom.run) {
        if (zoom.closingId != null) {
            zoom.run.animateTo(0f, tween(TAB_CLOSE_MS, easing = ButtonZoom.Easing))
            zoom.closingId = null
            zoom.run = Animatable(1f)
            return@LaunchedEffect
        }
        // 新页第一次组合要好几十毫秒：等它画出来再开始计时，不然飞行的开头正好被这一帧吃掉
        if (zoom.run.value < 1f) repeat(2) { androidx.compose.runtime.withFrameNanos { } }
        zoom.run.animateTo(1f, tween(TAB_ZOOM_MS, easing = ButtonZoom.Easing))
    }
    return zoom
}

/** [frozen] 为真时沿用上一次的测量约束，子树不跟着父布局尺寸动画重排。 */
@Composable
private fun Modifier.freezeLayoutWhile(frozen: () -> Boolean): Modifier {
    val last = remember { arrayOfNulls<Constraints>(1) }
    return this.layout { measurable, constraints ->
        val use = if (frozen()) last[0] ?: constraints else constraints.also { last[0] = it }
        val placeable = measurable.measure(use)
        layout(constraints.constrainWidth(placeable.width), constraints.constrainHeight(placeable.height)) {
            placeable.place(0, 0)
        }
    }
}

/** 壳交给 [AppShell] 的 `page` lambda 的那一页：是哪一页（[target]）+ 它落在哪一格上（[tab]）。 */
class ShellPage(
    val target: ShellTarget,
    val tab: ShellTab?,
)

/** 当前页那一层留下谁 —— [AppShell] / [ShellTabHost] 按它分派两种摆法。 */
enum class ShellPageHost {

    /** 进过组合的 tab 常驻（`:app` 的五格）。 */
    VISITED_TABS,

    /** 只画当前这一页（桌面 / web）。 */
    CURRENT_ONLY,
}

/** 这一端要不要走「tab 常驻」那一档 —— 纯函数，好单测（壳的语义，不是端的事）。 */
fun ShellNavState.keepsVisitedTabPages(pageHost: ShellPageHost): Boolean =
    pageHost == ShellPageHost.VISITED_TABS && selected != null

/** 只画当前这一页时，壳交给 `page` 的那一个 [ShellPage] —— 纯函数，好单测。 */
fun ShellNavState.currentShellPage(): ShellPage = ShellPage(target, selected)
