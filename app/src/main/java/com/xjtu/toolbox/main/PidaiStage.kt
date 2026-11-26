package com.xjtu.toolbox.main

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.agent.AgentPendingPrompt
import com.xjtu.toolbox.agent.AgentVoiceInput
import com.xjtu.toolbox.agent.PidaiHandoffFx
import com.xjtu.toolbox.agent.PidaiHoverPanel
import com.xjtu.toolbox.agent.PidaiOrb
import com.xjtu.toolbox.agent.ProactiveBubbleHost
import com.xjtu.toolbox.agent.rememberAndroidAgentVoiceInput
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.home.AppServices
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.shell.ShellBarAccessoryForm
import com.xjtu.toolbox.ui.components.PidaiPageContext
import com.xjtu.toolbox.ui.glass.GLASS_BAR_HEIGHT
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 小岱球的全局一份（#557）：大半屏面板开没开、按住说话的语音。底栏右边的球和子页上的悬浮球共用它，
 * 面板挂在导航最外层，子页上也能弹。点球、说话时把用户正在看的那一页带给小岱（见 [PidaiPageContext]）。
 */
@Stable
internal class PidaiStage(
    val voice: AgentVoiceInput,
    private val context: Context,
    /** 没登记快照的子页：至少告诉小岱是哪一页。 */
    private val routeTitle: () -> String?,
    /** 减少动效：不播转场，直接打开。 */
    private val reducedEffects: () -> Boolean,
    /** 截图用的窗口。 */
    private val window: () -> android.view.Window?,
    /** 此刻这页不许截图（账号页这类）。 */
    private val noScreenshot: () -> Boolean,
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 播转场，等这一页收进球里再打开小岱。 */
    private fun handOff(open: () -> Unit) {
        if (reducedEffects()) {
            open()
            return
        }
        handoffs++
        main.postDelayed(open, com.xjtu.toolbox.agent.HANDOFF_OPEN_MS.toLong())
    }

    var hover by mutableStateOf(false)

    /** 「这一页交给小岱」转场的序号，每交一次加一（见 [PidaiHandoffFx]）。 */
    var handoffs by mutableIntStateOf(0)
        private set

    /** 两颗球各自的位置：转场往当前看得见的那颗里收。 */
    var barOrb: Rect? = null
    var floatingOrb: Rect? = null

    /** 没登记的页最近一次从界面读到的文字：(页名, 文字)。工具在后台线程重读这页时用它。 */
    private var scanned: Pair<String, String>? = null

    /** 这页上有密码框：不读文字、不截图。 */
    private var scannedPassword = false

    init {
        PidaiPageContext.fallbackTitle = routeTitle
        PidaiPageContext.fallbackText = { scanned?.takeIf { it.first == routeTitle() }?.second }
        // 截图缓存放 7 天就清
        Thread {
            val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            java.io.File(context.cacheDir, "pidai_page").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }.start()
    }

    /** 没登记的页：在主线程从界面的无障碍语义树里读一遍看得见的文字，存给 [PidaiPageContext.fallbackText]。 */
    private fun scanPage() {
        scannedPassword = false
        if (PidaiPageContext.current() != null) return
        val title = routeTitle() ?: return
        val read = PageTextReader.read(window())
        scannedPassword = read == PageTextReader.PASSWORD
        scanned = title to (if (scannedPassword) "（这页有密码输入，内容没有读取）" else read ?: return)
    }

    private fun page(): Pair<String, String>? {
        scanPage()
        return PidaiPageContext.describe()
    }

    /**
     * 点球：把这一页挂到对话的输入框上，附一张截图（进待发图片由用户确认），再打开小岱
     * （[open] 由调用方决定进小岱页还是弹大半屏）。有密码框的页、账号页、登记时说不能截的页不截。
     */
    fun tap(open: () -> Unit) {
        val entry = PidaiPageContext.current()
        val here = page()
        if (here == null) {
            open()
            return
        }
        val shootable = if (entry != null) entry.screenshotAllowed() else !scannedPassword && !noScreenshot()
        if (!shootable) {
            AgentPendingPrompt.fromPage(here.first, here.second)
            handOff(open)
            return
        }
        // 先截再播转场：转场画上去之前的那一帧才是用户看到的页面
        captureWindow { shot ->
            AgentPendingPrompt.fromPage(here.first, here.second, screenshot = shot)
            handOff(open)
        }
    }

    /** 点了页面气泡里的提议：带着这一页把这句直接问出去。 */
    fun ask(text: String, open: () -> Unit) {
        val here = page() ?: return
        AgentPendingPrompt.fromPage(here.first, here.second, asUserRequest(text))
        handOff(open)
    }

    /** 气泡是小岱的口吻（「要我帮你看看…吗？」），发出去要换成用户的口吻（「帮我看看…」），不然模型以为是它自己问的。 */
    private fun asUserRequest(offer: String): String =
        Regex("""^要我帮你(.+?)吗[？?]?$""").find(offer.trim())?.groupValues?.get(1)?.let { "帮我$it" } ?: offer

    /** 截当前窗口（PixelCopy，禁止截屏的窗口会失败），缩到 720 宽存 JPEG，主线程回调路径或 null。 */
    private fun captureWindow(done: (String?) -> Unit) {
        val win = window()
        val decor = win?.decorView
        if (win == null || decor == null || decor.width == 0 || decor.height == 0 || android.os.Build.VERSION.SDK_INT < 26) {
            done(null)
            return
        }
        val full = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(win, full, { result ->
                val path = if (result != android.view.PixelCopy.SUCCESS) null else runCatching {
                    val scale = (720f / full.width).coerceAtMost(1f)
                    val small = android.graphics.Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
                    val dir = java.io.File(context.cacheDir, "pidai_page").apply { mkdirs() }
                    val file = java.io.File(dir, "page_${System.currentTimeMillis()}.jpg")
                    file.outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
                    if (small !== full) small.recycle()
                    file.absolutePath
                }.getOrNull()
                full.recycle()
                done(path)
            }, main)
        }.onFailure {
            full.recycle()
            done(null)
        }
    }

    /** 按住：开始听；松手转写后带着这一页开新对话直接发。 */
    fun holdStart(open: () -> Unit) {
        val key = runCatching { com.xjtu.toolbox.agent.AgentConfigStore().load().deepSeekToolKey }.getOrDefault("")
        if (key.isBlank()) {
            Toast.makeText(context, "语音要用 DeepSeek Key：在小岱设置里填上就能按住说话", Toast.LENGTH_SHORT).show()
            return
        }
        // 按下那一刻的页面：说话期间页面可能还在变，以开口时看到的为准
        val here = page()
        ProactiveBubbleHost.clear()
        voice.begin(key) { text ->
            if (here != null) {
                AgentPendingPrompt.fromPage(here.first, here.second, text)
                handOff(open)
            } else {
                AgentPendingPrompt.set(text)
                open()
            }
        }
    }

    fun holdEnd(cancel: Boolean) = voice.end(cancel)
}

