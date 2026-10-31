package com.xjtu.toolbox.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.xjtu.toolbox.bulletin.BulletinNoticePanel
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.expandOriginSource
import com.xjtu.toolbox.nav.rememberExpandOriginSource
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import com.xjtu.toolbox.ui.components.GapNote
import com.xjtu.toolbox.ui.components.GradientAppIcon
import com.xjtu.toolbox.ui.components.HeroAppearance
import com.xjtu.toolbox.ui.components.HeroAppearanceDialog
import com.xjtu.toolbox.ui.components.HeroAppearanceRevision
import com.xjtu.toolbox.ui.components.HeroCustomBackground
import com.xjtu.toolbox.ui.components.HeroCustomScrim
import com.xjtu.toolbox.ui.components.HeroForestBackground
import com.xjtu.toolbox.ui.components.HeroForestScrim
import com.xjtu.toolbox.ui.components.HeroPixelBackground
import com.xjtu.toolbox.ui.components.HeroPixelScrim
import com.xjtu.toolbox.ui.components.LiquidBackground
import com.xjtu.toolbox.ui.components.MeshBackground
import com.xjtu.toolbox.ui.components.RollingNumberText
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.components.rememberLiquidMood
import com.xjtu.toolbox.ui.glass.LocalGlassStyle
import com.xjtu.toolbox.ui.isWideLayout
import com.xjtu.toolbox.ui.theme.Editorial
import com.xjtu.toolbox.ui.theme.EditorialRule
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import com.xjtu.toolbox.ui.theme.serviceColor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 首页屏。 */
@Composable
fun HomeScreen(
    host: HomeHost,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior? = null,
    /** 悬浮底栏的总占位高度：底栏浮在内容之上，页面末尾得自己留出来。 */
    extraBottomPadding: Dp = 0.dp,
    /** 玻璃顶栏的高度：内容铺到顶栏下面，这段留白放进滚动内容里。经典风格为 0。 */
    contentTopPadding: Dp = 0.dp,
    /** 壳把整页上移的量（见 `ShellPageInsets.contentTopShift`）；模糊带按它补回屏幕顶边。 */
    contentTopShift: () -> Dp = { 0.dp },
    /** 首页没有顶栏：消息、扫一扫、搜索这几个按钮由宿主给，放在页面最上面跟着内容一起滚。 */
    topActions: (@Composable () -> Unit)? = null,
) {
    val honored = host.honoredCards
    // 用目录全量：图标配色按它在目录里的下标取，过滤会让配色挪位。
    val catalog = remember(host.audience) { AppServices.homeFor(host.audience) }
    // 配色随应用主题变化，不能放进 remember。
    val colorByKey = catalog.associate { it.route.id to serviceColor(it.route.id) }

    fun rowOf(service: AppService, stat: HomeStat?): HomeServiceRow {
        val id = service.route.id
        return HomeServiceRow(
            key = service.route.id,
            icon = HomeIcons.of(service.route),
            title = service.title,
            subtitle = service.subtitle,
            color = colorByKey[id] ?: Color.Unspecified,
            onClick = { host.onRoute(service.route) },
            stat = stat?.value,
            statDetail = stat?.detail,
        )
    }

    val quickRows = host.quickRoutes.mapNotNull { route ->
        catalog.firstOrNull { it.route == route }?.let { rowOf(it, null) }
    }
    val quick: (@Composable (iconSize: Dp, contentColor: Color?) -> Unit)? =
        if (HomeCard.QUICK_ACTIONS in honored && (quickRows.isNotEmpty() || host.quickEditable)) {
            { iconSize, contentColor ->
                QuickActionsGrid(
                    pinned = quickRows,
                    all = catalog.filter { host.openableRouteIds?.contains(it.route.id) != false }.map { rowOf(it, null) },
                    editable = host.quickEditable,
                    onChange = { keys -> host.onQuickRoutesChange(keys.mapNotNull { k -> catalog.firstOrNull { it.route.id == k }?.route }) },
                    iconSize = iconSize,
                    contentColor = contentColor,
                )
            }
        } else null
    val groups = HomeCards.groups(host.audience, host.stats, host.openableRouteIds)
    val frontInbox = host.inbox.takeIf { HomeCard.INBOX in honored }

    // 功能状态就地附在分类行里，不另设展示区。
    fun indexRow(service: AppService, stat: String?, detail: String?): HomeServiceRow {
        val glance = host.glances.takeIf { HomeCard.GLANCE in honored }
            ?.firstOrNull { it.route?.id == service.route.id && it.route != AppRoute.Schedule }
        val value = if (HomeCard.CATEGORY_STATS in honored) {
            stat ?: glance?.let { g -> g.number?.let { yuanText(it.toFloat()) } ?: g.value }
                ?: HomeCards.statOf(service.route.id, host.stats)?.value
        } else null
        return rowOf(service, value?.let { HomeStat(it, detail ?: glance?.caption) })
            .copy(tone = glance?.tone ?: HomeTone.NORMAL)
    }

    val scroll = rememberScrollState()
    // 有宿主给的按钮才有折叠顶栏（桌面、Web 的壳有自己的顶栏）
    val collapsible = topActions != null
    val masthead = HomeCard.GREETING in honored
    val barPx = with(LocalDensity.current) { BAR_HEIGHT.toPx() }
    var mastheadPx by remember { mutableIntStateOf(0) }
    // 刊头先让出一栏高度、再画在顶栏下面，所以滑完整个刊头才算折叠完成
    val rangePx = if (masthead && mastheadPx > 0) mastheadPx.toFloat().coerceAtLeast(barPx) else barPx
    val progress = { (scroll.value / rangePx).coerceIn(0f, 1f) }
    // 顶栏的玻璃采这一页自己的滚动层：顶栏本身在壳的采样层里，采壳的层会采到自己
    val pageBackdrop = rememberLayerBackdrop()
    // 采样层本身没有页面底色：先垫一层不透明 surface 再采样，否则模糊把透明当黑色，
    // 字周围会出一圈脏黑边（和参考图里干净的模糊差在这里）。
    val barSurface = MiuixTheme.colorScheme.surface
    val surfaceDraw: DrawScope.() -> Unit = remember(barSurface) { { drawRect(barSurface) } }
    val glassBackdrop = rememberCombinedBackdrop(
        rememberCanvasBackdrop(surfaceDraw),
        pageBackdrop,
    )
    val glass = LocalGlassStyle.current && collapsible

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (glass) Modifier.layerBackdrop(pageBackdrop) else Modifier)
                .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
                .overScrollVertical()
                .verticalScroll(scroll)
        ) {
            Spacer(Modifier.height(contentTopPadding))
            if (masthead) {
                // 顶栏那一排按钮先占住最上面，刊头大字从它下面开始（对齐「小布记忆」的首屏）
                if (collapsible) Spacer(Modifier.height(BAR_HEIGHT))
                EditorialMasthead(
                    greeting = Greeting.of(),
                    name = host.greetingName,
                    dateLabel = host.dateLabel,
                    weekNumber = if (HomeCard.WEEK_NUMBER in honored) host.weekNumber else 0,
                    status = host.statusLine,
                    onStatus = host::onOpenStatus,
                    modifier = Modifier.enterOnce(0).onSizeChanged { mastheadPx = it.height },
                )
            } else if (collapsible) {
                Spacer(Modifier.height(BAR_HEIGHT))
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = if (masthead) 6.dp else 8.dp)
            ) {
                if (HomeCard.BULLETIN_BAR in honored && host.bulletins.isNotEmpty()) {
                    BulletinNoticePanel(
                        bulletins = host.bulletins,
                        onTap = host::onBulletinTap,
                        onDismiss = host::onBulletinDismiss,
                    )
                    Spacer(Modifier.height(SECTION_GAP))
                }
                EditorialFrontPage(
                    modifier = Modifier.enterOnce(0),
                    host = host,
                    inbox = frontInbox,
                    quick = quick?.let { { it(40.dp, null) } },
                )
            }

            if (HomeCard.SERVICE_GRID in honored && groups.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                HomeIndexCard(
                    sections = groups.map { group ->
                        IndexCategorySection(
                            title = group.category.title,
                            subtitle = group.category.subtitle,
                            accent = serviceColor(HomeIcons.accentKey(group.category)),
                            rows = group.rows.map { indexRow(it.service, it.stat, it.statDetail) },
                        )
                    },
                    modifier = Modifier.enterOnce(1),
                )
            }
            host.gapNote?.let { note ->
                Spacer(Modifier.height(SECTION_GAP))
                GapNote(note)
            }
            Spacer(Modifier.height(SECTION_GAP + extraBottomPadding + 8.dp))
        }
        if (topActions != null) {
            val scope = rememberCoroutineScope()
            HomeCollapsingBar(
                progress = progress,
                topInset = contentTopPadding,
                bandShift = contentTopShift,
                backdrop = if (glass) glassBackdrop else null,
                onTapCollapsed = { scope.launch { scroll.animateScrollTo(0) } },
                actions = topActions,
                alignWithGreeting = masthead,
            )
        }
    }
}

