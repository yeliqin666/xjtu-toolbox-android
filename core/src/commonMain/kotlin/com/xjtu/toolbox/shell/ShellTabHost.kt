package com.xjtu.toolbox.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeLayoutState
import androidx.compose.runtime.Recomposer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import com.xjtu.toolbox.ui.components.PageSignal

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
    /** 切页时整页左右滑：往右边的格切，新页从右边进、旧页往左出（没有 [zoom] 时生效）。 */
    slide: Boolean = false,
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
    // 每页的内容分开组合：启动时那一页立刻组合；其余页在界面静止、主界面露着时分段预组合，
    // 还没组合好就被切过去的，先随切页动画露出底色，内容组合好再出现
    val contents = remember { mutableStateMapOf<String, TabContent>() }
    val latestContent = rememberUpdatedState(content)
    val outerVisible = rememberUpdatedState(LocalPageVisible.current)
    val latestSelected = rememberUpdatedState(selectedId)
    val tabOrder = rememberUpdatedState(nav.tabs.tabs.map { it.id })
    val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateFlow
    LaunchedEffect(contents, lifecycleState) {
        precomposeTabs(
            order = { tabOrder.value },
            contents = contents,
            selected = { latestSelected.value },
            visible = { outerVisible.value.value },
            lifecycleState = lifecycleState,
        )
    }

    val selectedIndex = nav.tabs.indexOf(selectedId)
    val pageColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
    val tabSlideDistance = with(LocalDensity.current) { 28.dp.toPx() }
    val slideOnly = slide && zoom == null
    // 上一次选中的格：第一次打开的页签据此决定从哪一侧滑进来
    val lastIndex = remember { intArrayOf(selectedIndex) }
    SideEffect { lastIndex[0] = selectedIndex }
    Box(
        modifier.fillMaxSize().pageBackdropBase(pageBackdropColor)
            .onSizeChanged { zoom?.hostSize = it.toSize() },
    ) {
        // 每页的外壳（位移、缩放、显隐）都常驻组合，很轻；重的是里面的内容，见 TabContent
        // 别在 key {} 里 return@key：Kotlin 2.4.20 会生成 R8 无法转 dex 的方法名
        nav.tabs.tabs.forEach { tab ->
            key(tab) {
                val isActive = selectedId == tab.id
                // 三档：zoom = 从底栏按钮放大；slideOnly = 整页左右滑；都没有 = 硬切（侧栏）
                val animated = zoom != null || slideOnly
                val tabAlpha by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0f,
                    animationSpec = if (animated) tween(if (isActive) 340 else 260) else snap(),
                    label = "tabAlpha",
                )
                // 不是启动时那一页：外壳一开始就藏着，以后切过来走的是切页过渡
                val openedBySwitch = remember { !isActive }
                val firstEntrance = remember { Animatable(if (openedBySwitch && zoom != null) 0f else 1f) }
                val isUnder = zoom != null && !isActive && tab.id == zoom.underId
                // 返回时正缩回按钮的那页；它在收的时候，当前这页（退回来的）从后面浮上来
                val isClosing = zoom != null && tab.id == zoom.closingId
                LaunchedEffect(Unit) { if (zoom != null) firstEntrance.animateTo(1f, tween(340)) }
                // 横移量按「几个单位」记：-1 = 在左边，1 = 在右边；一个单位多宽在绘制时定
                val tabIndex = nav.tabs.indexOf(tab.id)
                val offsetTarget = if (isActive) 0f else if (tabIndex < selectedIndex) -1f else 1f
                val tabOffset = remember {
                    Animatable(
                        if (openedBySwitch && animated) (if (tabIndex < lastIndex[0]) -1f else 1f) else offsetTarget,
                    )
                }
                LaunchedEffect(offsetTarget) {
                    when {
                        // 跨格切换时夹在中间的页：本来就在屏外，从一侧直接挪到另一侧，不从屏幕中间扫过去。
                        // 只有切出去的那页（此刻在屏幕正中）和切进来的那页走动画
                        !isActive && kotlin.math.abs(tabOffset.value) >= 1f -> tabOffset.snapTo(offsetTarget)
                        // 照 SleepDown 的整页滑动：一条不弹的减速曲线，末尾收得干净
                        slideOnly -> tabOffset.animateTo(offsetTarget, tween(TAB_SLIDE_MS, easing = TabSlideEasing))
                        animated -> tabOffset.animateTo(offsetTarget, tween(260))
                        else -> tabOffset.snapTo(offsetTarget)
                    }
                }
                val tabScale by animateFloatAsState(
                    targetValue = if (isActive || slideOnly) 1f else 0.96f,
                    animationSpec = if (animated) tween(360) else snap(),
                    label = "tabScale",
                )
                // 测量回调只读这个离散的「已完全藏起」：直接读逐帧变化的 tabAlpha，淡出期间每帧都要重测
                val activeNow = rememberUpdatedState(isActive)
                val hidden = remember { derivedStateOf { !activeNow.value && tabAlpha == 0f } }
                Box(
                    Modifier
                        .fillMaxSize()
                        // 隐藏的 tab 冻结测量约束，侧栏动画时不跟着逐帧重排
                        .freezeLayoutWhile { hidden.value }
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
                                slideOnly -> {
                                    // 整页宽度的平移，不淡不缩；完全滑出去的页不画
                                    val f = tabOffset.value
                                    alpha = if (f <= -1f || f >= 1f) 0f else 1f
                                    translationX = f * size.width
                                }
                                else -> {
                                    alpha = tabAlpha * firstEntrance.value
                                    translationX = tabOffset.value * tabSlideDistance
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
                    // 让隐藏 tab 里的常驻动画停下。滑进来的页等滑到位才算「可见」：它一变可见，页内的常驻动画、
                    // 取数都跟着重组，挤在切页那一帧里会把滑动的开头卡住
                    val latestActive by rememberUpdatedState(isActive)
                    val pageVisible by remember {
                        derivedStateOf { latestActive && (!slideOnly || tabOffset.value == 0f) }
                    }
                    // 内容与它读的信号每页一份、终身不换：可见性变化只让读它的地方重组，不让整页插槽重跑
                    val tabContent = remember {
                        val visibleSignal = PageSignal { pageVisible && outerVisible.value.value }
                        // 切过来的页签不演内部入场：整页淡入已经够了，控件再各自动一遍会显得很吵
                        val enterSignal = if (openedBySwitch) PageSignal.False else PageSignal.True
                        val page = ShellPage(tab.target, tab)
                        TabContent(ready = !openedBySwitch, prewarm = tab.prewarm) {
                            CompositionLocalProvider(
                                LocalPageVisible provides visibleSignal,
                                LocalEnterAnimations provides enterSignal,
                            ) { latestContent.value(page) }
                        }
                    }
                    DisposableEffect(tabContent) {
                        contents[tab.id] = tabContent
                        onDispose {
                            contents.remove(tab.id)
                            tabContent.cancel()
                        }
                    }
                    TabContentSlot(tabContent) { latestActive }
                }
            }
        }
    }
}

