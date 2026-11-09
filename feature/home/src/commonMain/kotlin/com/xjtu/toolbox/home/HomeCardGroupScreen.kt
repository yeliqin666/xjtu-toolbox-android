package com.xjtu.toolbox.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.xjtu.toolbox.bulletin.BulletinNoticePanel
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.settings.SettingsStore
// 两个同名 revision：一个是本页布局自己的（com.xjtu.toolbox.home），一个是全应用设置那套
// （com.xjtu.toolbox.settings）。背景 / 材质 / 顶栏排法与经典版共用后者，故起别名区分。
import com.xjtu.toolbox.settings.HomeLayoutRevision as SettingsRevision
import com.xjtu.toolbox.ui.components.AppColorPicker
import com.xjtu.toolbox.ui.components.HeroAppearance
import com.xjtu.toolbox.ui.components.HeroAppearanceDialog
import com.xjtu.toolbox.ui.components.HeroAppearanceRevision
import com.xjtu.toolbox.ui.components.HeroCustomBackground
import com.xjtu.toolbox.ui.components.HeroCustomScrim
import com.xjtu.toolbox.ui.components.HeroForestBackground
import com.xjtu.toolbox.ui.components.HeroForestScrim
import com.xjtu.toolbox.ui.components.HeroPixelBackground
import com.xjtu.toolbox.ui.components.HeroPixelScrim
import com.xjtu.toolbox.ui.components.JiaoxiaoxiAvatar
import com.xjtu.toolbox.ui.components.LiquidBackground
import com.xjtu.toolbox.ui.components.LocalPageVisible
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.components.rememberLiquidMood
import com.xjtu.toolbox.ui.glass.LocalGlassStyle
import com.xjtu.toolbox.ui.glass.progressiveBlurBar
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.ui.theme.Editorial
import com.xjtu.toolbox.ui.theme.EditorialRule
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import com.xjtu.toolbox.ui.theme.serviceColor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 首页「卡片组」布局——**dev 那套「小组件」就是这一套的前身**，两者已合成一份。
 *
 * 版面从「刊头 + 头卡 + 一堆分类卡」改成「刊头 + 头卡 + **可编辑的卡片组**」：
 * 每组是一片组件网格，组件的大小、位置、所属组都能在编辑态里拖。
 * 「常用功能」不再是特权区，它就是第一组（需求 §2.1）。
 *
 * 合并时从小组件那套搬过来的能力：材质四态里的纯色 / 渐变薄底（§1）、
 * 一镜到底接线（`HomeTileBoard` 的 `tileOpen`、本文件的 `rowOf` 里 `onSeamlessClick`）、
 * 校园卡大卡的付款码（`HomeTileBoard` 的 `PaymentCodeOverlay`）。
 * 只剩「经典分类卡」作为回落，见 [HomeClassicScreen]；入口分派在 [HomeScreen]。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeCardGroupScreen(
    host: HomeHost,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior? = null,
    /** 悬浮底栏的总占位高度：底栏浮在内容之上，页面末尾得自己留出来。 */
    extraBottomPadding: Dp = 0.dp,
    /** 玻璃顶栏的高度：内容铺到顶栏下面，这段留白放进滚动内容里。经典风格为 0。 */
    contentTopPadding: Dp = 0.dp,
    /** 壳把整页上移的量（见 `ShellPageInsets.contentTopShift`）；顶栏遮罩按它补回屏幕顶边。 */
    contentTopShift: () -> Dp = { 0.dp },
    /**
     * 顶栏最左侧那个按钮（`:app` 是「扫一扫」）；null = 这一端没有。
     *
     * 打开「功能按钮统一右对齐」（`SettingsStore.homeTopActionsRight`，默认开）时这一端改成
     * 改造前的顶栏——左侧换成「交小西」头像、不画居中标题，所以这个槽位在那时不被调用；
     * 关掉才按本分支的排法画（扫码靠左、标题居中）。
     */
    topLeading: (@Composable () -> Unit)? = null,
    /** 顶栏右侧那一排（`:app` 是「搜索」+「通知」，扫码统一右对齐时也在这一排）。null = 这一端没有顶栏。 */
    topActions: (@Composable () -> Unit)? = null,
) {
    val honored = host.honoredCards
    // 用目录全量：图标配色按它在目录里的下标取，过滤会让配色挪位。
    val catalog = remember(host.audience) { AppServices.homeFor(host.audience) }
    // 配色随应用主题变化，不能放进 remember。
    val colorByKey = catalog.associate { it.route.id to serviceColor(it.route.id) }

    // ── 布局与主页设置 ──
    val layoutStore = remember { HomeLayoutStore() }
    // 背景 / 卡片材质 / 顶栏排法这三组**与经典版、小组件共用同一份落盘**（`SettingsStore`）：
    // 三套布局切来切去时，壁纸和玻璃不该跟着变；布局本身（格位、分组）、网格开关、折射子开关、
    // 背景不透明度才是卡片组独有的，留在 `HomeLayoutStore` 里。
    val settings = remember { SettingsStore() }
    val settingsRevision = SettingsRevision.current
    val revision = HomeLayoutRevision.current
    val editing = remember { HomeEditState() }
    val stored = remember(revision, host.audience, host.quickRoutes) {
        layoutStore.load(host.audience, host.quickRoutes)
    }
    val layout = editing.draft ?: stored
    val grid = layoutStore.grid
    val liquid = remember(settingsRevision) { settings.homeLiquidGlass }
    // 液态玻璃的折射子项：只在 liquid 为真时有意义，关掉省一层离屏位移采样（见 HomeLayoutStore）
    // 顶栏排法：开（默认）= 改造前那套（左侧「交小西」头像、三个圆钮统一在右、不画居中标题）；
    // 关 = 本分支那套（扫码靠左、标题绝对居中、右侧只留搜索与通知）
    val topActionsRight = remember(settingsRevision) { settings.homeTopActionsRight }
    // 背景样式：主题底色 / 主题色渐变 / 自定义图片（取值与经典版同一套字符串）
    val backgroundMode = remember(settingsRevision) { settings.homeBackground }
    // 只有自定义图片这一档才去读那串 Base64（完整壁纸能有几百 KB）：其余两档读了也没人用，
    // 别让每次设置变更都白扛一次大字符串
    val backgroundImage = remember(settingsRevision, backgroundMode) {
        if (backgroundMode == HomeLayoutStore.BACKGROUND_IMAGE) settings.homeWallpaperImage else ""
    }
    val backgroundBlur = remember(settingsRevision) { settings.homeWallpaperBlur }
    val backgroundOpacity = remember(revision) { layoutStore.backgroundOpacity }
    // 卡片圆角（主页设置里那条滑条）；默认 22dp，实际还会被「边长 × 12」夹住
    val cardCorner = remember(revision) { layoutStore.cardCorner }.dp

    /**
     * 背景上**真的有壁纸可糊**吗：只有「自定义图片」且图还在时才是。
     *
     * 液态玻璃在纯色 / 渐变上把白费的离屏采样都关掉：模糊（`blur`，糊不出信息）与折射
     * （`lens`，折的是一张纯色底，看不出弯光）——活力（光感）、高光、轮廓、投影仍然保留，
     * **这一档还不铺底色**（卡片完全透明，只剩轮廓高光与投影，见 `HomeTileFaceBox`；
     * 染色只留在角标 `CountBadge`）与 `LocalHomeGlassBlur`。顶栏那条模糊带不受影响：它采的是
     * **内容层**，纯色下也照样有内容可糊。
     */
    val hasWallpaper = backgroundMode == HomeLayoutStore.BACKGROUND_IMAGE && backgroundImage.isNotEmpty()
    /**
     * 壁纸被自己那条「模糊程度」糊到没有细节了（≥ [SettingsStore.HOME_WALLPAPER_BLUR_HIDES_GLASS]）。
     *
     * 那时卡片能折 / 能糊的东西也已经被抹平：再跑一遍模糊与折射，画出来的和「薄色底」几乎
     * 分不出，而这两层是全页最贵的。所以按纯色 / 渐变同一套处理（2026-11 按需求补的这一档）。
     */
    val wallpaperBlurredOut = hasWallpaper && backgroundBlur >= SettingsStore.HOME_WALLPAPER_BLUR_HIDES_GLASS
    /**
     * 卡片那层玻璃**还能不能看出模糊 / 折射**：有壁纸、且壁纸还有细节。
     *
     * 卡片与幽灵块采的是**背景层**，所以这个值同时决定「糊不糊」与「折不折」——**没有用户开关**
     * （2026-11 维护者把「开启折射」那项整个删了）：有细节就一整套上，没有就不采样，
     * 免得把「省电」这件事交给用户去猜。
     * 顶栏圆钮、悬浮球、组删除钮采的是**内容层**（卡片、文字都在里面），那里永远有东西可折，
     * 所以它们恒采样 + 恒折射（见 `HomeBarIconButton` / `GroupDeleteButton`）。
     */
    val glassHasDetail = hasWallpaper && !wallpaperBlurredOut
    // 背景层的离屏层：**只在真有人要采时才录**（`layerBackdrop` 是一次整屏离屏渲染）。
    // 卡片在纯色 / 渐变上（或壁纸已糊成色块）不采样：`drawBackdrop(sampling = false)` 跳过「录采样层」
    // 那一段、也不建离屏层，这一档就是为省性能（见 `HomeTileFaceBox`）。
    //
    // 2026-11 这一档**去掉了 `|| editing.on`**：以前编辑态为了组标题行那个 30dp 的删除圆钮、
    // 还有拖拽幽灵块能糊一下，在没有壁纸细节的背景上也强行录这一层——等于为了几个小圆钮每帧
    // 多渲染一整屏。现在它们跟卡片同一条规则：没细节可糊就走薄色兜底（编辑态因此少了一整屏离屏渲染）。
    val recordTileLayer = liquid && glassHasDetail

    /** 改当前布局：编辑态改草稿（点「对勾」才落盘），其余情况直接落盘。 */
    fun update(block: (HomeLayout) -> HomeLayout) {
        if (editing.on) {
            editing.draft = block(editing.draft ?: stored)
        } else {
            layoutStore.save(block(stored))
        }
    }

    /**
     * 退出编辑态并**落盘**：「对勾」、返回键、切到别的页签都走这条。
     *
     * 不落盘就直接退出，用户摆的东西会静悄悄丢掉；反过来，退出时不写盘而是等下次进编辑态
     * 再问一遍，就成了「摆完还得记得点保存」。所以这里一次写清楚：有草稿就存，没有就只是关掉。
     */
    fun saveAndQuitEdit() {
        if (!editing.on) return
        editing.draft?.let { layoutStore.save(it) }
        editing.cancel()
    }

    /** 编辑态进度（0 常规 → 1 编辑），只在绘制阶段读。 */
    val editFade = animateFloatAsState(if (editing.on) 1f else 0f, tween(260), label = "editFade")

    val pageVisible = LocalPageVisible.current
    // 返回键：编辑态优先（这一下不退出页面），退出时自动保存
    BackHandler(enabled = editing.on && pageVisible) { saveAndQuitEdit() }
    // 切到别的页签：这一页还在组合里，只是不可见了 —— 这时就退出编辑并保存。
    // 只在「变成不可见」那一下动一次，可见时不做事（不做任何周期性工作）。
    LaunchedEffect(pageVisible) {
        if (!pageVisible) saveAndQuitEdit()
    }
    // 页面整个被移出组合（宿主直接销毁这一页）：补一次落盘。
    // 只写盘、不碰 Compose 状态（dispose 阶段改 state 没有意义，还可能触发多余的组合）。
    DisposableEffect(Unit) {
        onDispose {
            if (editing.on) editing.draft?.let { layoutStore.save(it) }
        }
    }

    fun rowOf(service: AppService, stat: HomeStat?): HomeServiceRow {
        val id = service.route.id
        return HomeServiceRow(
            key = service.route.id,
            icon = HomeIcons.of(service.route),
            title = service.title,
            subtitle = service.subtitle,
            color = colorByKey[id] ?: Color.Unspecified,
            onClick = { host.onRoute(service.route) },
            // 一镜到底：把「从这一块出发」的矩形交给宿主，ROM 支持 SharedViewKit 时走系统转场，
            // 不支持就落回上面的 onClick（见 HomeTileBoard 的 tileOpen）
            onSeamlessClick = { source -> host.onRouteSeamless(service.route, source) },
            stat = stat?.value,
            statDetail = stat?.detail,
            onLink = host::onOpenLink,
        )
    }

    // 功能状态就地附在组件里，不另设展示区。这里不 remember：宿主对象每次重组都是新的，
    // 记住它会把旧的 onClick 闭包一起留住（切账号后点了没反应就是这么来的）。
    // 这一端打不开的入口直接不进 faces，它那些块就自然不画（桌面、Web 各自声明能开哪些）。
    val openable = host.openableRouteIds
    val faces: Map<String, HomeServiceRow> = catalog
        .filter { openable == null || it.route.id in openable }
        .associate { service ->
            val glance = host.glances.takeIf { HomeCard.GLANCE in honored }
                ?.firstOrNull { it.route?.id == service.route.id && it.route != AppRoute.Schedule }
            val id = service.route.id
            val withStats = HomeCard.CATEGORY_STATS in honored
            // 速览格没接的端（或校园卡、校历这类让给速览格的）照样取全量状态，大卡才有东西可放
            val stat = HomeCards.statOf(id, host.stats) ?: host.stats[id]?.takeIf { it.value.isNotBlank() }
            val value = if (withStats) {
                stat?.value ?: glance?.let { g -> g.number?.let { yuanText(it.toFloat()) } ?: g.value }
            } else null
            id to rowOf(service, value?.let { HomeStat(it, stat?.detail ?: glance?.caption) }).copy(
                tone = glance?.tone ?: HomeTone.NORMAL,
                lines = if (withStats) HomeCards.linesOf(id, host.stats) else emptyList(),
            )
        }

    val quickVisible = HomeCard.QUICK_ACTIONS in honored

    /** 组的可见性规则：渲染列表（预览）与几何表（数据）共用同一份，两边的组顺序才永远一致。 */
    fun groupVisible(group: HomeGroup): Boolean =
        HomeCard.SERVICE_GRID in honored &&
            (group.id != HomeLayoutDefaults.QUICK_GROUP || quickVisible) &&
            (editing.on || group.tiles.any { faces.containsKey(it.routeId) })
    // 数据版布局的可见组：几何表与组排序的输入。拖拽中数据不变（预览不改数据），命中区间恒定
    val visibleGroups = layout.groups.filter(::groupVisible)

    val scroll = rememberScrollState()
    // 有宿主给的按钮才有折叠顶栏（桌面、Web 的壳有自己的顶栏）
    val collapsible = topActions != null || topLeading != null
    val masthead = HomeCard.GREETING in honored
    val barPx = with(LocalDensity.current) { BAR_HEIGHT.toPx() }
    var mastheadPx by remember { mutableIntStateOf(0) }
    // 刊头先让出一栏高度、再画在顶栏下面，所以滑完整个刊头才算折叠完成
    val rangePx = if (masthead && mastheadPx > 0) mastheadPx.toFloat().coerceAtLeast(barPx) else barPx
    val progress = { (scroll.value / rangePx).coerceIn(0f, 1f) }
    // 背景层的主题底色（没有壁纸时顶栏与卡片透出来的就是它）
    val barSurface = MiuixTheme.colorScheme.surface
    // 「主题色渐变背景」的两个输入：主题色 + 明暗（深色下要更浓一点才看得出）
    val barPrimary = MiuixTheme.colorScheme.primary
    val barDark = LocalIsDarkTheme.current
    val glass = LocalGlassStyle.current && collapsible

    // 卡片柔光玻璃的采样源：背景层（主题底色 + 壁纸）另录一份**预先糊好**的副本，所有卡片共用。
    // 背景不随滚动变，带 RenderEffect 的这一层在 HWUI 里会被单独缓存成一张纹理：滚动时卡片只是
    // 平移着贴它，不再每帧每张卡各跑一遍模糊与折射（以前滚动卡顿的主因）。
    val frostRadius = with(LocalDensity.current) { FROST_RADIUS.toPx() }
    val frostLayer = rememberGraphicsLayer()
    remember(frostLayer, frostRadius) { frostLayer.renderEffect = BlurEffect(frostRadius, frostRadius, TileMode.Clamp) }
    val tileBackdrop = rememberLayerBackdrop(frostLayer, onDraw = FrostOnDraw)

    // 顶栏模糊带与顶栏圆钮的采样源：**这一页的内容层**（背景 + 滚动内容），
    // 糊出来的是「此刻压在它下面的东西」，而不是固定不动的壁纸。
    val pageBackdrop = rememberLayerBackdrop(cache = true)

    // 有消费者才录（`layerBackdrop` 是一次整屏离屏渲染）：玻璃风格的模糊带、液态圆钮（编辑态没有宿主顶栏也画）
    val recordPageLayer = glass || (liquid && (collapsible || editing.on))

    val session = remember { HomeDragSession() }
    val density = LocalDensity.current
    // 长按「拿起」的触感：走主题里那个被「振动开关」包过的 LocalHapticFeedback（见 Theme.kt）
    val haptic = LocalHapticFeedback.current
    var pageSize by remember { mutableStateOf(Offset.Zero) }

    /**
     * 本场拖拽里**已经给哪一组多开了几行**（组 id → 多出来的行数）。
     *
     * 卡片拖到某一组的最后一个格之外时，要能在那儿新起一行接住它（需求：拖到本组底端可以
     * 再建一行）。做法是「按需长高」而不是「先把预算放宽」——放宽成对**所有**组都多一行的话，
     * 每场拖拽都会凭空多出一整行空行，收起时整页又要跳一下。
     *
     * 只增不减：行数一旦长出来，本场就不再收回去。这一条是防振荡的关键——手指探出组底就
     * 长一行，长完之后手指自然回到组内，若这时又收回去，「长 → 收 → 再长」就成了每帧来回摆。
     * 多出来的行若最终没被占住，松手时由「收空行」（`HomeLayout.finishRows`）统一压掉。
     */
    var extraRows by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }

    /**
     * 各组在**页面坐标**里的顶边（组 id → y），由每组外框的 `onGloballyPositioned` 每帧刷新。
     * 只服务「进编辑态不跳」这一件事：拿它当滚动锚点（见下面的 `LaunchedEffect(editing.on)`）。
     */
    val groupTops = remember { mutableMapOf<String, Float>() }

    /**
     * 「**上一种**编辑状态的布局帧里」各组的位置（组 id → 页面 y）。
     *
     * 进/出编辑态都要把滚动位置保住：进的时候每组补标题行、空组显现，出的时候反过来，内容在
     * 视口之上长高/变矮，画面就会错位。这里按状态各记一份实测值，切换时拿它当锚点。
     */
    val idleTops = remember { mutableMapOf<String, Float>() }
    val editTops = remember { mutableMapOf<String, Float>() }

    /**
     * 两种状态各自**真正渲染出来的那一份组列表**（引用级快照，不复制数据）。
     *
     * 切换的头一帧要用它算出「视口之上会多出/少掉多少高度」，好在布局之前就把滚动补好——
     * 等布局量完再补，那一帧的错位就已经被画出来了（就是「闪一下」）。
     */
    var idleGroups by remember { mutableStateOf<List<HomeGroup>>(emptyList()) }
    var editGroups by remember { mutableStateOf<List<HomeGroup>>(emptyList()) }

    /** 非编辑态那份里**标题为空**的组 id：它们不画标题行（§6），预测高度必须扣掉。 */
    var idleHeaderless by remember { mutableStateOf<Set<String>>(emptySet()) }
    if (!editing.on) {
        // 内容相等时不会触发重组（List / Set 按结构比较），所以这里每帧赋值是安全的
        idleGroups = visibleGroups
        idleHeaderless = visibleGroups.filter { it.title.isBlank() }.mapTo(mutableSetOf()) { it.id }
    } else {
        editGroups = visibleGroups
    }

    val scope = rememberCoroutineScope()

    // ── 拖拽编排：快照几何 → 每帧幂等重算 → 松手固化一次（根基见 HomeDragSession 类头） ──
    val tileGapPx = with(density) { HomeTileGap.toPx() }
    val groupGapPx = with(density) { GROUP_GAP.toPx() }
    val headerPx = with(density) { GROUP_HEADER_HEIGHT.toPx() }
    val firstGapPx = with(density) { FIRST_GROUP_GAP.toPx() }
    val handlePx = with(density) { RESIZE_HANDLE_TOUCH.toPx() }
    val removePx = with(density) { REMOVE_TOUCH.toPx() }

    // derivedStateOf 的 lambda 只在依赖的 State 变化时重跑；layout / grid / visibleGroups
    // 都是普通局部值，直接读不会被追踪，所以经 rememberUpdatedState 读它们的**最新值**
    // （旧代码同样这么做）。落盘、开关网格、改草稿时 liveLayout 才会跟着刷新。
    val currentLayout = rememberUpdatedState(layout)
    val gridState = rememberUpdatedState(grid)

    /**
     * 组区几何表：命中与落点的唯一依据。拖拽中按起手快照算，空闲时按当前可见组算。
     *
     * 这个 State 对象必须整场稳定：下面 targetGroup / liveLayout 等 derivedStateOf 只在首次组合
     * 时捕获它一次。所以可见组经 rememberUpdatedState 读、extraRows 本身是 State，都不进 key——
     * 进了 key 就会换新对象，下游还读旧的那份（多开行之后落点与渲染对不上）。
     */
    val visibleGroupsState = rememberUpdatedState(visibleGroups)
    val dragGeometry by remember(tileGapPx, groupGapPx, headerPx, firstGapPx) {
        derivedStateOf {
            buildBoardGeometry(
                groups = session.baseGroups.ifEmpty { visibleGroupsState.value },
                left = 0f,
                top = 0f,
                width = session.boardWidth,
                gapPx = tileGapPx,
                headerPx = headerPx,
                groupGapPx = groupGapPx,
                firstGapPx = firstGapPx,
                // 给「已经多开了行」的那几组补上高度，命中区间与渲染才继续相等
                extraRows = extraRows,
            )
        }
    }

    /**
     * 手指的**板内坐标** = 页面根局部坐标 − 组区容器左上角。
     *
     * 只做这一处减法，而且减的是**实测**的 `session.boardOrigin`（随滚动一起动），
     * 所以自动滚动时手指不动也能正确换组、缩放尺寸也会自然跟着变大。
     *
     * 旧实现存的是「内容坐标 = 页面坐标 + scrollY」：那条链要求 pageOrigin 已回填、
     * scrollY 恰好同帧，任何一环错位都会让整份几何表整体偏移（表现：一拖就跑到上方老远）。
     */
    val pointerBoard by remember {
        derivedStateOf { session.pointer - session.boardOrigin }
    }

    /**
     * **幽灵左上角**的板内坐标 = 手指板坐标 − 抓取偏移。
     *
     * 落点必须按它算，不能按手指算：手指按在块的中间，用手指所在的格当「块左上角该落的格」
     * 会整体偏掉一格左右（2×1 的块按中心抓，偏半格横向 + 半格纵向，取整后就是一格）。
     * 幽灵画在 `pointer − grab`，所以用它算格才能「所见即所得」。
     * grab 是同一坐标系里的差值，−boardOrigin 会抵消，整条链与滚动无关。
     */
    val ghostBoard by remember {
        derivedStateOf { pointerBoard - session.grab }
    }

    /**
     * 目标组：纯函数。组间空隙对半分之后各组的命中区间**首尾相接不重叠**，
     * 所以不需要「记住当前组」的滞回，也就不存在边界上来回切。
     */
    val targetGroup by remember(groupGapPx) {
        derivedStateOf {
            if (!session.draggingTile) {
            null
        } else {
            // 用幽灵**中心**判组：2×2 的块上半截还在上一组时，不该判给上一组
            val half = Offset(
                session.sizeCells.x * dragGeometry.colUnit / 2f,
                session.sizeCells.y * dragGeometry.rowUnit / 2f,
            )
            dragGeometry.groupAt(ghostBoard + half, groupGapPx / 2f)
        }
        }
    }

    /**
     * 拖动中的**行数预算**：每组锁死成起手快照的行数（**加上**本场已经给它多开的行）。
     *
     * 这是「落点不飘」的物理保证——有了它，整场拖拽里除了「用户明确要求多开的那几行」以外
     * 没有任何一个组会变高，于是 `dragGeometry`（按快照算的命中区间）与渲染出来的组位置
     * **每一帧都严格相等**。组一变高，下面的组就被推下去，而命中还按快照的旧位置算，手指与
     * 格子的对应关系当场失效，然后落点改布局、布局改高度、高度又改落点——那就是旧实现振荡
     * 的回路。
     *
     * 缩放不吃预算（组跟着变高是用户的明确意图，而且缩放全程不需要命中判定）。
     */
    val rowBudget by remember {
        derivedStateOf {
            if (!session.draggingTile) {
                null
            } else {
                session.baseGroups.associate { group ->
                    val base = HomeGridMath.contentBottom(group.tiles).coerceAtLeast(1f)
                    group.id to base + (extraRows[group.id] ?: 0f)
                }
            }
        }
    }

    /**
     * 预测「切换编辑态会让视口之上的内容长高（进）或变矮（出）多少」（px，正数 = 内容下移）。
     *
     * 编辑态会给每一组补标题行、把空组与「组件都打不开」的组也显示出来，退出时反过来——
     * 这些都在视口之上，内容一变，画面就整体错位。等布局量完再补，那一帧的错位已经被画出来
     * （闪一下），所以这里用**同一套几何函数**（[buildBoardGeometry]）把「切换前 / 切换后」
     * 锚点组的顶边差算出来，在布局之前就补进滚动。
     *
     * 锚点 = 切换前视口里最靠上的那一组；只比它的顶边，因为插入都发生在它之上（它自己的标题行
     * 在顶边之下，不影响顶边）。万一预测有误差，后面那条逐帧锚点循环会兜底修正。
     */
    fun predictAnchorFix(): Float {
        val entering = editing.on
        val beforeGroups = if (entering) idleGroups else editGroups
        if (beforeGroups.isEmpty()) return 0f
        val beforeHeaderless = if (entering) idleHeaderless else emptySet()
        val afterHeaderless = if (entering) emptySet() else visibleGroups.filter { it.title.isBlank() }.mapTo(mutableSetOf()) { it.id }
        val beforeTops = if (entering) idleTops else editTops
        val anchor = beforeTops.entries
            .filter { entry -> beforeGroups.any { it.id == entry.key } }
            .minByOrNull { abs(it.value) }
            ?: return 0f
        fun topOf(groups: List<HomeGroup>, headerless: Set<String>): Float? = buildBoardGeometry(
            groups = groups,
            left = 0f,
            top = 0f,
            width = session.boardWidth,
            gapPx = tileGapPx,
            headerPx = headerPx,
            groupGapPx = groupGapPx,
            firstGapPx = firstGapPx,
            headerlessIds = headerless,
        ).slot(anchor.key)?.top
        val before = topOf(beforeGroups, beforeHeaderless) ?: return 0f
        val after = topOf(visibleGroups, afterHeaderless) ?: return 0f
        return after - before
    }

    /**
     * 待应用的「切换编辑态预测补偿」（px）。用普通数组当一次性槽位（不是 State）：组合期写它、
     * [SideEffect] 读它，两处都不需要订阅——用 State 反而会在组合期写状态、引起一次额外重组。
     */
    val anchorFixSlot = remember { floatArrayOf(0f) }
    // remember(editing.on) 的 calculation 只在编辑态切换的那一次组合里跑，且早于布局
    remember(editing.on) {
        anchorFixSlot[0] = predictAnchorFix()
    }
    // SideEffect 在「组合已提交、这一帧还没布局」时执行：现在改 scroll，布局与绘制用的就是
    // 补好的位置，中间不会有一帧错位被画出来。
    SideEffect {
        val fix = anchorFixSlot[0]
        if (fix != 0f) {
            anchorFixSlot[0] = 0f
            scroll.dispatchRawDelta(fix)
        }
    }

    // 「恢复默认分组和布局」：抽屉里确认之后立刻生效——**不退出编辑态**，但手上的草稿换成新的
    // 默认布局（抽屉自己已经关了，见 HomeSettingsBody）。只看 reset 这一个信号，不看普通
    // 的 revision：那个在改背景 / 开关网格时也会动，跟着换会把用户正摆着的草稿冲掉。
    val resetRevision = HomeLayoutResetRevision.current
    LaunchedEffect(resetRevision) {
        if (resetRevision == 0 || !editing.on) return@LaunchedEffect
        editing.draft = layoutStore.load(host.audience, host.quickRoutes)
    }

    /** 缩放中的实时尺寸（格）：按「手指内容坐标 − 起手内容坐标」的**绝对差值**重算。 */
    val resizeCells by remember {
        derivedStateOf {
            if (session.mode != HomeDragMode.RESIZE) return@derivedStateOf null
            val g = dragGeometry
            if (!g.ready) return@derivedStateOf null
            val grid = gridState.value
            val delta = pointerBoard - session.resizeStartContent
            Offset(
                HomeGridMath.clampSpan(session.resizeStartCells.x + delta.x / g.colUnit, grid),
                HomeGridMath.clampSpan(session.resizeStartCells.y + delta.y / g.rowUnit, grid),
            )
        }
    }

    /** 组排序：目标下标（越过相邻组中线才换序，中线按快照算，所以是静止的、不抖）。 */
    val reorderTarget by remember(groupGapPx) {
        derivedStateOf {
            if (session.mode != HomeDragMode.GROUP) return@derivedStateOf -1
            val g = dragGeometry
            val top = g.slots.getOrNull(session.groupIndex)?.top ?: return@derivedStateOf -1
            val offset = pointerBoard.y - session.groupGrabY - top
            HomeGridMath.reorderTargetIndex(g.heights(groupGapPx), session.groupIndex, offset)
        }
    }

    /** 组排序：其它组各自要让位多少（px）。被拖组自己不在表里——它跟手。 */
    val reorderShiftMap by remember(groupGapPx) {
        derivedStateOf {
            if (session.mode != HomeDragMode.GROUP || reorderTarget < 0) return@derivedStateOf emptyMap<String, Float>()
            val g = dragGeometry
            val ids = g.slots.map { it.groupId }
            HomeGridMath.reorderShifts(g.heights(groupGapPx), session.groupIndex, reorderTarget)
                .mapKeys { (i, _) -> ids[i] }
        }
    }

    /**
     * **渲染用的布局**：拖拽中每帧从快照重算，否则就是数据本身。
     *
     * 幂等（同一个手指位置反复算结果不变），而且**拖动中不写草稿**——草稿只在松手那
     * 一次写入。所以手势被系统抢走时不需要「回退临时状态」：草稿根本没被碰过。
     *
     * 组排序不在这里换序：被拖组跟手浮起、其它组按 [reorderShiftMap] 让位，都是画出来的，
     * 松手才 `moveGroupWithin` 落盘一次（Column 不是 Lazy 布局，没有 `animateItem()`
     * 那种自动补位，让位只能自己算——但它算的是纯函数，不回读任何位置）。
     */
    val liveLayout by remember {
        derivedStateOf {
            val base = session.base
            val current = currentLayout.value
            val grid = gridState.value
            when {
                base == null -> current
                session.mode == HomeDragMode.RESIZE -> {
                    val id = session.tileId
                    val cells = resizeCells
                    if (id == null || cells == null) current else base.resizeTile(id, cells.x, cells.y, grid)
                }
                session.draggingTile -> {
                    val id = session.tileId
                    val route = session.routeId
                    val target = targetGroup
                    val cell = if (target == null) null else dragGeometry.cellAt(target, ghostBoard)
                    when {
                        id == null || route == null || target == null || cell == null -> base
                        else -> base.moveTile(
                            id, target, cell.x, cell.y, grid,
                            if (grid) 0f else EDGE_LINK_CELLS, rowBudget,
                        )
                    }
                }
                else -> current
            }
        }
    }

    /**
     * 维护「本场拖拽里各组多开了几行」（[extraRows]）。三条规则，都是当前状态的纯函数：
     *
     * 1. **真用到的必须留着**：实时布局里某组已经占到预算之外的行，就把它记下来。不然卡片
     *    明明摆在新行上，预算下一帧一收，它就被挤回去了。
     * 2. **没用到的当帧就收**：反过来说，实时布局已经不占那些行了（让位 / 收空行把卡片提回
     *    原来的行），多出来的行必须立刻消失——否则组底会留一条空行，直到松手才塌下去。
     * 3. **手指探出组底 → 把空位补出来**：幽灵块底边越过该组的预算就补上差的那几行；
     *    **网格模式下取整到整行**（空位随网格一格一格扩展），自由模式按实际差值（半格也行）。
     *
     * 写成幂等函数而不是「只增不减」，是因为第 2 条必须能回收；也不会振荡——cell.y 是相对
     * 组顶算的，组底多出几行不改变它，「有那一行」和「没有那一行」各自都是不动点。
     */
    fun syncExtraRows() {
        if (!session.draggingTile) {
            if (extraRows.isNotEmpty()) extraRows = emptyMap()
            return
        }
        val base = session.baseGroups.associate {
            it.id to HomeGridMath.contentBottom(it.tiles).coerceAtLeast(1f)
        }
        val liveRows = liveLayout.groups.associate {
            it.id to HomeGridMath.contentBottom(it.tiles).coerceAtLeast(1f)
        }
        val next = mutableMapOf<String, Float>()
        base.forEach { (id, rows) ->
            val used = (liveRows[id] ?: rows) - rows
            if (used > 0.01f) next[id] = used
        }
        val group = targetGroup
        if (group != null) {
            val rows = base[group]
            val cell = dragGeometry.cellAt(group, ghostBoard)
            if (rows != null && cell != null) {
                val budget = rows + (next[group] ?: 0f)
                val need = cell.y + session.sizeCells.y
                if (need > budget + 0.05f) {
                    val raw = need - budget
                    val add = if (gridState.value) ceil(raw) else raw
                    next[group] = maxOf(next[group] ?: 0f, add)
                }
            }
        }
        if (next != extraRows) extraRows = next
    }

    // ── 松手结算：幽灵块的吸附/回弹动画，与组排序的归位补偿 ──
    var ghostSettling by remember { mutableStateOf(false) }
    var ghostEpoch by remember { mutableIntStateOf(0) }
    val ghostSettle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var reorderSettleId by remember { mutableStateOf<String?>(null) }
    var reorderEpoch by remember { mutableIntStateOf(0) }
    val reorderSettleAnim = remember { Animatable(0f) }

    /**
     * 卡片松手：**这一次**才写草稿，而且放开行数预算（允许在组末尾新增一行）并收掉空行。
     *
     * 幽灵吸附的落点像素按**固化后的布局**重算：收空行会让块整体上移，用拖动中的预览
     * 几何算就会差一截。这一步不是打补丁——固化后的布局确实和预览不同（多了压缩），
     * 而它同样是纯函数，不回读任何节点。
     */
    fun settleTileDrop() {
        val id = session.tileId ?: return
        val base = session.base ?: return
        // 落点必须在**此刻**的布局上算：自动滚动可能刚把页面又挪过一截，而 targetGroup /
        // ghostBoard 都是派生量，喂给它们的原点要是旧值，落点就会整格偏出去（松手落到别的
        // 组、或挤到别的行）。先刷原点，再读派生量——读一次就是新值。
        session.refreshBoardFromCoordinates()
        val route = session.routeId
        val target = targetGroup
        val cell = if (target == null) null else dragGeometry.cellAt(target, ghostBoard)
        val from = session.ghostOrigin
        val startGhost = session.startGhost

        // 落盘必须与预览用**同一套行数预算**：预览带着 rowBudget，落盘若不带就会在落位时多
        // 认几行、整组上移，于是「落到的位置和预览看到的不一样、还可能挤在一起」。
        // （空行由 finishRows 两边一起收，所以收空行不会让落盘和预览分叉。）
        val committed = if (target != null && cell != null && route != null) {
            base.moveTile(id, target, cell.x, cell.y, grid, if (grid) 0f else EDGE_LINK_CELLS, rowBudget)
        } else {
            null
        }

        // 落点的内容坐标 → 视口坐标（幽灵画在不滚动的覆盖层上）
        // 拖拽中幽灵块被抬高（GhostDragLift），松手时从「抬起位」落回真实格——吸附动画
        // 的起点要跟拖拽中看到的抬起位一致，否则会瞬间下跳。
        val destination = if (committed != null) {
            val placed = committed.locate(id)
            val settled = buildBoardGeometry(
                groups = committed.groups.filter(::groupVisible),
                left = 0f,
                top = 0f,
                width = session.boardWidth,
                gapPx = tileGapPx,
                headerPx = headerPx,
                groupGapPx = groupGapPx,
                firstGapPx = firstGapPx,
            )
            placed
                ?.let { (g, t) -> settled.cellOrigin(g.id, t.x, t.y) }
                ?.let { it + session.boardOrigin }
        } else {
            null
        }

        // 落盘与切结算态同步执行，不进协程、不被 epoch 守卫跳过
        if (committed != null) update { committed }
        session.release()

        // 剩下只是幽灵的吸附/回弹动画（可被打断；打断就交给下一场手势的 finish 清场）。
        val epoch = ++ghostEpoch
        scope.launch {
            ghostSettle.snapTo(from)
            ghostSettling = true
            try {
                ghostSettle.animateTo(destination ?: startGhost, HomeTileMotionOffset)
            } finally {
                if (ghostEpoch == epoch) {
                    ghostSettling = false
                    session.finish()
                }
            }
        }
    }

    /** 缩放松手：固化实时尺寸。没有幽灵，也就没有收尾动画。 */
    fun settleResizeDrop() {
        val id = session.tileId
        val base = session.base
        // 同一个理由：resizeCells 按「手指板坐标 − 起手内容坐标」算，原点过期会让固化下来的
        // 尺寸比预览小/大一截（滚过一段之后尤其明显）
        session.refreshBoardFromCoordinates()
        val cells = resizeCells
        if (id != null && base != null && cells != null) {
            update { base.resizeTile(id, cells.x, cells.y, grid) }
        }
        session.finish()
    }

    /**
     * 手势被系统抢走，或按下后没拖过 slop（其实是点击）：整场作废。
     *
     * 拖动中**从不写草稿**，所以这里不需要恢复任何数据——清掉会话就够了
     * （AOSP Launcher3 要 `revertTempState()` 是因为它拖动中就改了 CellLayout）。
     */
    fun abandonDrag() {
        session.abort()
    }

    /**
     * 组排序被取消（含没拖过 slop 的点击、或落点就是原位）：数据不动，跟手位移弹回原位。
     *
     * 声明在 [settleGroupDrop] **之前**——Kotlin 的局部函数必须先声明后使用（成员函数才
     * 可以任意顺序），否则编译器报 `Unresolved reference`。
     */
    fun cancelGroupDrop() {
        val id = session.groupId ?: run { session.abort(); return }
        // 跟手位移从指针板坐标来，原点过期会让「弹回原位」的起点偏出去一截
        session.refreshBoardFromCoordinates()
        val g = dragGeometry
        val groupTop = g.slots.getOrNull(session.groupIndex)?.top ?: 0f
        val liftNow = pointerBoard.y - session.groupGrabY - groupTop
        reorderSettleId = id
        val epoch = ++reorderEpoch
        scope.launch {
            reorderSettleAnim.snapTo(liftNow)
            session.release()
            reorderSettleAnim.animateTo(0f, HomeTileMotion)
            if (reorderEpoch == epoch) {
                reorderSettleId = null
                session.finish()
            }
        }
    }

    /**
     * 组排序松手：按目标下标换序，被拖组的跟手位移补成「手指位置 − 新槽位」再弹回 0。
     *
     * 三步（落盘、切成结算动画、值就位）都放在同一个协程步骤里，中间不会插入一帧——
     * 否则会出现「已经换了序、但位移还是旧值」的一帧跳变。
     *
     * 落点无效（没越过任何中线 / 原位）就当作取消，复用 [cancelGroupDrop]。
     */
    fun settleGroupDrop() {
        val id = session.groupId ?: return
        // 同 cancelGroupDrop：目标下标与归位补偿都按指针板坐标算，先刷原点
        session.refreshBoardFromCoordinates()
        val from = session.groupIndex
        val target = reorderTarget
        val g = dragGeometry
        val heights = g.heights(groupGapPx)
        val groupTop = g.slots.getOrNull(from)?.top ?: 0f
        val liftNow = pointerBoard.y - session.groupGrabY - groupTop
        if (target < 0 || target == from || from !in heights.indices) {
            cancelGroupDrop()
            return
        }
        val delta = if (target > from) {
            heights.subList(from + 1, target + 1).fold(0f) { a, b -> a + b }
        } else {
            -heights.subList(target, from).fold(0f) { a, b -> a + b }
        }
        // 几何下标是「可见组序列」的；落盘要映射回全量序列（被设置关掉的组原地不动）
        val order = g.slots.map { it.groupId }
        reorderSettleId = id
        val epoch = ++reorderEpoch
        scope.launch {
            reorderSettleAnim.snapTo(liftNow - delta)
            update { it.moveGroupWithin(id, order, target) }
            session.release()
            reorderSettleAnim.animateTo(0f, HomeTileMotion)
            if (reorderEpoch == epoch) {
                reorderSettleId = null
                session.finish()
            }
        }
    }

    /**
     * 手势落点是拖拽目标：按种类起手（快照、身份、抓取偏移一次定死）。
     *
     * [viewport] 是页面根局部坐标；板内坐标在这里现算一次，交给 session 当缩放/组排序的
     * 基准。几何表没就绪（还没量到组区宽度）时不接手——宁可这一下没反应，也不要拿一份
     * 空几何去起手（那正是「一拖就飞到老远」的来源）。
     */
    fun beginGesture(hit: HomeHit, viewport: Offset) {
        // 几何表没就绪（组区还没量到宽度）就不接手：宁可这一下没反应，也不要拿空几何起手
        if (!dragGeometry.ready || session.boardWidth <= 0f) return
        // 新的一场：上一场多开的行全部作废（多出来的行在那场松手时就已经被收空行压掉了）
        extraRows = emptyMap()
        // 原点已由手势层在起手那一刻现场刷新过（session.refreshBoardFromCoordinates），
        // 这里直接用，不再做二次猜测。
        val board = viewport - session.boardOrigin
        // 新手势顶掉上一场的结算动画
        ghostEpoch++
        reorderEpoch++
        ghostSettling = false
        reorderSettleId = null
        session.finish()

        val ids = visibleGroups.map { it.id }
        when (hit) {
            is HomeHit.Tile -> {
                val tile = layout.tile(hit.tileId) ?: return
                val tileOrigin = dragGeometry.tileOrigin(hit.groupId, tile) ?: return
                session.beginTile(
                    layout, ids, tile, hit.groupId,
                    // 块原点是板内坐标，加 boardOrigin 换成页面坐标——grab 是两者之差，
                    // 天然与坐标系无关。这版写法在真机上确认过拖拽正确，别再动。
                    origin = tileOrigin + session.boardOrigin,
                    startPointer = viewport,
                )
            }
            is HomeHit.Resize -> {
                val tile = layout.tile(hit.tileId) ?: return
                val tileOrigin = dragGeometry.tileOrigin(hit.groupId, tile) ?: return
                session.beginResize(
                    layout, ids, tile,
                    origin = tileOrigin + session.boardOrigin,
                    startPointer = viewport,
                    startContent = board,
                )
            }
            is HomeHit.GroupHandle -> {
                val top = dragGeometry.slot(hit.groupId)?.top ?: return
                session.beginGroup(layout, ids, hit.groupId, hit.index, top, viewport, board)
            }
            HomeHit.Pass -> Unit
        }
    }

    // 手势层作为**页面根 Box 的修饰符**挂载，而不是盖在上面的兄弟覆盖层。
    // 兄弟覆盖层一旦带 pointerInput 就会独占整条命中路径，把下面的滚动列 / 顶栏 / 卡片
    // 全挡掉。挂在根 Box 上则是「祖先」：子节点照样先收到 Main pass，我们只在 Initial pass
    // 抢真正属于拖拽的那一下 down。
    val dragLayerModifier = if (editing.on) {
        Modifier.homeDragLayer(
            session = session,
            geometry = { dragGeometry },
            handlePx = handlePx,
            removePx = removePx,
            onDown = { hit, viewport -> beginGesture(hit, viewport) },
            // 移动卡片 / 组排序要长按：拿起那一刻给一次触感，和系统桌面「拾起图标」同一手感
            onPickUp = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            onMove = { viewport -> session.move(viewport) },
            onUp = {
                when (session.mode) {
                    HomeDragMode.TILE -> settleTileDrop()
                    HomeDragMode.RESIZE -> settleResizeDrop()
                    HomeDragMode.GROUP -> settleGroupDrop()
                    HomeDragMode.IDLE -> Unit
                }
            },
            onCancel = {
                // 手势被系统抢走：整场作废（拖动中从不写草稿，所以不用恢复数据）
                if (session.mode == HomeDragMode.GROUP) cancelGroupDrop() else abandonDrag()
            },
        )
    } else {
        Modifier
    }

    Box(
        modifier
            .fillMaxSize()
            .then(dragLayerModifier)
            .onGloballyPositioned {
                // 页面根局部坐标系的基准：组区原点与组顶都按它换算。
                // 用 positionInRoot 而不是 boundsInRoot().topLeft：后者默认按**裁剪边界**取框，
                // 节点被滚出一半时会拿到被夹住的值（首页网格区就是这么拿到"冻住的"原点的）。
                session.pageOrigin = it.positionInRoot()
                // 活坐标：起手时现场算组区原点用（见 session.refreshBoardFromCoordinates）
                session.rootCoords = it
                pageSize = Offset(it.size.width.toFloat(), it.size.height.toFloat())
            }
            .then(
                // 长按空白处进编辑态；已经有手势的地方（组件、头卡）自己吃掉了指针，不受影响
                if (editing.on) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = { editing.begin(stored) },
                    )
                },
            ),
    ) {
        // ── 内容层：背景 + 滚动内容，整层录进 pageBackdrop 给顶栏模糊带与圆钮采样 ──
        // 只在真有人要采的时候录，见上面的 recordPageLayer
        Box(
            Modifier
                .matchParentSize()
                .then(if (recordPageLayer) Modifier.layerBackdrop(pageBackdrop) else Modifier),
        ) {
            // ── 背景层：主题底色 +（可选）主题色渐变 / 壁纸。卡片的柔光玻璃采它糊好的副本 ──
            // 只在「液态玻璃开着、且背景上真有壁纸」时才录（见 `recordTileLayer`）。
            // 自带一层 graphicsLayer：滚动内容每帧重录时不连带重录背景，糊好的那张纹理才能一直复用
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer()
                    .then(if (recordTileLayer) Modifier.layerBackdrop(tileBackdrop) else Modifier),
            ) {
                Box(Modifier.matchParentSize().background(barSurface))
                when (backgroundMode) {
                    HomeLayoutStore.BACKGROUND_IMAGE -> {
                        if (backgroundImage.isNotEmpty()) {
                            HeroCustomBackground(
                                imageData = backgroundImage,
                                modifier = Modifier.matchParentSize(),
                                blur = backgroundBlur,
                            )
                            // 透明度：缺多少就用主题底色盖回去多少，不整层 alpha（否则文字也会跟着淡）
                            val cover = (100 - backgroundOpacity) / 100f
                            if (cover > 0f) {
                                Box(Modifier.matchParentSize().background(barSurface.copy(alpha = cover)))
                            }
                        }
                    }
                    HomeLayoutStore.BACKGROUND_GRADIENT -> {
                        val gradient = remember(barSurface, barPrimary, barDark) {
                            themeBackgroundGradient(barSurface, barPrimary, barDark)
                        }
                        Box(Modifier.matchParentSize().background(gradient))
                    }
                    // 主题底色：上面那层 surface 已经铺好了
                    else -> Unit
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
                    .overScrollVertical()
                    .verticalScroll(scroll)
            ) {
                Spacer(Modifier.height(contentTopPadding))
                // 编辑态把刊头与头卡压暗，视线落到卡片组上
                val dim = Modifier.graphicsLayer { alpha = 1f - 0.6f * editFade.value }
                if (masthead) {
                    // 顶栏那一排按钮先占住最上面，刊头大字从它下面开始
                    if (collapsible) Spacer(Modifier.height(BAR_HEIGHT))
                    EditorialMasthead(
                        greeting = Greeting.of(),
                        name = host.greetingName,
                        dateLabel = host.dateLabel,
                        weekNumber = if (HomeCard.WEEK_NUMBER in honored) host.weekNumber else 0,
                        status = host.statusLine,
                        modifier = Modifier.enterOnce(0).onSizeChanged { mastheadPx = it.height }.then(dim),
                    )
                } else if (collapsible) {
                    Spacer(Modifier.height(BAR_HEIGHT))
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = if (masthead) 6.dp else 8.dp)
                        .then(dim)
                ) {
                    if (HomeCard.BULLETIN_BAR in honored && host.bulletins.isNotEmpty()) {
                        BulletinNoticePanel(
                            bulletins = host.bulletins,
                            onTap = host::onBulletinTap,
                            // 「查看详情」走一镜到底：面板自己登记起点矩形，宿主决定走 KIT 还是 Compose 放大
                            onDetail = host::onBulletinDetail,
                            onDismiss = host::onBulletinDismiss,
                        )
                        Spacer(Modifier.height(SECTION_GAP))
                    }
                    EditorialFrontPage(
                        modifier = Modifier.enterOnce(0),
                        host = host,
                        inbox = host.inbox.takeIf { HomeCard.INBOX in honored },
                    )
                }

                // ── 卡片组 ──
                // 这一层容器是拖拽命中的唯一回读基准：padding 之后的网格区。
                // boardOrigin/boardWidth 是**页面根局部**量；几何表以它为原点算板内坐标
                // （纯数据，不含测量量）。拖拽期间用 heightIn(min = 快照总高) 把页面总高
                // 钉住：否则拖走一块源组变矮、页面变短，滚动被夹回上界，视觉上会跳。
                val boardMinHeight = if (session.draggingTile || session.mode == HomeDragMode.RESIZE) {
                    with(density) { dragGeometry.totalHeight(groupGapPx, firstGapPx).toDp() }
                } else {
                    Dp.Unspecified
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = boardMinHeight)
                        .padding(horizontal = 16.dp)
                        .onGloballyPositioned { coords ->
                            // 活坐标对象留好（拖拽期间靠它现读原点，见 refreshBoardFromCoordinates），
                            // 顺带回填一份数值：只当兜底——滚动时这个回调不保证每帧都到，别拿它当
                            // 跟随滚动的依据。位置一律用 positionInRoot()：它读的是布局位置，
                            // 而 boundsInRoot() 默认会被滚动容器的裁剪边界夹住——网格区顶一滚出
                            // 视口，拿到的就是「视口顶」，原点从此冻住（「滚过大卡片后网格不再
                            // 随滚动变化」就是这么来的）。
                            session.boardCoords = coords
                            session.boardOrigin = coords.positionInRoot() - session.pageOrigin
                            if (coords.size.width > 0) session.boardWidth = coords.size.width.toFloat()
                        },
                ) {
                    liveLayout.groups.filter(::groupVisible).forEachIndexed { index, group ->
                        // key 定身份：组重排时子树连同动画状态一起平移，不销毁重建
                        key(group.id) {
                            Column(
                                // 记下这一组的实际顶边（页面坐标）：进编辑态时拿它当滚动锚点，
                                // 把「编辑态补出来的标题行 / 空组」造成的内容位移补回去
                                Modifier.onGloballyPositioned { coords ->
                                    val top = coords.positionInRoot().y - session.pageOrigin.y
                                    groupTops[group.id] = top
                                    // 这一帧属于哪种状态，就记到哪一份「切换前的样子」里
                                    if (!editing.on) idleTops[group.id] = top else editTops[group.id] = top
                                },
                            ) {
                                Spacer(Modifier.height(if (index == 0) FIRST_GROUP_GAP else GROUP_GAP))
                                HomeGroupSection(
                                    group = group,
                                    faces = faces,
                                    editing = editing.on,
                                    liquid = liquid,
                                    refraction = glassHasDetail,
                                    backdrop = tileBackdrop.takeIf { recordTileLayer },
                                    // 卡片能不能糊：没有可糊的细节（纯色 / 渐变，或壁纸已被糊平）时关掉
                                    blurGlass = glassHasDetail,
                                    cardCorner = cardCorner,
                                    resizeSpan = { tile ->
                                        val g = dragGeometry
                                        if (!g.ready) {
                                            null
                                        } else {
                                            val delta = pointerBoard - session.resizeStartContent
                                            Offset(
                                                (session.resizeStartCells.x + delta.x / g.colUnit)
                                                    .coerceIn(HomeGridMath.MIN_SPAN, minOf(HomeGridMath.MAX_SPAN, HomeGridMath.COLUMNS - tile.x)),
                                                (session.resizeStartCells.y + delta.y / g.rowUnit)
                                                    .coerceIn(HomeGridMath.MIN_SPAN, HomeGridMath.MAX_SPAN),
                                            )
                                        }
                                    },
                                    // 校园卡大卡的付款码：用餐时段才真去取（原「小组件」那套能力）
                                    paymentCode = { host.paymentCode() },
                                    session = session,
                                    budgetRows = rowBudget?.get(group.id),
                                    // 接收高亮：手指把这一组当落点，且它不是拖拽源组（§19）
                                    receiving = session.draggingTile &&
                                        targetGroup == group.id &&
                                        session.sourceGroupId != group.id,
                                    // 传 lambda：手指位置与回弹动画逐帧变化，只在绘制阶段读，不让整页逐帧重组
                                    reorderLift = when {
                                        session.mode == HomeDragMode.GROUP && session.groupId == group.id -> {
                                            { pointerBoard.y - session.groupGrabY - (dragGeometry.slot(group.id)?.top ?: 0f) }
                                        }
                                        reorderSettleId == group.id -> {
                                            { reorderSettleAnim.value }
                                        }
                                        else -> null
                                    },
                                    reorderShift = reorderShiftMap[group.id] ?: 0f,
                                    onOpen = { it.onClick() },
                                    onStartEdit = { editing.begin(stored) },
                                    onEditGroup = { editing.editGroup = group },
                                    onAddTile = { editing.addTo = group },
                                    addTileMenu = {
                                        TilePickerMenu(
                                            expanded = editing.addTo?.id == group.id,
                                            // 全目录都列：组内已经有的功能**照样可以再加一块**（2026-11 按需求取消了
                                            // 「一组里同一功能只能一个」的限制，常用功能本来就是同路由的第二块）
                                            candidates = catalog,
                                            onDismiss = { editing.addTo = null },
                                            onPick = { service ->
                                                update { it.addTile(group.id, service.route.id, it.nextTileId(group.id, service.route.id)) }
                                                editing.addTo = null
                                            },
                                        )
                                    },
                                    onDeleteGroup = { editing.confirmDeleteGroup = group },
                                    // 网格模式下删完顺手收空行（空行不该留着，下面的块与组一起上移）
                                    onRemoveTile = { tileId -> update { it.removeTile(tileId, grid) } },
                                    // 功能卡各自错峰起跳，避免再叠加整组的上浮。
                                    modifier = Modifier,
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = editing.on,
                    enter = expandVertically(tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + fadeIn(),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                ) {
                    // 新组不预填名字：空标题常规态整行不画，编辑态显示「未命名分组」占位
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = GROUP_GAP)) {
                        AddGroupRow { update { it.addGroup(HomeGroup(id = newGroupId())) } }
                    }
                }
                Spacer(Modifier.height(SECTION_GAP + extraBottomPadding + 8.dp))
            }
        }

        // 编辑态顶栏（取消 / 设置 / 保存）不需要宿主给按钮，所以这一端没有 topActions 也要画，
        // 否则桌面、Web 长按进了编辑态就出不来了
        if (collapsible || editing.on) {
            // 顶栏圆钮（编辑态三个图标 + 宿主给的扫码 / 搜索 / 通知）也采**内容层**：
            // 它们压在内容上面，该糊 / 折射的是此刻盖住的东西，不是固定壁纸。
            // 折射开关同理走 Local：那几个按钮是宿主（:app）画的，拿不到这里的参数
            CompositionLocalProvider(
                // 折射开关是采样总开关：关掉时圆钮连采样源都不给，退回半透明底（见 HomeTileFaceBox）
                LocalHomeBarBackdrop provides pageBackdrop.takeIf { liquid },
            ) {
                HomeCollapsingBar(
                    progress = progress,
                    topInset = contentTopPadding,
                    bandShift = contentTopShift,
                    bandBackdrop = pageBackdrop.takeIf { glass },
                    barLiquidBackdrop = pageBackdrop.takeIf { liquid },
                    onTapCollapsed = { scope.launch { scroll.animateScrollTo(0) } },
                    editing = editing.on,
                    leading = topLeading,
                    actions = topActions,
                    onSaveEdit = { saveAndQuitEdit() },
                    onHomeSettings = {
                        // 齿轮 = 在原地打开「主页设置」抽屉：不跳设置页、编辑态不关闭，
                        // 手上的草稿原样留着，关掉抽屉接着摆（§31）
                        host.onOpenHomeSettings()
                    },
                    alignWithGreeting = masthead,
                    // 「功能按钮统一右对齐」开着 = 整个顶栏回到改造前那套（头像 / 三钮 / 无居中标题）
                    devTopBar = topActionsRight,
                )
            }
        }

        // ── 拖拽幽灵块：跟手画在最上层；松手时飞向落点（或飞回起手位置）再消失 ──
        // 几何表按**函数**传：不拖动时幽灵自己早退，几何表就一次都不用算（传值的话参数在
        // 调用点就求值了，等于每次重组都白算一遍整张表）
        HomeDragGhost(
            session = session,
            faces = faces,
            liquid = liquid,
            refraction = glassHasDetail,
            backdrop = tileBackdrop.takeIf { recordTileLayer },
            // 幽灵块是卡片的预览，跟卡片同一条规则
            blurGlass = glassHasDetail,
            cardCorner = cardCorner,
            geometry = { dragGeometry },
            settling = ghostSettling,
            settleValue = { ghostSettle.value },
        )

        // ── 拖拽期间的每帧维护：①组区原点跟住滚动 ②拖到边缘自动滚动 ③探出组底就多开一行 ──
        // ①是几何表的命门：手指板坐标 = 手指 − boardOrigin，而 boardOrigin 只能靠**现读活坐标**
        // 才准。组区容器上那处 onGloballyPositioned 回填不可靠——页面滚起来之后它不保证每帧都到，
        // 值一停住，落点格 / 目标组 / 组排序中点就整体偏「从起手算起滚过的距离」：表现是拖到一半
        // 页面滚下去、占位框不跟着内容走，松手落到别的组（卡片本身是 Column 排的，照样滚得对，
        // 所以只看画面会觉得「卡片对、网格不对」）。这里每帧刷一次，与回调是否触发无关。
        // ②只改 scroll.value：原点跟着刷新，目标组 / 落点格 / 缩放尺寸 / 组排序目标全部自动重算。
        // ③只在手指探出某一组最后一格时给它加行，且**只增不减**（见 extraRows 的说明）。
        LaunchedEffect(session.active) {
            if (!session.active) return@LaunchedEffect
            val edge = with(density) { 104.dp.toPx() }
            val maxStep = with(density) { 14.dp.toPx() }
            while (true) {
                withFrameNanos { }
                session.refreshBoardFromCoordinates()
                syncExtraRows()
                val y = session.pointer.y
                val step = when {
                    y < edge -> -(1f - (y / edge).coerceIn(0f, 1f)) * maxStep
                    y > pageSize.y - edge -> ((y - (pageSize.y - edge)) / edge).coerceIn(0f, 1f) * maxStep
                    else -> 0f
                }
                if (step != 0f) scroll.scrollBy(step)
            }
        }

        // 切换编辑态保住滚动位置（进、出都要）：编辑态会给每组补标题行、把空组与「组件都打不开」
        // 的组显示出来，退出时反过来——这些都在**当前视口之上**，内容一变，画面就整体错位。
        // 光记住 scroll.value 补不回来（内容高度变了、scroll 值没变）。
        //
        // 上面 predictAnchorFix + SideEffect 已经在**布局之前**把预测的差值补好了；这里按实测
        // 的锚点组（切换前视口里最靠上的那一组）逐帧把残差补掉——组高带弹簧动画，一次补到位
        // 剩下的部分还会接着漂；残差归零三帧收工。
        LaunchedEffect(editing.on) {
            val entering = editing.on
            val beforeTops = if (entering) idleTops else editTops
            val beforeGroups = if (entering) idleGroups else editGroups
            val alive = visibleGroups.mapTo(mutableSetOf()) { it.id }
            val anchor = beforeTops.entries
                .filter { it.key in alive && beforeGroups.any { group -> group.id == it.key } }
                .minByOrNull { abs(it.value) }
                ?: return@LaunchedEffect
            val targetY = anchor.value
            var stable = 0
            repeat(40) {
                withFrameNanos { }
                val y = groupTops[anchor.key] ?: return@LaunchedEffect
                val delta = (y - targetY).roundToInt()
                if (delta == 0) {
                    if (++stable >= 3) return@LaunchedEffect
                } else {
                    stable = 0
                    val target = (scroll.value + delta).coerceIn(0, scroll.maxValue)
                    if (target != scroll.value) scroll.scrollTo(target)
                }
            }
        }

        // ── 组标题颜色、删除组、往组里加功能这几个弹窗 ──
        editing.editGroup?.let { group ->
            val current = layout.group(group.id) ?: group
            GroupEditDialog(
                group = current,
                onDismiss = { editing.editGroup = null },
                onConfirm = { colorHex, title ->
                    update { it.tintGroup(current.id, colorHex).renameGroup(current.id, title) }
                    editing.editGroup = null
                },
            )
        }
        // 组标题行删除按钮点出来的二次确认：默认组不许删（删了就回不来）
        editing.confirmDeleteGroup?.let { group ->
            DeleteGroupDialog(
                group = group,
                onDismiss = { editing.confirmDeleteGroup = null },
                onConfirm = {
                    update { it.removeGroup(group.id) }
                    editing.confirmDeleteGroup = null
                },
            )
        }
    }
}