/**
 * 首页的折叠顶栏，叠在滚动内容上面。三个圆钮一直在右上角；往上滑过刊头后，
 * 玻璃底色和左边的方印一起浮出来，底边柔和淡出，内容从它后面模糊着滑过。折叠后点空白处回到顶部。
 */
@Composable
private fun HomeCollapsingBar(
    progress: () -> Float,
    topInset: Dp,
    bandShift: () -> Dp,
    backdrop: Backdrop?,
    onTapCollapsed: () -> Unit,
    actions: @Composable () -> Unit,
    alignWithGreeting: Boolean = false,
) {
    val collapsed by remember { derivedStateOf { progress() > 0.5f } }
    val surface = MiuixTheme.colorScheme.surface
    Box(Modifier.fillMaxWidth().height(topInset + BAR_HEIGHT)) {
        if (backdrop != null) {
            // 96dp 的模糊带比顶栏本身高，放在顶栏高度之外，才能往内容里多淡一段。
            // useplanes 的 progressive blur：七层不同半径的模糊，每层各自用一条
            // 从底边透明、到屏幕顶边最强的遮罩裁出一条带。
            // 贴的是屏幕顶边而不是这一页的本地 0 点：壳会按 stable 顶栏高度把整页上移，
            // 不补回 [bandShift] 的话整条带会被顶到屏幕外（切到过顶栏更高的 tab 再回来就复现）。
            Box(
                Modifier
                    .fillMaxWidth()
                    .requiredHeight(PROGRESSIVE_BLUR_BAND_HEIGHT)
                    .offset { IntOffset(0, bandShift().roundToPx()) },
            ) {
                ProgressiveBarBlur(backdrop, Modifier.matchParentSize())
                // 表面色遮罩是 useplanes 的可选 tint；页面预览没有开，这里也保持关闭。
                if (SHOW_TINT_WASH) {
                    Box(Modifier.matchParentSize().background(tintWash(surface)))
                }
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .then(if (backdrop == null) Modifier.background(surface) else Modifier)
                .then(
                    if (collapsed) Modifier.clickable(interactionSource = null, indication = null, onClick = onTapCollapsed)
                    else Modifier
                ),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = topInset).height(BAR_HEIGHT).padding(start = 20.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                HomeSeal.Square,
                contentDescription = "岱宗盒子",
                modifier = Modifier.size(26.dp).graphicsLayer {
                    // 红印不跟着玻璃瞬现，随着上滑慢慢淡入、放大
                    val f = progress()
                    alpha = f
                    scaleX = 0.78f + 0.22f * f
                    scaleY = scaleX
                },
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.graphicsLayer {
                translationY = if (alignWithGreeting) 0f else -BUTTON_LIFT.toPx() * (1f - progress())
            }) { actions() }
        }
    }
}