internal val LocalPidaiStage = staticCompositionLocalOf<PidaiStage> { error("PidaiStage 没装") }

@Composable
internal fun rememberPidaiStage(top: () -> AppRoute?, tabTitle: () -> String?): PidaiStage {
    val context = LocalContext.current
    val voice = rememberAndroidAgentVoiceInput()
    val reduced = androidx.compose.runtime.rememberUpdatedState(com.xjtu.toolbox.ui.components.LocalReducedEffects.current)
    return remember(voice) {
        PidaiStage(
            voice,
            context.applicationContext,
            // 带参数的深链路由（思源某门课、某个活动）和目录里那条不相等：先按相等找，再按路由类型找
            // 栈底主界面就报底栏当前那一格
            routeTitle = {
                val route = top()
                if (route == null) {
                    tabTitle()
                } else {
                    (AppServices.all.firstOrNull { it.route == route } ?: AppServices.all.firstOrNull { it.route::class == route::class })?.title
                }
            },
            reducedEffects = { reduced.value },
            window = {
                var c: Context? = context
                while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
                (c as? android.app.Activity)?.window
            },
            noScreenshot = { top() is AppRoute.Accounts },
        )
    }
}

/** 子页上不放悬浮球的路由：小岱页自己、整屏扫码。 */
private fun hidesOrb(route: AppRoute): Boolean = route in NO_ORB_ROUTES