/** 组与组之间的间距（每组自带标题行，间距比组内组件缝隙大一档）。 */
private val GROUP_GAP = 20.dp

/** 柔光玻璃的模糊半径：背景副本只糊这一次，所有卡片共用。 */
private val FROST_RADIUS = 14.dp

/** 采样副本的录制方式：与默认那份不是同一个对象，`layerBackdrop` 才会照常把清晰的背景画上屏，只把副本拿去糊。 */
private val FrostOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

/** 拖拽幽灵块的绘制层次：压在顶栏与所有卡片之上。 */
private const val GHOST_Z_INDEX = 20f

/** 第一组与上方头卡之间的间距。 */
private val FIRST_GROUP_GAP = 20.dp

/** 自由拖拽模式的边界联动容差（格）：离邻居的边不到这个距离就吸过去。 */
private const val EDGE_LINK_CELLS = 0.28f

/** 幽灵块吸附：与卡片位移同一条曲线，只是换成二维坐标。 */
private val HomeTileMotionOffset = tween<Offset>(HOME_TILE_MOTION_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)

/**
 * 拖动中幽灵块缩放到的比例 / 不透明度：比真身略小、略透，底下的落点占位框才露得出来。
 * 松手后这两个值会在结算动画里补回 1.0（否则动画末尾换真身那一下会「突变成正常大小」）。
 */