/**
 * useplanes 的 progressive blur：七层不同半径的模糊叠在一起。
 * 每层都从同一份 backdrop 采样，再用各自的渐变遮罩只留下一段：
 * 最弱的一层在底边淡出，最强的一层在屏幕顶边达到满强度，中间没有硬边。
 */
@Composable
private fun ProgressiveBarBlur(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        ProgressiveBlurRadii.forEachIndexed { index, radius ->
            val layer = remember(backdrop, index) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(0.dp) },
                    effects = {
                        blur(radius.dp.toPx(), TileMode.Decal)
                    },
                    highlight = null,
                    shadow = null,
                    onDrawFront = {
                        drawRect(
                            brush = ProgressiveBlurMasks[index],
                            blendMode = BlendMode.DstIn,
                        )
                    },
                )
            }
            Box(Modifier.matchParentSize().then(layer))
        }
    }
}

/** 展开时圆钮比顶栏正中上移的距离：顶栏里圆钮上方正好留这么多，不会顶进状态栏。 */
private val BUTTON_LIFT get() = (BAR_HEIGHT - 36.dp) / 2 - 2.dp

/** useplanes 的可选 tint：表面色从顶部 1.0 线性淡到底边 0。 */
private const val SHOW_TINT_WASH = true

/** useplanes 页面预览的顶部模糊带高度：96px。 */
private val PROGRESSIVE_BLUR_BAND_HEIGHT = 96.dp