/** 不放悬浮球的页：小岱页自己、整屏扫码、设置、小游戏（全屏玩，球会挡手）。 */
private val NO_ORB_ROUTES: Set<AppRoute> = setOf(
    AppRoute.Agent, AppRoute.QrScan, AppRoute.Settings,
    AppRoute.Games, AppRoute.Game2048, AppRoute.GameMerge, AppRoute.GameBlocks,
    AppRoute.GameHop, AppRoute.GameGomoku, AppRoute.GameGo, AppRoute.GameXiangqi,
)

/**
 * 导航最外层的那一份：子页上的悬浮球 + 大半屏小岱。
 * [top] 是栈顶路由，栈底主界面（有底栏球）时为 null。
 */
@Composable
internal fun PidaiStageHost(
    stage: PidaiStage,
    top: AppRoute?,
    loginState: AppLoginState,
    open: (AppRoute) -> Unit,
) {
    val imeShown = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    AnimatedVisibility(
        visible = top != null && !hidesOrb(top) && !stage.hover && !imeShown,
        enter = fadeIn() + scaleIn(initialScale = 0.6f),
        exit = fadeOut() + scaleOut(targetScale = 0.6f),
    ) {
        FloatingOrb(stage) { stage.hover = true }
    }
    PidaiHoverPanel(visible = stage.hover, onDismiss = { stage.hover = false }) {
        AgentRoutePage(
            loginState = loginState,
            onNavigate = { route ->
                stage.hover = false
                open(route)
            },
            onBack = { stage.hover = false },
        )
    }
    // 盖在大半屏上面：面板升起的同时看得见这一页被扫描、收进球里
    val onSubPage = top != null
    PidaiHandoffFx(stage.handoffs) { if (onSubPage) stage.floatingOrb else stage.barOrb }
}

/**
 * 子页右下的悬浮小岱球：拖着走，松手吸到最近的左右边，位置记住。点开大半屏，按住说话。
 */
@Composable
private fun FloatingOrb(stage: PidaiStage, onOpen: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pidai_orb", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val orb = with(density) { GLASS_BAR_HEIGHT.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val maxX = (constraints.maxWidth - orb - margin).coerceAtLeast(margin)
        val maxY = (constraints.maxHeight - orb - margin).coerceAtLeast(margin)
        val x = remember { Animatable(if (prefs.getInt("side", 1) > 0) maxX else margin) }
        val y = remember { Animatable(margin + (maxY - margin) * prefs.getFloat("y", 0.78f)) }
        // 屏幕转了、窗口变了：按记下的边和比例重新摆
        LaunchedEffect(maxX, maxY) {
            x.snapTo(if (prefs.getInt("side", 1) > 0) maxX else margin)
            y.snapTo(margin + (maxY - margin) * prefs.getFloat("y", 0.78f))
        }
        // 拖过一点距离才算拖：没过就是点或长按，交给球自己；长按说话时球会吃掉移动，这里不会跟着拖
        Box(
            Modifier
                .offset { IntOffset(x.value.roundToInt(), y.value.roundToInt()) }
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDrag = { change, delta ->
                            change.consume()
                            scope.launch {
                                x.snapTo((x.value + delta.x).coerceIn(margin, maxX))
                                y.snapTo((y.value + delta.y).coerceIn(margin, maxY))
                            }
                        },
                        onDragEnd = {
                            val right = x.value + orb / 2 > (maxX + orb) / 2
                            prefs.edit()
                                .putInt("side", if (right) 1 else -1)
                                .putFloat("y", ((y.value - margin) / (maxY - margin).coerceAtLeast(1f)).coerceIn(0f, 1f))
                                .apply()
                            scope.launch {
                                x.animateTo(if (right) maxX else margin, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
                            }
                        },
                    )
                },
        ) {
            PidaiOrb(
                form = ShellBarAccessoryForm.FLOATING,
                voice = stage.voice,
                onTap = { stage.tap(onOpen) },
                onHoldStart = { stage.holdStart(onOpen) },
                onHoldEnd = stage::holdEnd,
                onBoundsChange = { stage.floatingOrb = it },
            )
            PageHintBubble(stage, onOpen, onRight = { x.value > maxX / 2 })
        }
    }
}