private const val GHOST_DRAG_SCALE = 0.94f
private const val GHOST_DRAG_ALPHA = 0.88f

/**
 * 编辑态的那点临时状态：草稿布局、正在编辑的组、正在加功能的组。
 *
 * 单独抽出来是因为首页里到处都要改它，塞成十几个 `var` 会让 [HomeScreen] 读不下去；
 * 拖拽过程中的那些位置不在这里，在 [HomeDragSession]。
 */
private class HomeEditState {
    var on by mutableStateOf(false)
    var draft by mutableStateOf<HomeLayout?>(null)
    var editGroup by mutableStateOf<HomeGroup?>(null)
    var addTo by mutableStateOf<HomeGroup?>(null)
    /** 等着二次确认删除的那一组（组标题行右侧的删除按钮点出来的）。 */
    var confirmDeleteGroup by mutableStateOf<HomeGroup?>(null)

    fun begin(stored: HomeLayout) {
        if (on) return
        draft = stored
        on = true
    }

    fun cancel() {
        on = false
        draft = null
        editGroup = null
        addTo = null
        confirmDeleteGroup = null
    }
}

/**
 * 默认组的 id：「常用功能」+ 各分类名（默认组的 id 就是分类名，见 `HomeLayoutDefaults`）。
 * 不许删（删了就再也回不来）。直接按分类枚举判定，不再为拿 id 重新生成一遍目录——
 * 那样既低效，又依赖「研究生组集 ⊆ 本科生」这个偶然事实。
 */