/**
 * 一页的内容：组合在自己的子组合里，可以先分帧预组合、组合好再接进布局。
 * [ready] 之前这一页只有外壳和底色。
 */
@Stable
private class TabContent(ready: Boolean, val prewarm: Boolean, val content: @Composable () -> Unit) {
    val layout = SubcomposeLayoutState()

    /** 已落到界面树上（节点建好、副作用开始跑）。 */
    var ready by mutableStateOf(ready)
        private set

    /** 已在后台组合完、还没落地。 */
    var composed by mutableStateOf(false)
        private set
    private var pending: SubcomposeLayoutState.PausedPrecomposition? = null

    /** 推进一段预组合，最多 [slice]；返回是否已组合完。已落地的页什么都不做。 */
    fun advance(slice: Duration): Boolean {
        if (ready) return true
        val work = pending ?: layout.createPausedPrecomposition(TAB_SLOT, content).also { pending = it }
        if (!work.isComplete) {
            val start = TimeSource.Monotonic.markNow()
            work.resume { start.elapsedNow() > slice }
        }
        composed = work.isComplete
        return composed
    }

    /**
     * 要显示了：把没做完的组合一口气做完、落到界面树上，随后的 subcompose 直接接管这份组合。
     * 在测量里调——切过来的第一帧内容就在，不先出一帧空白（懒列表的预取也是这样在测量时补完）。
     */
    fun finish() {
        pending?.let { work ->
            while (!work.isComplete) work.resume { false }
            work.apply()
        }
        pending = null
        ready = true
    }

    fun cancel() {
        pending?.cancel()
        pending = null
        composed = false
    }

    /** 后台还有活：没组合完，或组合完了、这一页允许提前落地而还没落地。 */
    val backgroundWork: Boolean get() = !ready && (!composed || prewarm)
}

