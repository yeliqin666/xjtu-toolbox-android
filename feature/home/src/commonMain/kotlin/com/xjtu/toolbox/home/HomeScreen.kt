package com.xjtu.toolbox.home

import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.theme.Editorial
import com.xjtu.toolbox.ui.theme.EditorialRule
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.xjtu.toolbox.ui.glass.LocalGlassStyle
import com.xjtu.toolbox.ui.glass.glassBarSurface
import com.xjtu.toolbox.ui.glass.glassBarTint
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.bulletin.BulletinNoticePanel
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.expandOriginSource
import com.xjtu.toolbox.nav.rememberExpandOriginSource
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import com.xjtu.toolbox.ui.components.GradientAppIcon
import com.xjtu.toolbox.ui.components.MeshBackground
import com.xjtu.toolbox.ui.components.RollingNumberText
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.isWideLayout
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import com.xjtu.toolbox.ui.theme.legacyColor
import com.xjtu.toolbox.ui.theme.serviceColor
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
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
    /** 首页没有顶栏：消息、扫一扫、搜索这几个按钮由宿主给，放在页面最上面跟着内容一起滚。 */
    topActions: (@Composable () -> Unit)? = null,
) {
    val honored = host.honoredCards
    // 用目录全量：图标配色按它在目录里的下标取，过滤会让配色挪位。
    val catalog = remember(host.audience) { AppServices.homeFor(host.audience) }
    // `serviceColor` / `legacyColor` 是 @Composable，不能放进 `remember`。
    val iconColorByKey = catalog.mapIndexed { index, svc -> svc.route.id to serviceColor(index, catalog.size) }.toMap()
    val legacyColorByKey = catalog.associate { it.route.id to legacyColor(it.route.id) }
    val isIconTheme = host.homeTheme == SettingsStore.THEME_ICON

    fun rowOf(service: AppService, stat: HomeStat?): HomeServiceRow {
        val id = service.route.id
        return HomeServiceRow(
            key = service.route.id,
            icon = HomeIcons.of(service.route),
            title = service.title,
            // 图标主题的配色按「它在目录里的位置」取，卡片主题用那一条自己的历史色
            color = if (isIconTheme) iconColorByKey[id] ?: legacyColorByKey[id] ?: Color.Unspecified else legacyColorByKey[id] ?: Color.Unspecified,
            onClick = { host.onRoute(service.route) },
            stat = stat?.value,
            statDetail = stat?.detail,
        )
    }

    val quickRows = host.quickRoutes.mapNotNull { route ->
        catalog.firstOrNull { it.route == route }?.let { rowOf(it, null) }
    }
    val groups = HomeCards.groups(host.audience, host.stats, host.openableRouteIds)
    // 杂志版式的头版：各功能此刻的状态都收进「今日」卡，和速览、下一节课重复的不再列
    val frontFigures = if (isIconTheme || HomeCard.CATEGORY_STATS !in honored) emptyList() else {
        val covered = buildSet {
            host.glances.takeIf { HomeCard.GLANCE in honored }.orEmpty().forEach { g -> g.route?.let { add(it.id) } }
            if (HomeCard.NEXT_UP in honored && host.nextUp != null) add(AppRoute.Schedule.id)
        }
        groups.flatMap { it.rows }
            .filter { it.stat != null && it.key !in covered }
            .map { rowOf(it.service, HomeStat(it.stat!!, it.statDetail)) }
    }
    val frontInbox = host.inbox.takeIf { !isIconTheme && HomeCard.INBOX in honored }

    val headerSection: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
        ) {
            if (HomeCard.BULLETIN_BAR in honored && host.bulletins.isNotEmpty()) {
                BulletinNoticePanel(
                    bulletins = host.bulletins,
                    onTap = host::onBulletinTap,
                    onDismiss = host::onBulletinDismiss,
                )
                Spacer(Modifier.height(SECTION_GAP))
            }
            HomeHeroCard(
                modifier = Modifier.enterOnce(0),
                host = host,
                dateLabel = host.dateLabel,
                weekNumber = if (HomeCard.WEEK_NUMBER in honored) host.weekNumber else 0,
                quickRows = quickRows,
                isIconTheme = isIconTheme,
                onOpenCourses = { host.onRoute(AppRoute.Schedule) },
                figures = frontFigures,
                inbox = frontInbox,
            )
        }
    }

    val categorySection: @Composable () -> Unit = {
        if (HomeCard.SERVICE_GRID in honored && groups.isNotEmpty()) {
            if (isIconTheme) {
                // 彩虹主题：实心渐变的 App 式图标 + 4 列宫格，回答「有哪些功能」。
                CategoryCards(count = groups.size, spacing = SECTION_GAP) { index ->
                    val group = groups[index]
                    HomeCategoryCard(
                        title = group.category.title,
                        subtitle = group.category.subtitle,
                        accent = legacyColor(HomeIcons.accentKey(group.category)),
                        rows = group.rows.map { rowOf(it.service, null) },
                    )
                }
            } else {
                // 卡片主题（杂志版式）：状态都上了头版，这里只是一页目录
                HomeIndexCard(
                    sections = groups.map { group -> group.category.title to group.rows.map { rowOf(it.service, null) } },
                    modifier = Modifier.enterOnce(1),
                )
            }
        }
    }

    val scroll = rememberScrollState()
    // 有宿主给的按钮才有折叠顶栏（桌面、Web 的壳有自己的顶栏）
    val collapsible = topActions != null
    val masthead = !isIconTheme && HomeCard.GREETING in honored
    val barPx = with(LocalDensity.current) { BAR_HEIGHT.toPx() }
    var mastheadPx by remember { mutableIntStateOf(0) }
    // 刊头底边碰到顶栏底边时折叠完成；没有刊头时只让顶栏底色在一栏高度内浮出来
    val rangePx = if (masthead && mastheadPx > 0) (mastheadPx - barPx).coerceAtLeast(barPx) else barPx
    val progress = { (scroll.value / rangePx).coerceIn(0f, 1f) }
    if (collapsible) SnapCollapse(scroll, rangePx)
    // 顶栏的玻璃采这一页自己的滚动层：顶栏本身在壳的采样层里，采壳的层会采到自己
    val pageBackdrop = rememberLayerBackdrop()
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
            EditorialMasthead(
                greeting = Greeting.of(),
                name = host.greetingName,
                weekNumber = if (HomeCard.WEEK_NUMBER in honored) host.weekNumber else 0,
                status = host.statusLine,
                onStatus = host::onOpenStatus,
                underBar = collapsible,
                progress = progress,
                modifier = Modifier.enterOnce(0).onSizeChanged { mastheadPx = it.height },
            )
        } else if (collapsible) {
            Spacer(Modifier.height(BAR_HEIGHT))
        }
        headerSection()
        host.inbox?.let { summary ->
            if (HomeCard.INBOX in honored && isIconTheme) {
                Spacer(Modifier.height(SECTION_GAP))
                HomeInboxCard(summary) { host.onRoute(AppRoute.Inbox) }
            }
        }
        Spacer(Modifier.height(SECTION_GAP))
        categorySection()
        Spacer(Modifier.height(SECTION_GAP + extraBottomPadding))
    }
    if (topActions != null) {
        val scope = rememberCoroutineScope()
        HomeCollapsingBar(
            progress = progress,
            topInset = contentTopPadding,
            backdrop = if (glass) pageBackdrop else null,
            onTapCollapsed = { scope.launch { scroll.animateScrollTo(0) } },
            actions = topActions,
        )
    }
    }
}