private fun defaultGroupIds(): Set<String> =
    ServiceCategory.entries.mapTo(mutableSetOf(HomeLayoutDefaults.QUICK_GROUP)) { it.name }

private fun newGroupId(): String = "custom-" + kotlin.random.Random.nextInt(100000, 999999)

/**
 * 首页**内容层**（背景 + 滚动内容）的采样源：顶栏那排圆钮——包括宿主给的扫码 / 搜索 / 通知——
 * 读它做液态玻璃，于是圆钮模糊 / 折射的也是它**此刻盖住的东西**，和顶栏圆钮同一个源。
 *
 * 首页在顶栏这一层 provide（液态开关关的时候是 null，按钮退回非液态）。卡片 / 悬浮球不走它，
 * 那些还是采背景层（见 `tileBackdrop`）——它们贴在壁纸上，采整页会把自己和邻居也采进去。
 */
val LocalHomeBarBackdrop = compositionLocalOf<Backdrop?> { null }

// ── 顶栏 ──

/**
 * 首页的折叠顶栏，叠在滚动内容上面，两套排法：
 *
 * - [devTopBar] = true（主页设置「功能按钮统一右对齐」开着，默认）：**改造前那套**——
 *   左侧「交小西」头像（随上滑淡入放大）、三个功能圆钮全在右侧、**不画居中标题**；
 * - false：本分支那套——扫码靠左（宿主给的 [leading]）、标题绝对居中、右侧只留搜索与通知。
 *
 * 往上滑过刊头后玻璃底色浮出来，底边柔和淡出。编辑态两套排法一律换成三个纯图标：
 * 左边取消、右边齿轮 + 对勾（需求 §1.3）——编辑态是本分支新加的，不跟着顶栏排法走，
 * 否则老排法下进了编辑态就出不来。
 */