/**
 * 每类页面第一次出现小岱球时，在球上方冒一句这页的提议（「要我帮你看看哪个教室适合吗？」），
 * 点一下就带着这页把这句问出去；7 秒后自己收起。每类页面只冒一次，记在本机。
 */
@Composable
private fun PageHintBubble(stage: PidaiStage, onOpen: () -> Unit, onRight: () -> Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pidai_orb", Context.MODE_PRIVATE) }
    val entry = PidaiPageContext.current()
    val hint = entry?.hint
    val title = entry?.title
    val state = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(title, hint) {
        state.targetState = false
        if (title == null || hint == null) return@LaunchedEffect
        val seen = prefs.getStringSet("hinted", emptySet()).orEmpty()
        if (title in seen) return@LaunchedEffect
        // 等页面落定、球出场完再冒
        kotlinx.coroutines.delay(900)
        if (stage.hover || stage.voice.phase != com.xjtu.toolbox.agent.VoicePhase.IDLE) return@LaunchedEffect
        prefs.edit().putStringSet("hinted", seen + title).apply()
        shown = title to hint
        state.targetState = true
        kotlinx.coroutines.delay(7000)
        state.targetState = false
    }
    // 开大半屏、开始说话时立刻收起
    if (stage.hover || stage.voice.phase != com.xjtu.toolbox.agent.VoicePhase.IDLE) state.targetState = false
    val content = shown ?: return
    if (!state.currentState && !state.targetState) return
    val right = onRight()
    val density = LocalDensity.current
    val provider = remember(right, density) {
        val gap = with(density) { 10.dp.roundToPx() }
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): IntOffset {
                val x = if (right) anchorBounds.right - popupContentSize.width else anchorBounds.left
                return IntOffset(
                    x.coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)),
                    (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0),
                )
            }
        }
    }
    androidx.compose.ui.window.Popup(popupPositionProvider = provider, properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
        AnimatedVisibility(
            visibleState = state,
            enter = fadeIn(androidx.compose.animation.core.tween(180)) +
                scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (right) 1f else 0f, 1f)),
            exit = fadeOut(androidx.compose.animation.core.tween(160)) +
                scaleOut(targetScale = 0.8f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (right) 1f else 0f, 1f)),
        ) {
            val accent = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (right) 18.dp else 4.dp, bottomEnd = if (right) 4.dp else 18.dp,
            )
            androidx.compose.foundation.layout.Row(
                Modifier
                    .widthIn(max = 250.dp)
                    .shadow(12.dp, shape)
                    .clip(shape)
                    .background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        state.targetState = false
                        stage.ask(content.second, onOpen)
                    }
                    .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                top.yukonga.miuix.kmp.basic.Text(
                    content.second,
                    style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2,
                    color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                top.yukonga.miuix.kmp.basic.Icon(
                    androidx.compose.material.icons.Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