/** 停手时折叠到一半的顶栏吸到最近的一端，和 MIUIX 大标题的手感一致。 */
@Composable
private fun SnapCollapse(scroll: ScrollState, rangePx: Float) {
    LaunchedEffect(scroll, rangePx) {
        snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            val v = scroll.value
            // 内容不够折叠一整段时不吸，否则会对着滚不到的位置来回动画
            if (moving || v <= 0 || v >= rangePx || rangePx > scroll.maxValue) return@collect
            scroll.animateScrollTo(if (v < rangePx / 2) 0 else rangePx.roundToInt())
        }
    }
}

/**
 * 首页的折叠顶栏，叠在滚动内容上面。三个圆钮一直在右上角；往上滑过刊头后，
 * 玻璃底色、底边发丝线和左边的方印一起浮出来，内容从它后面模糊着滑过。折叠后点空白处回到顶部。
 */
@Composable
private fun HomeCollapsingBar(
    progress: () -> Float,
    topInset: Dp,
    backdrop: LayerBackdrop?,
    onTapCollapsed: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val collapsed by remember { derivedStateOf { progress() > 0.5f } }
    val tint = glassBarTint()
    val surface = MiuixTheme.colorScheme.surface
    Box(Modifier.fillMaxWidth().height(topInset + BAR_HEIGHT)) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = ramp(progress(), 0.55f) }
                .then(if (backdrop != null) Modifier.glassBarSurface(backdrop, tint) else Modifier.background(surface))
                // 折叠后顶栏挡住的内容不该再被点到
                .then(
                    if (collapsed) Modifier.clickable(interactionSource = null, indication = null, onClick = onTapCollapsed)
                    else Modifier
                ),
        )
        EditorialRule(Modifier.align(Alignment.BottomStart).graphicsLayer { alpha = ramp(progress(), 0.7f) })
        Row(
            // 方印和刊头的竖长印、卡片里的文字落在同一条竖线上
            Modifier.fillMaxWidth().padding(top = topInset).height(BAR_HEIGHT).padding(start = 36.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                HomeSeal.Square,
                contentDescription = "岱宗盒子",
                modifier = Modifier.size(30.dp).graphicsLayer {
                    val f = ramp(progress(), 0.6f)
                    alpha = f
                    scaleX = 0.7f + 0.3f * f
                    scaleY = scaleX
                },
            )
            Spacer(Modifier.weight(1f))
            // 展开时圆钮贴着状态栏下沿，离问候远一点；折叠后回到细顶栏正中
            Box(Modifier.graphicsLayer { translationY = -BUTTON_LIFT.toPx() * (1f - progress()) }) { actions() }
        }
    }
}