@Composable
private fun HomeCollapsingBar(
    progress: () -> Float,
    topInset: Dp,
    bandShift: () -> Dp,
    /** 顶部模糊带的采样源；null = 经典风格，不画模糊带。 */
    bandBackdrop: Backdrop?,
    /** 顶栏圆钮液态玻璃的采样源（首页**内容层**：背景 + 滚动内容）；null = 退回半透明圆钮。 */
    barLiquidBackdrop: Backdrop?,
    onTapCollapsed: () -> Unit,
    editing: Boolean,
    leading: (@Composable () -> Unit)?,
    actions: (@Composable () -> Unit)?,
    onSaveEdit: () -> Unit,
    onHomeSettings: () -> Unit,
    alignWithGreeting: Boolean = false,
    devTopBar: Boolean = false,
) {
    val collapsed by remember { derivedStateOf { progress() > 0.5f } }
    Box(Modifier.fillMaxWidth().height(topInset + BAR_HEIGHT)) {
        if (bandBackdrop != null) {
            // 96dp 的模糊带比顶栏本身高，往内容里多淡一段。贴屏幕顶边：壳会按 stable 顶栏高度
            // 把整页上移，不补回 [bandShift] 整条带会被顶出屏幕。
            val surface = MiuixTheme.colorScheme.surface
            Box(
                Modifier
                    .fillMaxWidth()
                    .requiredHeight(PROGRESSIVE_BLUR_BAND_HEIGHT)
                    .offset { IntOffset(0, bandShift().roundToPx()) },
            ) {
                Box(Modifier.matchParentSize().progressiveBlurBar(bandBackdrop, surface, titled = false))
            }
        }
        // 顶栏本体透明：背景图（或背景层的主题底色）从下面透上来，按钮浮在上面
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (collapsed && !editing) {
                        Modifier.clickable(interactionSource = null, indication = null, onClick = onTapCollapsed)
                    } else {
                        Modifier
                    },
                ),
        )

        // 标题绝对居中：不参与左右两排按钮的排布，所以两边按钮多少都不影响它。
        // 编辑态换成「首页调整设置」，让人一眼知道现在在摆桌面；
        // 改造前的顶栏（devTopBar）本来就没有居中标题，那条路上整块不画
        Box(
            Modifier
                .matchParentSize()
                .padding(top = topInset, start = if (editing) 20.dp else 0.dp)
                .height(BAR_HEIGHT),
            // 编辑态标题靠左，右边留给三颗按钮
            contentAlignment = if (editing) Alignment.CenterStart else Alignment.Center,
        ) {
            AnimatedContent(editing, transitionSpec = { BarSwap }, label = "barTitle") { edit ->
                val title = when {
                    edit -> "编辑主页"
                    devTopBar -> null
                    else -> "岱宗盒子"
                }
                if (title != null) {
                    Text(
                        title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = topInset)
                .height(BAR_HEIGHT)
                // 改造前那一套的左边距是 20dp（头像比圆钮细一档），本分支两排圆钮统一 16dp
                .padding(start = if (devTopBar) 20.dp else 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.graphicsLayer {
                    translationY = if (alignWithGreeting) 0f else -BUTTON_LIFT.toPx() * (1f - progress())
                },
            ) {
                AnimatedContent(editing, transitionSpec = { BarSwap }, label = "barLeading") { edit ->
                    when {
                        edit -> Unit
                        devTopBar -> HomeBarAvatar(progress)
                        else -> leading?.invoke()
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.graphicsLayer {
                    translationY = if (alignWithGreeting) 0f else -BUTTON_LIFT.toPx() * (1f - progress())
                },
            ) {
                AnimatedContent(editing, transitionSpec = { BarSwap }, contentAlignment = Alignment.CenterEnd, label = "barActions") { edit ->
                    if (edit) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            HomeBarIconButton(Icons.Default.Settings, "主页设置", barLiquidBackdrop, onHomeSettings)
                            HomeBarIconButton(Icons.Default.Check, "保存布局", barLiquidBackdrop, onSaveEdit)
                        }
                    } else {
                        actions?.invoke()
                    }
                }
            }
        }
    }
}