/** useplanes 页面预览顶部带的七层模糊半径（默认 ramp × 1.5）。 */
private val ProgressiveBlurRadii = listOf(0.75f, 1.5f, 3f, 4.5f, 7.5f, 12f, 18f)

/**
 * 每层遮罩占整条带的比例：useplanes 的层与层之间错开 12.5%，
 * 每层是「透明 → 不透明 → 不透明 → 透明」的一条带。
 */
private const val PROGRESSIVE_BLUR_STEP = 0.125f

/**
 * 顶栏的模糊从屏幕顶边往下递减，所以遮罩按从底边往上数的位置生成：
 * 第 0 层（0.75dp）在底边附近淡出，第 6 层（18dp）在顶边满强度。
 */
private val ProgressiveBlurMasks: List<Brush> = ProgressiveBlurRadii.indices.map { index ->
    val stops = listOf(
        (1f - (index + 3) * PROGRESSIVE_BLUR_STEP) to 0f,
        (1f - (index + 2) * PROGRESSIVE_BLUR_STEP) to 1f,
        (1f - (index + 1) * PROGRESSIVE_BLUR_STEP) to 1f,
        (1f - index * PROGRESSIVE_BLUR_STEP) to 0f,
    ).clipToUnitRange()
    Brush.verticalGradient(
        *stops.map { (position, alpha) ->
            position to Color.Black.copy(alpha = alpha)
        }.toTypedArray(),
    )
}

/** 把越出 [0, 1] 的渐变端点裁掉，并在边界上按原斜率补一个端点。 */
private fun List<Pair<Float, Float>>.clipToUnitRange(): List<Pair<Float, Float>> {
    fun alphaAt(position: Float): Float {
        val before = lastOrNull { it.first <= position } ?: first()
        val after = firstOrNull { it.first >= position } ?: last()
        if (before.first == after.first) return after.second
        val t = (position - before.first) / (after.first - before.first)
        return before.second + (after.second - before.second) * t
    }

    val visible = filter { it.first in 0f..1f }.toMutableList()
    if (first().first > 0f) visible.add(0, 0f to alphaAt(0f))
    if (last().first < 1f) visible.add(1f to alphaAt(1f))
    return visible.sortedBy { it.first }
}

/** 顶栏表面色遮罩：顶部完全不透明，到底边线性归零。 */
private fun tintWash(surface: Color): Brush = Brush.verticalGradient(
    0f to surface.copy(alpha = 1f),
    1f to surface.copy(alpha = 0f),
)

// ── 首页版式 ──

/** 分类面板：白色卡面、细描边与柔和投影；今日安排使用蓝色底。 */
@Composable
private fun Modifier.editorialPaper(
    radius: Dp = EDITORIAL_RADIUS,
    tinted: Boolean = false,
): Modifier {
    val dark = LocalIsDarkTheme.current
    val base = AppCardColor
    val fill = when {
        dark && tinted -> lerp(base, Color(0xFFA0B9FB), 0.22f)
        dark -> base
        tinted -> Color(0xFFA0B9FB)
        else -> base
    }
    val border = MiuixTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.09f else 0.065f)
    return this
        .then(if (dark) Modifier else Modifier.appCardShadow(shape = RoundedCornerShape(radius)))
        .squircleClip(radius)
        .background(fill)
        .squircleBorder(0.6.dp, border, radius)
}

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
    onStatus: () -> Unit,
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
                    Text("第 $weekNumber 周", fontSize = 12.sp,
                        color = if (LocalIsDarkTheme.current) lerp(HomeSeal.Red, ink, 0.35f) else HomeSeal.Red,
                        maxLines = 1)
                }
                if (status != null) {
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        HeroStatusLine(status, onStatus)
                    }
                }
            }
        }
    }
}