/** 展开时圆钮比顶栏正中上移的距离：顶栏里圆钮上方正好留这么多，不会顶进状态栏。 */
private val BUTTON_LIFT get() = (BAR_HEIGHT - 36.dp) / 2 - 2.dp

/** [p] 从 [start] 走到 1 时线性地从 0 升到 1。 */
private fun ramp(p: Float, start: Float): Float = ((p - start) / (1f - start)).coerceIn(0f, 1f)

/** 首页的「今日」卡：一张卡装下此刻最该看的全部东西。 */
@Composable
private fun HomeHeroCard(
    modifier: Modifier = Modifier,
    host: HomeHost,
    dateLabel: String,
    weekNumber: Int,
    quickRows: List<HomeServiceRow>,
    isIconTheme: Boolean,
    onOpenCourses: () -> Unit,
    /** 杂志版式头版上各功能此刻的状态（已去掉和速览、下一节课重复的）。 */
    figures: List<HomeServiceRow> = emptyList(),
    /** 杂志版式头版上的「待办与消息」。 */
    inbox: HomeInboxSummary? = null,
) {
    /** 卡片主题的杂志版式：去掉流动渐变和内嵌小面板，层次靠字号与留白。 */
    val editorial = !isIconTheme
    val honored = host.honoredCards
    val greeting = Greeting.of()
    val headline = if (host.greetingName.isBlank()) greeting else "$greeting，${host.greetingName}"
    val meta = buildString {
        append(dateLabel)
        if (HomeCard.WEEK_NUMBER in honored && weekNumber in 1..25) append(" · 第${weekNumber}周")
    }
    val primary = MiuixTheme.colorScheme.primary
    val status = host.statusLine.takeIf { HomeCard.GREETING in honored }
    val nextUp = host.nextUp.takeIf { HomeCard.NEXT_UP in honored }
    val glances = host.glances.takeIf { HomeCard.GLANCE in honored }.orEmpty()
    val showQuick = HomeCard.QUICK_ACTIONS in honored && quickRows.isNotEmpty()
    // 杂志版式的卡里没有问候，什么都没有就不画一张空卡
    if (editorial && nextUp == null && glances.isEmpty() && figures.isEmpty() && inbox == null && !showQuick) return

    Box(
        modifier
            .fillMaxWidth()
            .appCardShadow(shape = RoundedCornerShape(CARD_RADIUS), strong = true)
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor),
    ) {
        if (!editorial) Box(Modifier.matchParentSize()) {
            // 底色是缓慢流动的 Mesh 渐变，顶点颜色由主题色按不同浓度混进卡片底色，Monet 取色下也跟着走。
            val cardBase = AppCardColor
            val heroMesh = remember(primary, cardBase) {
                HeroMeshWeights.map { line -> line.map { t -> lerp(cardBase, primary, t) } }
            }
            MeshBackground(
                modifier = Modifier.matchParentSize(),
                lightVertexColors = heroMesh,
                darkVertexColors = heroMesh,
                // 卡片在玻璃顶栏/底栏的取样范围里，一直流动会让静止的首页也持续重模糊，流动一小段就停。
                runForMillis = 6_000L,
            )
        }
        if (!editorial) host.heroArt?.invoke(this)
        // 状态、待办陆续读到时卡片平滑长高，不把下面的目录一下顶走
        Column(Modifier.fillMaxWidth().animateContentSize().padding(14.dp)) {
            // 杂志版式的刊头在卡外（见 [EditorialMasthead]），这张卡只装「今日」
            if (HomeCard.GREETING in honored && !editorial) {
                Column(Modifier.padding(start = 6.dp, top = 8.dp, end = HERO_ART_SIZE)) {
                    Text(
                        meta,
                        style = MiuixTheme.textStyles.footnote1,
                        color = primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        headline,
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (status != null) {
                        Spacer(Modifier.height(8.dp))
                        HeroStatusLine(status, host::onOpenStatus)
                    }
                }
                Spacer(Modifier.height(if (status != null) 18.dp else 30.dp))
            }
            if (HomeCard.NEXT_UP in honored && nextUp != null) {
                HeroNextUp(
                    nextUp = nextUp,
                    editorial = editorial,
                    onClick = { nextUp.route?.let { route -> host.onRoute(route) } ?: host.onProfile() },
                )
            }
            // 速览格；杂志版式再接上各功能此刻的状态，三格一行
            val cells = buildList<@Composable (Modifier) -> Unit> {
                glances.forEach { glance ->
                    add { m ->
                        HeroGlance(
                            editorial = editorial,
                            icon = glance.icon,
                            label = glance.label,
                            value = glance.value,
                            caption = glance.caption,
                            number = glance.number,
                            valueColor = when (glance.tone) {
                                HomeTone.ACCENT -> primary
                                HomeTone.ALERT -> MiuixTheme.colorScheme.error
                                HomeTone.NORMAL -> MiuixTheme.colorScheme.onSurface
                                HomeTone.MUTED -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            onClick = { glance.route?.let { host.onRoute(it) } },
                            modifier = m,
                        )
                    }
                }
                figures.forEach { row ->
                    add { m ->
                        HeroGlance(
                            editorial = true,
                            icon = row.icon,
                            label = row.title,
                            value = row.stat.orEmpty(),
                            caption = row.statDetail,
                            onClick = row.onClick,
                            modifier = m,
                        )
                    }
                }
            }
            if (cells.isNotEmpty()) {
                if (!editorial) Spacer(Modifier.height(8.dp))
                val perRow = if (editorial) 3 else cells.size
                cells.chunked(perRow).forEach { chunk ->
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(if (editorial) 0.dp else 8.dp),
                    ) {
                        chunk.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                        repeat(perRow - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (inbox != null) {
                Spacer(Modifier.height(4.dp))
                InboxBrief(inbox) { host.onRoute(AppRoute.Inbox) }
            }
            if (editorial) {
                val openable = host.openableRouteIds
                val invites = listOfNotNull(
                    Triple(AppRoute.Agent, "问屁岱", "「明天早八在哪？」"),
                    Triple(AppRoute.Community, "社区", "说说哪儿不好用"),
                ).filter { openable == null || it.first.id in openable }
                if (invites.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        invites.forEach { (route, title, hint) ->
                            InviteCell(route, title, hint, Modifier.weight(1f).fillMaxHeight()) { host.onRoute(route) }
                        }
                    }
                }
            }
            if (showQuick) {
                Spacer(Modifier.height(if (editorial && nextUp == null && cells.isEmpty() && inbox == null) 0.dp else if (editorial) 6.dp else 12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        // 杂志版式：快捷入口收在一条淡底托盘里，靠底色成组，不靠分割线
                        .then(if (editorial) Modifier.squircleBackground(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f), 18.dp) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    quickRows.forEach { row ->
                        if (editorial) EditorialQuickLink(row, Modifier.weight(1f))
                        else HomeQuickAction(
                            row.icon,
                            row.title,
                            row.color,
                            onClick = row.onClick,
                            modifier = Modifier.weight(1f),
                            originKey = row.key,
                            iconSize = 44.dp,
                            solidIcon = isIconTheme,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 杂志版式的刊头，直接铺在页面底色上，三栏咬合成一块：左边竖长印；中间大号宋体问候，
 * 一小截朱红短线，称呼，底下贴着子系统状态；右下是年月、周次、星期和大号衬线日期，
 * 右上那一角留给折叠顶栏的圆钮（[underBar]）。往上滑时整块随 [progress] 渐隐、略微收小。
 */
@Composable
private fun EditorialMasthead(
    greeting: String,
    name: String,
    weekNumber: Int,
    status: HomeStatusLine?,
    onStatus: () -> Unit,
    underBar: Boolean,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    today: LocalDate = todayOnCampus(),
) {
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            // 和下面卡片里的文字对齐（卡片外边距 16 + 内边距 20）
            .padding(horizontal = 36.dp)
            // 印章顶边和顶栏圆钮的顶边齐平
            .padding(top = if (underBar) (BAR_HEIGHT - 36.dp) / 2 else 12.dp, bottom = 6.dp)
            .graphicsLayer {
                val p = progress()
                alpha = (1f - p * 1.4f).coerceIn(0f, 1f)
                scaleX = 1f - 0.04f * p
                scaleY = scaleX
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
    ) {
        Image(HomeSeal.Tall, contentDescription = "岱宗盒子", modifier = Modifier.size(width = 30.dp, height = 109.dp))
        Spacer(Modifier.width(16.dp))
        // 一列文字贴着印章：星期与周次、问候、朱红短线、日期称呼与网络，分量中—大—小。
        // 刊头高度由印章定死，数据晚到也不跳
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chineseWeekday(today.dayOfWeek),
                    style = TextStyle(fontFamily = Editorial.Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 2.sp),
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (weekNumber in 1..25) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "第${weekNumber}周",
                        style = TextStyle(fontFamily = Editorial.Serif, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 1.sp),
                        color = HomeSeal.Red,
                        maxLines = 1,
                        modifier = Modifier
                            .border(1.dp, HomeSeal.Red.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Column {
                Text(
                    if (name.isBlank()) greeting else "$greeting，$name",
                    style = TextStyle(fontFamily = Editorial.Serif, fontWeight = FontWeight.Black, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = 2.sp),
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.padding(top = 6.dp).width(24.dp).height(2.dp).background(HomeSeal.Red, RoundedCornerShape(1.dp)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${today.month.number}月${today.day}日 · ",
                    style = TextStyle(fontFamily = Editorial.Serif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 1.sp),
                    color = muted,
                    maxLines = 1,
                )
                if (status != null) HeroStatusLine(status, onStatus, compact = true)
            }
        }
    }
}

private fun chineseWeekday(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "星期一"
    DayOfWeek.TUESDAY -> "星期二"
    DayOfWeek.WEDNESDAY -> "星期三"
    DayOfWeek.THURSDAY -> "星期四"
    DayOfWeek.FRIDAY -> "星期五"
    DayOfWeek.SATURDAY -> "星期六"
    DayOfWeek.SUNDAY -> "星期日"
}

/** Hero 里半透明的内嵌面板：叠在 Mesh 上像一层磨砂，比再套一张实心卡轻。 */
@Composable
private fun Modifier.heroInset(radius: Dp = 18.dp): Modifier {
    val dark = LocalIsDarkTheme.current
    return this
        .squircleClip(radius)
        .background(AppCardColor.copy(alpha = if (dark) 0.55f else 0.72f))
        .squircleBorder(1.dp, Color.White.copy(alpha = if (dark) 0.06f else 0.6f), radius)
}

@Composable
private fun HeroStatusLine(
    status: HomeStatusLine,
    onClick: () -> Unit,
    /** 刊头那一行：小一号字，名称和子系统个数之间不留多余空隙。 */
    compact: Boolean = false,
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
                .size(7.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(tint)
        )
    }
    val click = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = SinkFeedback(),
        onClick = onClick,
    )
    if (compact) {
        Row(click, verticalAlignment = Alignment.CenterVertically) {
            dot()
            Spacer(Modifier.width(5.dp))
            Text(
                "${status.label} · ${status.detail}",
                style = MiuixTheme.textStyles.footnote2,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ChevronRight, contentDescription = "查看子系统连接状态", tint = muted, modifier = Modifier.size(13.dp))
        }
        return
    }
    Row(click, verticalAlignment = Alignment.CenterVertically) {
        dot()
        Spacer(Modifier.width(6.dp))
        Text(
            "${status.label} · ${status.detail}",
            style = MiuixTheme.textStyles.footnote1,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "查看子系统连接状态",
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** 下一项安排：左边钟点，右边名称与倒计时，整条点进日程。 */
@Composable
private fun HeroNextUp(
    nextUp: HomeNextUp?,
    /** 杂志版式：不套内嵌面板，钟点和课名用衬线，课名是这一页的「头条」。 */
    editorial: Boolean = false,
    onClick: () -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (editorial) Modifier else Modifier.heroInset())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            // 杂志版式和刊头左边对齐
            .padding(horizontal = if (editorial) 6.dp else 14.dp, vertical = if (editorial) 14.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 屏只在宿主真给了内容时才画这一格（宿主把四种「说不出来」也当内容递进来）
        val item = nextUp ?: return
        val timeLabel = item.timeLabel
        var detail: AnnotatedString? = null
        if (timeLabel != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    timeLabel,
                    style = if (editorial) Editorial.figure else MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = primary,
                )
                Text(
                    item.timeCaption.orEmpty(),
                    style = MiuixTheme.textStyles.footnote2,
                    color = muted,
                )
            }
            if (editorial) {
                Spacer(Modifier.width(18.dp))
            } else {
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .width(1.dp)
                        .height(30.dp)
                        .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.18f))
                )
                Spacer(Modifier.width(12.dp))
            }
            detail = buildAnnotatedString {
                val eta = item.eta
                if (!eta.isNullOrBlank()) {
                    pushStyle(SpanStyle(color = primary, fontWeight = FontWeight.Medium))
                    append(eta)
                    pop()
                }
                val tail = item.detail
                if (!tail.isNullOrBlank()) append((if (eta.isNullOrBlank()) "" else " · ") + tail)
            }
        } else {
            if (!editorial) {
                ExpressiveIcon(icon = item.icon, color = primary, size = 38.dp, iconSize = 20.dp)
                Spacer(Modifier.width(12.dp))
            }
            item.detail?.takeIf { it.isNotBlank() }?.let { detail = AnnotatedString(it) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = if (editorial) Editorial.headline.copy(fontSize = 19.sp, lineHeight = 25.sp) else MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
    }
}

/** 速览格：小标签在上、数值在下。 */
@Composable
private fun HeroGlance(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color = MiuixTheme.colorScheme.onSurface,
    /** 金额类传数值：第一次从 0 滚上来，之后随缓存刷新从旧值滚到新值。 */
    number: Double? = null,
    /** 数值下面的一行小字，比如余额的「约够 4 天」。 */
    caption: String? = null,
    /** 杂志版式：不套内嵌面板，数值用衬线数字，格与格之间由调用方画竖线。 */
    editorial: Boolean = false,
) {
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val valueStyle = if (editorial) Editorial.figure.copy(fontSize = 18.sp, lineHeight = 23.sp) else MiuixTheme.textStyles.body1
    Column(
        modifier
            .then(if (editorial) Modifier else Modifier.heroInset(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            .padding(horizontal = if (editorial) 6.dp else 12.dp, vertical = if (editorial) 12.dp else 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = muted, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MiuixTheme.textStyles.footnote2, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        if (number != null) {
            RollingNumberText(
                value = number,
                format = { yuanText(it) },
                style = valueStyle,
                fontWeight = FontWeight.Bold,
                color = valueColor,
            )
        } else {
            Text(
                value,
                style = valueStyle,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (caption != null) {
            Text(caption, style = MiuixTheme.textStyles.footnote2, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 「待办与消息」那一格。 */
@Composable
private fun HomeInboxCard(summary: HomeInboxSummary, onClick: () -> Unit) {
    val accent = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .appCardShadow(shape = RoundedCornerShape(CARD_RADIUS))
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dark = LocalIsDarkTheme.current
            Box(
                Modifier
                    .size(32.dp)
                    .squircleBackground(accent.copy(alpha = if (dark) 0.24f else 0.12f), 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Notifications, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("待办与消息", style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        summary.isEmpty -> "没有要办的事，也没有未读"
                        summary.todoCount > 0 && summary.unreadCount > 0 ->
                            "还有 ${summary.todoCount} 件没办 · ${summary.unreadCount} 组没读"
                        summary.todoCount > 0 -> "还有 ${summary.todoCount} 件没办"
                        else -> "${summary.unreadCount} 组消息没读"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
        }
        summary.nextTodo?.let { todo ->
            Spacer(Modifier.height(12.dp))
            Text("最要紧的一件", style = MiuixTheme.textStyles.footnote2, color = muted, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                todo,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 分类卡的排布。 */
@Composable
private fun CategoryCards(
    count: Int,
    spacing: Dp,
    card: @Composable (Int) -> Unit,
) {
    // 每张分类卡接在 Hero 后面依次登场（Hero 是第 0 拍）
    if (!isWideLayout()) {
        for (i in 0 until count) {
            Box(Modifier.enterOnce(i + 1)) { card(i) }
            if (i != count - 1) Spacer(Modifier.height(spacing))
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
        for (col in 0..1) {
            Column(Modifier.weight(1f)) {
                var first = true
                var i = col
                while (i < count) {
                    if (!first) Spacer(Modifier.height(spacing))
                    Box(Modifier.enterOnce(i + 1)) { card(i) }
                    first = false
                    i += 2
                }
            }
        }
    }
}

/** 两个主题共用的服务条目数据。 */
private data class HomeServiceRow(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val color: Color,
    val onClick: () -> Unit,
    /** 主数据，大字号展示，如「¥42.50」「高等数学」。null 表示没有可展示的状态。 */
    val stat: String? = null,
    /** 辅助说明，小字，如「08:00 · 主楼A-203 · 还有2小时」。 */
    val statDetail: String? = null,
) {
    val hasStat: Boolean get() = !stat.isNullOrBlank()
}

/** 彩虹主题的分类卡。 */
@Composable
private fun HomeCategoryCard(
    title: String,
    subtitle: String,
    accent: Color,
    rows: List<HomeServiceRow>,
) {
    val dark = LocalIsDarkTheme.current
    val glow = accent.copy(alpha = if (dark) 0.16f else 0.10f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .appCardShadow(shape = RoundedCornerShape(CARD_RADIUS))
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(glow, Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = size.maxDimension * 0.75f,
                    )
                )
            }
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(bottom = 2.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        rows.chunked(4).forEach { group ->
            Row(Modifier.fillMaxWidth()) {
                group.forEach { row ->
                    HomeServiceTile(row, Modifier.weight(1f), solidIcon = true)
                }
                repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 宫格里的一个入口：图标 + 名称，没有独立底色，卡片是唯一容器。 */
@Composable
private fun HomeServiceTile(
    row: HomeServiceRow,
    modifier: Modifier = Modifier,
    solidIcon: Boolean = false,
) {
    val origin = rememberExpandOriginSource()
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(
        modifier = modifier
            .expandOriginSource(origin)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = {
                    origin.arm(row.key, 14.dp, density)
                    row.onClick()
                }
            )
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (solidIcon) {
            GradientAppIcon(row.icon, row.color)
        } else {
            ExpressiveIcon(icon = row.icon, color = row.color, size = 42.dp, iconSize = 21.dp)
        }
        Spacer(Modifier.height(7.dp))
        Text(
            row.title,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 卡片主题的目录：一张卡装下全部栏目。每栏左边是衬线序号和竖排宋体栏目名（和刊头的竖长印呼应），
 * 右边入口按名字长短顺排换行，右缘自然参差；栏目右上角垫一枚很淡的超大序号做水印。
 * 入口少的栏目两两并排，长栏目占满一行，版面有宽有窄。不画分割线，栏与栏之间靠留白分开。
 */
@Composable
private fun HomeIndexCard(
    sections: List<Pair<String, List<HomeServiceRow>>>,
    modifier: Modifier = Modifier,
) {
    // 相邻两个短栏目并成一行
    val lines = buildList {
        var i = 0
        while (i < sections.size) {
            val short = sections[i].second.size <= SHORT_SECTION
            if (short && i + 1 < sections.size && sections[i + 1].second.size <= SHORT_SECTION) {
                add(listOf(i, i + 1)); i += 2
            } else {
                add(listOf(i)); i += 1
            }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .appCardShadow(shape = RoundedCornerShape(CARD_RADIUS))
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor)
            .padding(start = 18.dp, end = 14.dp, top = 20.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        lines.forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                line.forEach { i ->
                    IndexSection(i + 1, sections[i].first, sections[i].second, Modifier.weight(1f))
                }
            }
        }
    }
}

/** 入口不超过这么多的栏目算「短栏目」，和相邻的短栏目并排。 */
private const val SHORT_SECTION = 3

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndexSection(index: Int, title: String, rows: List<HomeServiceRow>, modifier: Modifier = Modifier) {
    val primary = MiuixTheme.colorScheme.primary
    val number = index.toString().padStart(2, '0')
    Box(modifier) {
        Text(
            number,
            style = Editorial.figure.copy(fontSize = 84.sp, lineHeight = 84.sp, fontWeight = FontWeight.Black),
            color = primary.copy(alpha = if (LocalIsDarkTheme.current) 0.10f else 0.06f),
            modifier = Modifier.align(Alignment.TopEnd),
        )
        Row {
            Column(Modifier.width(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(number, style = Editorial.figure.copy(fontSize = 14.sp, lineHeight = 17.sp), color = primary)
                Spacer(Modifier.height(6.dp))
                title.forEach { ch ->
                    Text(
                        ch.toString(),
                        style = TextStyle(fontFamily = Editorial.Serif, fontWeight = FontWeight.Black, fontSize = 16.sp, lineHeight = 19.sp),
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            FlowRow(
                Modifier.weight(1f).padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rows.forEach { IndexEntry(it) }
            }
        }
    }
}

/** 头版的专栏格：淡底托盘里一枚功能色小圆标、宋体名称，下面一句邀请。 */
@Composable
private fun InviteCell(route: AppRoute, title: String, hint: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = legacyColor(route.id)
    val dark = LocalIsDarkTheme.current
    Column(
        modifier
            .squircleClip(18.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = SinkFeedback(), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(24.dp).background(color.copy(alpha = if (dark) 0.22f else 0.12f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(HomeIcons.of(route), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(7.dp))
            Text(title, style = EntryName, color = MiuixTheme.colorScheme.onSurface, maxLines = 1)
        }
        Text(
            hint,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** 头版的「待办」：朱红小标 + 件数，下面是最要紧的一件。 */
@Composable
private fun InboxBrief(summary: HomeInboxSummary, onClick: () -> Unit) {
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = SinkFeedback(), onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("待办", style = Editorial.kicker.copy(fontSize = 12.sp, letterSpacing = 2.sp), color = HomeSeal.Red)
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(
                        summary.todoCount.takeIf { it > 0 }?.let { "$it 件没办" },
                        summary.unreadCount.takeIf { it > 0 }?.let { "$it 组消息没读" },
                    ).joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote2,
                    color = muted,
                    maxLines = 1,
                )
            }
            Text(
                summary.nextTodo ?: "去收纳里看看",
                style = EntryTitle,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
    }
}

/** 点开功能页，并让功能页从这一块放大出来。 */
@Composable
private fun Modifier.opensRow(row: HomeServiceRow, radius: Dp = 12.dp): Modifier {
    val origin = rememberExpandOriginSource()
    val density = LocalDensity.current
    return this
        .expandOriginSource(origin)
        .clip(RoundedCornerShape(radius))
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = SinkFeedback(),
            onClick = {
                origin.arm(row.key, radius, density)
                row.onClick()
            },
        )
}

/** 目录名称：宋体，比正文略大。 */
private val EntryTitle = TextStyle(fontFamily = Editorial.Serif, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = 0.6.sp)

/** 索引条目：功能色淡底小圆标 + 宋体名称，宽度随名字。 */
@Composable
private fun IndexEntry(row: HomeServiceRow) {
    val dark = LocalIsDarkTheme.current
    Row(
        Modifier.opensRow(row, 18.dp).padding(start = 2.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).background(row.color.copy(alpha = if (dark) 0.22f else 0.11f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // 深色下把深蓝、深灰这类功能色提亮，免得沉进底色
            Icon(row.icon, contentDescription = null, tint = if (dark) lerp(row.color, Color.White, 0.3f) else row.color, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(row.title, style = EntryName, color = MiuixTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** 目录里的入口名：比 [EntryTitle] 轻一档，整块不发黑。 */
private val EntryName = TextStyle(fontFamily = Editorial.Serif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp)

/** 杂志版式的快捷入口：功能色小图标在上，宋体名称在下。 */
@Composable
private fun EditorialQuickLink(
    row: HomeServiceRow,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.opensRow(row, 18.dp).padding(vertical = 10.dp),
    ) {
        Icon(row.icon, contentDescription = null, tint = row.color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            row.title,
            style = TextStyle(fontFamily = Editorial.Serif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 1.sp),
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeQuickAction(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 点它打开的路由；用来让功能页从这一格放大出来（PR V）。 */
    originKey: String? = null,
    iconSize: Dp = 54.dp,
    /** 彩虹主题下用实心渐变图标，和分类卡里的图标一致。 */
    solidIcon: Boolean = false,
) {
    val origin = rememberExpandOriginSource()
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .then(if (originKey != null) Modifier.expandOriginSource(origin) else Modifier)
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = {
                    originKey?.let { origin.arm(it, 18.dp, density) }
                    onClick()
                }
            )
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        if (solidIcon) GradientAppIcon(icon, color, size = iconSize, iconSize = iconSize * 0.5f)
        else ExpressiveIcon(icon = icon, color = color, size = iconSize, iconSize = iconSize * 0.5f)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
    }
}

/** 首页各卡之间统一的间距：Hero、公告、分类卡之间都是它，页面读起来是一整套而不是几截。 */
private val SECTION_GAP = 14.dp

/** 首页折叠顶栏（状态栏以下）的高度，三个 36dp 圆钮在里面居中。 */
private val BAR_HEIGHT = 52.dp

/** 分类卡圆角。超椭圆下这个值可以给得比普通圆角更大而不显得「胀」。 */
private val CARD_RADIUS = 26.dp

/** Hero 右上角插画占的宽度（`:app` 那张校区图是 104dp，文字列得给它让开）。 */
private val HERO_ART_SIZE = 104.dp

private val STATUS_GREEN = Color(0xFF2E7D32)
private val STATUS_BLUE = Color(0xFF1565C0)

/** `¥42.50` —— 自己拼两位小数。 */
private fun yuanText(value: Float): String {
    val cents = (value * 100 + 0.5).toLong()
    return "¥${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

/** 首页 Hero 卡 Mesh 渐变的 3x3 顶点：每格是「主题色混进卡片底色的比例」。 */
private val HeroMeshWeights = listOf(
    listOf(0.15f, 0.08f, 0.22f),
    listOf(0.07f, 0.11f, 0.08f),
    listOf(0.03f, 0.02f, 0.05f),
)