/**
 * 改造前顶栏左侧的「交小西」头像（[HomeCollapsingBar] 的 `devTopBar` 那条路专用）。
 *
 * 和改造前一模一样的写法：随上滑淡入、由 0.78 放大到 1，停在顶部时基本看不见——
 * 那时候顶栏上只有右侧三个功能圆钮，滚起来才「浮」出个头像。
 */
@Composable
private fun HomeBarAvatar(progress: () -> Float) {
    JiaoxiaoxiAvatar(
        Modifier.size(28.dp).graphicsLayer {
            val f = progress()
            alpha = f
            scaleX = 0.78f + 0.22f * f
            scaleY = scaleX
        },
    )
}

/**
 * 顶栏圆钮：液态玻璃（采首页背景层，和卡片同一套 `drawBackdrop`）；[liquid] 为 null 时
 * 退回半透明底色圆钮（需求 §1.2）。
 */
@Composable
private fun HomeBarIconButton(icon: ImageVector, label: String, liquid: Backdrop?, onClick: () -> Unit) {
    val dark = LocalIsDarkTheme.current
    // 折射不再有用户开关：圆钮压在内容上，恒模糊 + 恒折射（顶栏那条带子已经不采样了，圆钮仍是玻璃）
    val fill = if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.4f)
    val material = if (liquid != null) {
        Modifier.drawBackdrop(
            backdrop = liquid,
            shape = { CircleShape },
            effects = {
                padding = maxOf(padding, 8.dp.toPx())
                vibrancy()
                blur(6.dp.toPx())
                lens(8.dp.toPx(), 10.dp.toPx())
            },
            highlight = { Highlight.Default.copy(alpha = 0.65f) },
            shadow = null,
            onDrawSurface = { drawRect(fill) },
        )
    } else {
        Modifier
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (dark) 0.52f else 0.6f))
    }
    Box(
        Modifier
            .size(BAR_BUTTON_SIZE)
            .then(material)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

/** 顶栏在常规 / 编辑两套按钮之间切换：旧的缩小淡出，新的放大淡入。 */
private val BarSwap = ContentTransform(
    targetContentEnter = fadeIn(tween(200, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.8f),
    initialContentExit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
    sizeTransform = SizeTransform(clip = false),
)

/** 展开时圆钮比顶栏正中上移的距离：顶栏里圆钮上方正好留这么多，不会顶进状态栏。 */
private val BUTTON_LIFT get() = (BAR_HEIGHT - 36.dp) / 2 - 2.dp

/** 顶部模糊带的高度。 */
private val PROGRESSIVE_BLUR_BAND_HEIGHT = 96.dp

/**
 * 「主题色渐变背景」：把主题色按 22% → 10% → 4%（深色 30% → 18% → 8%）的比例**融进底色**，
 * 从左上一路铺到右下，最后收在底色本身。
 *
 * 关键是**不铺纯主题色**：整屏浓色会把卡片与正文压掉，上半屏还会像换了一套配色；
 * 从「透明度适中」的混色起步、越往下越接近底色，才是壁纸缺席时该有的那种轻渐变。
 */
private fun themeBackgroundGradient(surface: Color, primary: Color, dark: Boolean): Brush =
    Brush.linearGradient(
        listOf(
            lerp(surface, primary, if (dark) 0.30f else 0.22f),
            lerp(surface, primary, if (dark) 0.18f else 0.10f),
            lerp(surface, primary, if (dark) 0.08f else 0.04f),
            surface,
        ),
        start = Offset.Zero,
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )

// ── 首页版式 ──

/**
 * 首页头卡的液体底：只留圆角裁切，不描边也不投影。
 * 着色器本体由 [LiquidBackground] 画，这一层只负责形状。
 */
@Composable
private fun Modifier.liquidPaper(radius: Dp = EDITORIAL_RADIUS): Modifier =
    squircleClip(radius)

/** 刊头：问候与名字合成一行大字（与操作钮同高），下行日期、教学周与连接状态。 */
@Composable
private fun EditorialMasthead(
    greeting: String,
    name: String,
    dateLabel: String,
    weekNumber: Int,
    status: HomeStatusLine?,
    modifier: Modifier = Modifier,
) {
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val headline = if (name.isBlank()) greeting else "$greeting，$name"
    Row(
        modifier.fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    headline,
                    style = Editorial.masthead.copy(fontSize = 30.sp, lineHeight = 38.sp),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(dateLabel, fontSize = 12.sp, color = muted, maxLines = 1)
                if (weekNumber in 1..MAX_TEACHING_WEEK) {
                    Text("  ", fontSize = 12.sp, color = muted.copy(alpha = 0.5f))
                    Text(
                        "第 $weekNumber 周",
                        fontSize = 12.sp,
                        color = if (LocalIsDarkTheme.current) lerp(HomeSeal.Red, ink, 0.35f) else HomeSeal.Red,
                        maxLines = 1,
                    )
                }
                if (status != null) {
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        HeroStatusLine(status)
                    }
                }
            }
        }
    }
}