/** 今日安排只放时间相关的信息；所有功能及其状态统一放在下面的服务区。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun EditorialFrontPage(
    modifier: Modifier = Modifier,
    host: HomeHost,
    inbox: HomeInboxSummary?,
    quick: (@Composable () -> Unit)?,
) {
    val scheduled = host.nextUp.takeIf { HomeCard.NEXT_UP in host.honoredCards }
    // 待办截止比下一节课 / 日程还早，就让它顶上「下一项」，待办那一行不再重复它
    val dueLead = HomeHero.dueLead(scheduled, inbox?.nextDue)
    val nextUp = dueLead ?: scheduled
    @Suppress("NAME_SHADOWING")
    val inbox = if (dueLead != null) inbox?.copy(nextTodo = null) else inbox
    val exam = host.glances.takeIf { HomeCard.GLANCE in host.honoredCards }
        ?.firstOrNull { it.route == AppRoute.Schedule }
    if (nextUp == null && inbox == null && exam == null && quick == null) return

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
                        EditorialNextUpLead(it, ink, muted, accent, onLongClick = configure) { it.route?.let(host::onRoute) ?: host.onProfile() }
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
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                                tint = muted.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
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
        // 常用功能独立成一张白卡，和下面的分类卡同一套纸面。
        if (quick != null) {
            if (hasLead) Spacer(Modifier.height(SECTION_GAP))
            Box(Modifier.fillMaxWidth().editorialPaper()) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) { quick() }
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

private data class IndexCategorySection(
    val title: String,
    val subtitle: String,
    val accent: Color,
    val rows: List<HomeServiceRow>,
)

/** 分类容器承载完整入口；状态直接附在入口下方，避免与展示卡重复。 */
@Composable
private fun HomeIndexCard(
    sections: List<IndexCategorySection>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (isWideLayout()) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                balancedColumns(sections.size) { 2 + (sections[it].rows.size + 1) / 2 }.forEach { column ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        column.forEach { ServiceSection(sections[it]) }
                    }
                }
            }
        } else {
            sections.forEachIndexed { index, section ->
                ServiceSection(section)
                if (index < sections.lastIndex) Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun ServiceSection(section: IndexCategorySection) {
    val dark = LocalIsDarkTheme.current
    val accent = if (dark) lerp(section.accent, Color.White, 0.3f) else section.accent
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(
        Modifier.fillMaxWidth().editorialPaper()
            .drawBehind {
                drawRect(Brush.radialGradient(
                    listOf(accent.copy(alpha = if (dark) 0.09f else 0.055f), Color.Transparent),
                    center = Offset(size.width, 0f), radius = size.width * 0.9f,
                ))
            }.padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 5.dp, end = 5.dp, top = 5.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(28.dp).background(accent, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(section.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ink)
                Spacer(Modifier.height(3.dp))
                Text(section.subtitle, fontSize = 11.sp, color = muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        section.rows.chunked(2).forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                line.forEach { IndexEntry(it, Modifier.weight(1f).fillMaxHeight()) }
                if (line.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun IndexEntry(row: HomeServiceRow, modifier: Modifier = Modifier) {
    val dark = LocalIsDarkTheme.current
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val tile = if (dark) lerp(AppCardColor, Color.White, 0.045f)
        else lerp(AppCardColor, MiuixTheme.colorScheme.surface, 0.65f)
    val iconColor = if (dark) lerp(row.color, Color.White, 0.4f) else row.color
    Row(
        modifier.background(tile, RoundedCornerShape(15.dp))
            .opensRow(row, 15.dp) { m -> IndexEntrySnapshot(row, m) }
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IndexEntryBody(row, dark, ink, muted, iconColor)
    }
}

/** 返回转场里画在窗口中的行块；和 [IndexEntry] 用同一套内容与底色，落到原位时不会跳。 */
@Composable
private fun IndexEntrySnapshot(row: HomeServiceRow, modifier: Modifier = Modifier) {
    val dark = LocalIsDarkTheme.current
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val tile = if (dark) lerp(AppCardColor, Color.White, 0.045f)
        else lerp(AppCardColor, MiuixTheme.colorScheme.surface, 0.65f)
    val iconColor = if (dark) lerp(row.color, Color.White, 0.4f) else row.color
    Row(
        modifier.fillMaxSize()
            .background(tile, RoundedCornerShape(15.dp))
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IndexEntryBody(row, dark, ink, muted, iconColor)
    }
}

@Composable
private fun RowScope.IndexEntryBody(
    row: HomeServiceRow,
    dark: Boolean,
    ink: Color,
    muted: Color,
    iconColor: Color,
) {
    Box(Modifier.size(34.dp).background(row.color.copy(alpha = if (dark) 0.18f else 0.10f),
        RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
        Icon(row.icon, null, tint = iconColor, modifier = Modifier.size(19.dp))
    }
    Spacer(Modifier.width(9.dp))
    Column(Modifier.weight(1f)) {
        Text(row.title, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        Text(row.stat ?: row.subtitle,
            fontSize = 11.sp, lineHeight = 15.sp,
            fontWeight = if (row.stat != null) FontWeight.Medium else FontWeight.Normal,
            color = when {
                row.stat == null || row.tone == HomeTone.MUTED -> muted
                row.tone == HomeTone.ALERT -> MiuixTheme.colorScheme.error
                row.tone == HomeTone.ACCENT -> iconColor
                else -> lerp(row.color, ink, if (dark) 0.55f else 0.25f)
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        row.statDetail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, fontSize = 10.5.sp, lineHeight = 14.sp, color = muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HeroStatusLine(
    status: HomeStatusLine,
    onClick: () -> Unit,
) {
    val tint = when (status.tone) {
        HomeTone.ACCENT -> STATUS_BLUE
        HomeTone.ALERT -> MiuixTheme.colorScheme.error
        HomeTone.NORMAL -> STATUS_GREEN
        HomeTone.MUTED -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val dot = @Composable {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tint)
        )
    }
    val click = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = SinkFeedback(),
        onClick = onClick,
    )
    Row(click, verticalAlignment = Alignment.CenterVertically) {
        dot()
        Spacer(Modifier.width(5.dp))
        Text(
            status.label,
            style = MiuixTheme.textStyles.footnote2,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(Icons.Default.ChevronRight, contentDescription = "查看子系统连接状态", tint = muted, modifier = Modifier.size(13.dp))
    }
}

/** 宽屏分类分成高度接近的两列。 */
private fun balancedColumns(count: Int, weight: (Int) -> Int): List<List<Int>> {
    val w = List(count) { weight(it) }
    fun inRight(mask: Int, i: Int) = (mask shr i) and 1 == 1
    val best = (0 until (1 shl count) step 2).minByOrNull { mask ->
        abs(w.indices.sumOf { if (inRight(mask, it)) w[it] else -w[it] })
    } ?: 0
    return w.indices.partition { !inRight(best, it) }.toList()
}

/** 首页服务条目。 */
internal data class HomeServiceRow(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String = "",
    val color: Color,
    val onClick: () -> Unit,
    val stat: String? = null,
    val statDetail: String? = null,
    val tone: HomeTone = HomeTone.NORMAL,
)

/** 点开功能页，并让功能页从这一块放大出来。 */
@Composable
private fun Modifier.opensRow(
    row: HomeServiceRow,
    radius: Dp = 12.dp,
    snapshot: (@Composable (Modifier) -> Unit)? = null,
): Modifier {
    val origin = rememberExpandOriginSource()
    val density = LocalDensity.current
    return this
        .expandOriginSource(origin)
        .clip(RoundedCornerShape(radius))
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = SinkFeedback(),
            onClick = {
                origin.arm(row.key, radius, density, snapshot)
                row.onClick()
            },
        )
}

/** 首页各卡之间统一的间距。 */
private val SECTION_GAP = 14.dp

/** 液体卡上承托白字的暗色罩：顶部稍重、中间最透、底部再压回来。 */
private val LiquidCardScrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.34f),
    0.45f to Color.Black.copy(alpha = 0.22f),
    1f to Color.Black.copy(alpha = 0.38f),
)

/** 液体卡上的发丝线：白线，比主题描边亮一档。 */

/** 液体卡上的暖色点缀，只用在小圆点和考试图标上，不承字。 */
private val LiquidCardAccent = Color(0xFFFFB4A6)

/** 首页折叠顶栏（状态栏以下）的高度，三个 36dp 圆钮在里面居中。 */
private val BAR_HEIGHT = 52.dp

/** 杂志版式的卡片圆角。 */
private val EDITORIAL_RADIUS = 24.dp

/** 教学周超过这个数就不显示（假期或学期没对上）。 */
private const val MAX_TEACHING_WEEK = 25

private val STATUS_GREEN = Color(0xFF2E7D32)
private val STATUS_BLUE = Color(0xFF1565C0)

/** `¥42.50` —— 自己拼两位小数。 */
private fun yuanText(value: Float): String {
    val cents = (value * 100 + 0.5).toLong()
    return "¥${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}