/** [active]：这一页正被选中（在测量里读，切过来时这一层随之重新测量）。 */
@Composable
private fun TabContentSlot(tab: TabContent, active: () -> Boolean) {
    SubcomposeLayout(tab.layout, Modifier.fillMaxSize()) { constraints ->
        if (!tab.ready && active()) tab.finish()
        // 与 Box 一致：子项不受最小尺寸约束，都从左上角摆
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = if (tab.ready) subcompose(TAB_SLOT, tab.content).map { it.measure(loose) } else emptyList()
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

private const val TAB_SLOT = "content"

/** 每段预组合最多花的时间：段与段之间让出主线程，留够帧自己的组合、布局和绘制。 */
private val PRECOMPOSE_SLICE = 4.milliseconds
private val PRECOMPOSE_GAP = 8.milliseconds

/** 开始后台预组合前观察界面的时长：刚松手、动画之间的空档都不算安静。 */
private val PRECOMPOSE_SETTLE = 400.milliseconds

/** 确认安静后连续做这么久再复查，复查只看 [PRECOMPOSE_RECHECK]。 */
private val PRECOMPOSE_BURST = 150.milliseconds
private val PRECOMPOSE_RECHECK = 120.milliseconds

/**
 * 观察窗里界面忙的时间占比低于它算安静。逐帧动画（加载圈、惯性滚动、转场）几乎一直忙；
 * 常驻的小循环（底栏小岱的待机动作，约 30 帧/秒、每次只重组一下）只占一小部分，不该挡住预组合。
 */
private const val PRECOMPOSE_BUSY_RATIO = 0.5

/**
 * 界面忙不忙：任一 Recomposer 有待做的重组、或有动画在等下一帧（加载圈、惯性滚动、转场）都算忙。
 * 只看触摸不够——没人碰屏幕时加载圈照样在转，这时插一大块活就是掉帧。
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val uiBusy: Flow<Boolean> = Recomposer.runningRecomposers.flatMapLatest { recomposers ->
    if (recomposers.isEmpty()) {
        flowOf(false)
    } else {
        combine(recomposers.map { it.state }) { states -> states.any { it != Recomposer.State.Idle } }
    }
}

/** 观察 [window] 这么久，忙的占比低于 [PRECOMPOSE_BUSY_RATIO] 才算安静。 */
private suspend fun uiQuietFor(window: Duration): Boolean {
    var busySince: TimeMark? = null
    var busyTotal = Duration.ZERO
    withTimeoutOrNull(window) {
        uiBusy.collect { busy ->
            val since = busySince
            if (busy && since == null) busySince = TimeSource.Monotonic.markNow()
            if (!busy && since != null) {
                busyTotal += since.elapsedNow()
                busySince = null
            }
        }
    }
    busySince?.let { busyTotal += it.elapsedNow() }
    return busyTotal < window * PRECOMPOSE_BUSY_RATIO
}

/** 下一个要后台组合的页：按底栏顺序，跳过当前页（当前页另行处理）。 */
internal fun precomposeTarget(order: List<String>, pending: Set<String>, selected: String?): String? =
    order.firstOrNull { it in pending && it != selected }

/**
 * 没选中的页：主界面露着、应用在前台、界面静止时在后台分段组合。
 *
 * 默认**只组合不落地**——节点、`LaunchedEffect` 里的取数与登录都等用户真切过来才发生，页面行为
 * 与第一次点开时完全一样，只是组合这一大块提前做完了；切过来时由 [TabContentSlot] 在测量里补完并落地。
 * [ShellTab.prewarm] 的页组合完还会在静止时落地：副作用（读缓存、解图）先跑完，下一帧在后台完成首次测量，
 * 切过来时第一帧就是现成的。
 */
private suspend fun precomposeTabs(
    order: () -> List<String>,
    contents: Map<String, TabContent>,
    selected: () -> String?,
    visible: () -> Boolean,
    lifecycleState: StateFlow<Lifecycle.State>,
) {
    var settle = PRECOMPOSE_SETTLE
    while (true) {
        snapshotFlow {
            val current = selected()
            contents.any { (id, c) -> id != current && c.backgroundWork }
        }.first { it }
        // 应用不在前台（锁屏、切走）时不做：后台没人看，也不该在窗口不可见时占主线程
        if (!lifecycleState.value.isAtLeast(Lifecycle.State.STARTED)) {
            settle = PRECOMPOSE_SETTLE
            lifecycleState.first { it.isAtLeast(Lifecycle.State.STARTED) }
            continue
        }
        if (!visible()) {
            settle = PRECOMPOSE_SETTLE
            snapshotFlow { visible() }.first { it }
            continue
        }
        if (!uiQuietFor(settle)) {
            settle = PRECOMPOSE_SETTLE
            continue
        }
        settle = PRECOMPOSE_RECHECK
        val burst = TimeSource.Monotonic.markNow()
        var busyStreak = 0
        while (burst.elapsedNow() < PRECOMPOSE_BURST && visible()) {
            // 在帧与帧之间做，不占某一帧
            delay(PRECOMPOSE_GAP)
            if (!lifecycleState.value.isAtLeast(Lifecycle.State.STARTED)) break
            // 连着两次撞上界面忙（开始滚动、切页、转场）就收手；单次多半是常驻小循环的那一下
            busyStreak = if (uiBusy.first()) busyStreak + 1 else 0
            if (busyStreak >= 2) {
                settle = PRECOMPOSE_SETTLE
                break
            }
            // 等待期间这一页可能已被切过去、在测量里落地：目标在等完之后再挑
            val pending = contents.filterValues { it.backgroundWork }.keys
            val id = precomposeTarget(order(), pending, selected()) ?: break
            val target = contents[id] ?: break
            if (!target.composed) {
                target.advance(PRECOMPOSE_SLICE)
            } else {
                // 允许提前落地的页：落地后下一帧在后台量好，那一帧较重，之后重新观察整段
                target.finish()
                settle = PRECOMPOSE_SETTLE
                break
            }
        }
    }
}

private const val TAB_SLIDE_MS = 360

/** 照 SleepDown 主页切页的那条减速曲线：起步快、中段稳、末尾缓。 */
private val TabSlideEasing = CubicBezierEasing(0.22f, 0.72f, 0.20f, 1f)
private const val TAB_ZOOM_MS = 820

/** 返回时当前页缩回按钮：比打开快，像收起而不是再演一遍。 */
private const val TAB_CLOSE_MS = 360
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