/** 今日安排只放时间相关的信息；所有功能及其状态统一放在下面的卡片组里。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun EditorialFrontPage(
    modifier: Modifier = Modifier,
    host: HomeHost,
    inbox: HomeInboxSummary?,
) {
    val scheduled = host.nextUp.takeIf { HomeCard.NEXT_UP in host.honoredCards }
    // 待办截止比下一节课 / 日程还早，就让它顶上「下一项」，待办那一行不再重复它
    val dueLead = HomeHero.dueLead(scheduled, inbox?.nextDue)
    val nextUp = dueLead ?: scheduled
    @Suppress("NAME_SHADOWING")
    val inbox = if (dueLead != null) inbox?.copy(nextTodo = null) else inbox
    val exam = host.glances.takeIf { HomeCard.GLANCE in host.honoredCards }
        ?.firstOrNull { it.route == AppRoute.Schedule }
    if (nextUp == null && inbox == null && exam == null) return

    val hasLead = nextUp != null || exam != null || inbox != null
    val appearanceStore = remember { SettingsStore() }
    val dark = LocalIsDarkTheme.current
    // 设置页与首页各读一份；靠 revision / 深浅模式在别处改完之后重新读盘。
    val appearance = remember(HeroAppearanceRevision.current, dark) { HeroAppearance.read(appearanceStore, dark) }
    var showAppearance by remember { mutableStateOf(false) }
    val configure = { showAppearance = true }
    val ink = appearance.contentColor
    val muted = ink.copy(alpha = 0.78f)
    val accent = if (ink == Color.White) LiquidCardAccent else ink.copy(alpha = 0.8f)
    val rule = ink.copy(alpha = 0.18f)
    val mood = rememberLiquidMood(nextUp?.startAt, examSoon = exam != null, appearance = appearance)
    Column(modifier.fillMaxWidth()) {
        if (hasLead) {
            Box(Modifier.fillMaxWidth().liquidPaper()) {
                when (appearance.background) {
                    "solid" -> Box(Modifier.matchParentSize().background(appearance.solidColor))
                    "forest" -> {
                        HeroForestBackground(Modifier.matchParentSize())
                        Box(Modifier.matchParentSize().background(HeroForestScrim))
                    }
                    "pixel" -> {
                        HeroPixelBackground(Modifier.matchParentSize(), animated = !showAppearance)
                        Box(Modifier.matchParentSize().background(HeroPixelScrim))
                    }
                    "custom" -> {
                        HeroCustomBackground(
                            imageData = appearance.imageData,
                            modifier = Modifier.matchParentSize(),
                            blur = appearance.imageBlur,
                            brightness = appearance.imageBrightness,
                        )
                        Box(Modifier.matchParentSize().background(HeroCustomScrim))
                    }
                    else -> {
                        LiquidBackground(
                            colors = mood.colors, seed = mood.seed, speed = mood.speed, crease = mood.crease,
                            animated = mood.animated && !showAppearance,
                            modifier = Modifier.matchParentSize(),
                        )
                        Box(Modifier.matchParentSize().background(LiquidCardScrim))
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    nextUp?.let {
                        EditorialNextUpLead(it, ink, muted, accent, onLongClick = configure) {
                            it.route?.let(host::onRoute) ?: host.onProfile()
                        }
                    }
                    if (exam != null) {
                        if (nextUp != null) EditorialRule(Modifier.padding(horizontal = 18.dp), rule)
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = SinkFeedback(),
                                    onClick = { host.onRoute(AppRoute.Schedule) },
                                    onLongClick = configure,
                                    onLongClickLabel = "设置卡片外观",
                                )
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(exam.icon, null, tint = accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(exam.label, fontSize = 12.sp, color = muted)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(exam.value, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ink)
                                exam.caption?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = muted)
                                }
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                                tint = muted.copy(alpha = 0.7f), modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                    if (inbox != null) {
                        if (nextUp != null || exam != null) {
                            EditorialRule(Modifier.padding(horizontal = 18.dp), rule)
                        }
                        InboxBrief(inbox, ink, muted, onLongClick = configure) { host.onRoute(AppRoute.Inbox) }
                    }
                }
            }
        }
    }
    if (showAppearance) {
        HeroAppearanceDialog(
            current = appearance,
            nextStartAt = nextUp?.startAt,
            examSoon = exam != null,
            onDismiss = { showAppearance = false },
            onApply = {
                it.save(appearanceStore, dark)
                showAppearance = false
            },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun EditorialNextUpLead(
    nextUp: HomeNextUp,
    ink: Color,
    muted: Color,
    accent: Color,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val timeLabel = nextUp.timeLabel

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "设置卡片外观",
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .background(
                        if (timeLabel != null) accent else muted.copy(alpha = 0.45f),
                        CircleShape,
                    )
            )
            Spacer(Modifier.width(7.dp))
            Text(
                if (timeLabel != null) "接下来" else "我的日程",
                style = Editorial.kicker.copy(fontSize = 11.sp, letterSpacing = 1.6.sp),
                color = muted,
                modifier = Modifier.weight(1f),
            )
            if (!nextUp.eta.isNullOrBlank()) {
                Text(
                    nextUp.eta,
                    style = TextStyle(
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        letterSpacing = 0.5.sp,
                    ),
                    color = ink,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = muted.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        if (timeLabel != null) {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        timeLabel,
                        style = Editorial.figure.copy(
                            fontSize = 28.sp,
                            lineHeight = 32.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                        color = ink,
                    )
                    if (!nextUp.timeCaption.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            nextUp.timeCaption,
                            style = MiuixTheme.textStyles.footnote2,
                            color = muted,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Box(
                    Modifier
                        .width(0.6.dp)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp)
                        .background(ink.copy(alpha = 0.18f))
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        nextUp.title,
                        style = TextStyle(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            lineHeight = 24.sp,
                            letterSpacing = 0.4.sp,
                        ),
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!nextUp.detail.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            nextUp.detail,
                            style = MiuixTheme.textStyles.footnote1,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    nextUp.icon,
                    contentDescription = null,
                    tint = muted.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        nextUp.title,
                        style = TextStyle(
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            letterSpacing = 0.4.sp,
                        ),
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!nextUp.detail.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            nextUp.detail,
                            style = MiuixTheme.textStyles.footnote2,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun InboxBrief(summary: HomeInboxSummary, ink: Color, muted: Color, onLongClick: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "设置卡片外观",
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "待办",
            style = Editorial.kicker.copy(fontSize = 11.sp, letterSpacing = 1.5.sp),
            color = ink,
            modifier = Modifier
                .border(0.6.dp, ink.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.5.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            summary.nextTodo ?: "去收纳里看看",
            style = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 14.5.sp,
                lineHeight = 19.sp,
                letterSpacing = 0.3.sp,
            ),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            listOfNotNull(
                summary.todoCount.takeIf { it > 0 }?.let { "$it 件没办" },
                summary.unreadCount.takeIf { it > 0 }?.let { "$it 组未读" },
            ).joinToString(" · "),
            style = MiuixTheme.textStyles.footnote2,
            color = muted,
            maxLines = 1,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = muted.copy(alpha = 0.7f),
            modifier = Modifier.size(15.dp),
        )
    }
}


// ── 编辑态的弹窗与幽灵块 ──

/**
 * 拖拽幽灵块：跟手画一块「和真组件一模一样」的预览；松手结算中改由吸附/回弹动画驱动。
 *
 * 只在移动卡片时出现；缩放没有幽灵（被缩那块自己跟手变大）。
 * 结算期 [HomeDragSession.mode] 已是 IDLE，但 [HomeDragSession.settling] 为真、
 * `base`/`tileId` 还留着——靠它们继续画到吸附动画播完。
 */
@Composable
private fun HomeDragGhost(
    session: HomeDragSession,
    faces: Map<String, HomeServiceRow>,
    liquid: Boolean,
    refraction: Boolean,
    backdrop: Backdrop?,
    /** 幽灵是卡片的预览，糊不糊跟卡片同一条规则（见 [HomeTileFaceBox]）。 */
    blurGlass: Boolean = true,
    /** 幽灵块的圆角也跟卡片走（同样按短边一半截断），松手换成真身时形状才对得上。 */
    cardCorner: Dp = 22.dp,
    /** 几何表按函数拿：本层早退时一次都不必求值（见调用处说明）。 */
    geometry: () -> HomeBoardGeometry,
    settling: Boolean,
    settleValue: () -> Offset,
) {
    if (!session.draggingTile && !settling) return
    val face = session.routeId?.let { faces[it] } ?: return
    val board = geometry()
    if (!board.ready) return
    val density = LocalDensity.current
    val gapPx = with(density) { HomeTileGap.toPx() }
    val wPx = (session.sizeCells.x * board.colUnit - gapPx).coerceAtLeast(1f)
    val hPx = (session.sizeCells.y * board.rowUnit - gapPx).coerceAtLeast(1f)
    // 和卡片同一套截断：设置值不超过短边的一半 ⇒ 滑条到顶时幽灵也是圆饼
    val cornerRadius = cardCorner.coerceAtMost(with(density) { minOf(wPx, hPx).toDp() } / 2)

    // 松手后幽灵一边飞向落点、一边**长回原大、变回不透明**：
    // 拖动中它是 94% + 88% 不透明（好让底下的落点占位框露出来），而落点处换成真身的那一刻
    // 是真身 100%。不把这两个值补回去，动画结束那一帧就会「从略小一点突变成正常大小」——
    // 用户看到的就是落位末尾跳一下。和吸附位移同一条曲线，同时到位。
    val settleToFull by animateFloatAsState(
        targetValue = if (settling) 1f else 0f,
        animationSpec = HomeTileMotion,
        label = "ghostSettleToFull",
    )
    // 拿起时缩到拖动比例，同样是这条曲线。
    val pickup = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pickup.animateTo(1f, HomeTileMotion) }

    Box(
        Modifier
            // 跟手阶段：手指 − 抓取点；结算阶段：吸附/回弹动画的当前值（都是视口坐标）
            .offset {
                val p = if (settling) settleValue() else session.ghostOrigin
                IntOffset(p.x.roundToInt(), p.y.roundToInt())
            }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .zIndex(GHOST_Z_INDEX)
            .graphicsLayer {
                // 略小于占位框 + 半透明：底下的落点占位框（2dp 主题色边框）才能露出来。
                // 之前放大到 1.04 且 96% 不透明，正好把占位框整个盖住，等于没有提示。
                // 结算期线性补回 1.0 / 1.0，见上面 settleToFull。
                val full = settleToFull
                val lift = pickup.value
                val dragScale = 1f + (GHOST_DRAG_SCALE - 1f) * lift
                val dragAlpha = 1f + (GHOST_DRAG_ALPHA - 1f) * lift.coerceIn(0f, 1f)
                scaleX = dragScale + (1f - dragScale) * full
                scaleY = scaleX
                alpha = dragAlpha + (1f - dragAlpha) * full
            },
    ) {
        HomeTileFaceBox(
            face = face,
            w = session.sizeCells.x,
            h = session.sizeCells.y,
            liquid = liquid,
            refraction = refraction,
            backdrop = backdrop,
            blurGlass = blurGlass,
            cornerRadius = cornerRadius,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 一个组的设置：组名 + 标题颜色。组名可清空（常规态整行不画，编辑态显示「未命名分组」）。
 *
 * 颜色是一排色块：第一块「跟随主题」（存空串），中间几块预设，最后一块「自定义」才展开取色器。
 */
@Composable
private fun GroupEditDialog(
    group: HomeGroup,
    onDismiss: () -> Unit,
    onConfirm: (colorHex: String, title: String) -> Unit,
) {
    // 限长 12：标题行只有一行、字号 18sp，再长就只剩省略号了
    var title by remember(group.id) { mutableStateOf(group.title) }
    // null = 跟随主题
    var picked by remember(group.id) { mutableStateOf(CourseColors.parseHex(group.colorHex)) }
    var custom by remember(group.id) { mutableStateOf(picked != null && picked !in GroupTitlePresets) }
    val themeTint = MiuixTheme.colorScheme.primary
    WindowDialog(show = true, title = "分组设置", onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
        ) {
            TextField(
                value = title,
                onValueChange = { if (it.length <= GROUP_TITLE_MAX) title = it },
                label = "分组名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text("标题颜色", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ColorSwatch(fill = Brush.linearGradient(listOf(themeTint, themeTint.copy(alpha = 0.35f))), selected = picked == null, label = "跟随主题") {
                    picked = null
                    custom = false
                }
                GroupTitlePresets.forEach { color ->
                    ColorSwatch(fill = Brush.linearGradient(listOf(color, color)), selected = !custom && picked == color, label = null) {
                        picked = color
                        custom = false
                    }
                }
                ColorSwatch(fill = Brush.sweepGradient(RainbowStops), selected = custom, label = "自定义") {
                    custom = true
                    if (picked == null) picked = themeTint
                }
            }
            AnimatedVisibility(custom, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                AppColorPicker(
                    color = picked ?: themeTint,
                    onColorChanged = { picked = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            TextButton(
                text = "确定",
                onClick = { onConfirm(picked?.let(CourseColors::toHex).orEmpty(), title.trim()) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 组标题颜色的一枚色块：选中时外面套一圈主题色描边。 */
@Composable
private fun ColorSwatch(fill: Brush, selected: Boolean, label: String?, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "swatch")
    val outline = MiuixTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(26.dp)
            .graphicsLayer { scaleX = 0.86f + 0.14f * ring; scaleY = scaleX }
            .border(2.dp, outline.copy(alpha = 0.7f * ring), CircleShape)
            .padding(3.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClickLabel = label,
                onClick = onClick,
            ),
    )
}

/** 组标题的预设色：朱红、橙、琥珀、绿、青、蓝、靛、紫。 */
private val GroupTitlePresets = listOf(
    Color(0xFFB2362B), Color(0xFFE07B39), Color(0xFFC99A2E), Color(0xFF2E7D32),
    Color(0xFF00897B), Color(0xFF1565C0), Color(0xFF5C6BC0), Color(0xFF8E24AA),
)

private val RainbowStops = listOf(
    Color(0xFFFF5252), Color(0xFFFFC107), Color(0xFF4CAF50), Color(0xFF00BCD4),
    Color(0xFF3F51B5), Color(0xFFE040FB), Color(0xFFFF5252),
)

/** 删除分组的二次确认（§2.4）：整组连同里面的组件一起删，没法撤销。 */
@Composable
private fun DeleteGroupDialog(group: HomeGroup, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    WindowDialog(show = true, title = "删除分组", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "「${group.title.ifBlank { "未命名分组" }}」和里面的 ${group.tiles.size} 个组件都会一起删掉，这一步没法撤销。",
                style = MiuixTheme.textStyles.body2,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "删除",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 往一个组里放功能：列出目录里的所有入口（已经放过的**也列**，允许再放一块）。 */
@Composable
private fun TilePickerMenu(
    expanded: Boolean,
    candidates: List<AppService>,
    onDismiss: () -> Unit,
    onPick: (AppService) -> Unit,
) {
    com.xjtu.toolbox.ui.components.AppDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (candidates.isEmpty()) {
            Text(
                "这一端没有可添加的功能",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(16.dp),
            )
        }
        candidates.forEach { service ->
            com.xjtu.toolbox.ui.components.AppDropdownMenuItem(
                text = { Text(service.title, fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        HomeIcons.of(service.route),
                        contentDescription = null,
                        tint = serviceColor(service.route.id),
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = { onPick(service) },
            )
        }
    }
}

/** 编辑态最下面的「新建分组」。 */
@Composable
private fun AddGroupRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(
                1.dp,
                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                RoundedCornerShape(18.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("新建分组", fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
    }
}

/** 首页各卡之间统一的间距。 */
private val SECTION_GAP = 14.dp

/** 液体卡上承托白字的暗色罩：顶部稍重、中间最透、底部再压回来。 */
private val LiquidCardScrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.34f),
    0.45f to Color.Black.copy(alpha = 0.22f),
    1f to Color.Black.copy(alpha = 0.38f),
)

/** 液体卡上的暖色点缀，只用在小圆点和考试图标上，不承字。 */
private val LiquidCardAccent = Color(0xFFFFB4A6)

/** 首页折叠顶栏（状态栏以下）的高度，几个 36dp 圆钮在里面居中。 */
private val BAR_HEIGHT = 52.dp

/** 顶栏圆钮的直径：和改造前一致，编辑态那三个图标也用这个尺寸。 */
private val BAR_BUTTON_SIZE = 44.dp

/** 杂志版式的卡片圆角。 */
private val EDITORIAL_RADIUS = 24.dp

/** 教学周超过这个数就不显示（假期或学期没对上）。 */
private const val MAX_TEACHING_WEEK = 25

/** 组名最多几个字：标题行只有一行、18sp 字号，再长就只剩省略号了。 */
private const val GROUP_TITLE_MAX = 12

/** `¥42.50` —— 自己拼两位小数。 */
private fun yuanText(value: Float): String {
    val cents = (value * 100 + 0.5).toLong()
    return "¥${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}
