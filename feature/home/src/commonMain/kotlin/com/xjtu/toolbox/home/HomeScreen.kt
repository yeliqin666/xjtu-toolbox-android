package com.xjtu.toolbox.home

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.xjtu.toolbox.settings.SettingsStore
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
import com.xjtu.toolbox.ui.components.LiquidBackground
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.components.rememberLiquidMood
import com.xjtu.toolbox.ui.glass.LocalGlassStyle
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.ui.theme.Editorial
import com.xjtu.toolbox.ui.theme.EditorialRule
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import com.xjtu.toolbox.ui.theme.serviceColor
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
 * 首页屏。
 *
 * 版面从「刊头 + 头卡 + 一堆分类卡」改成「刊头 + 头卡 + **可编辑的卡片组**」：
 * 每组是一行标题 + 一片组件网格，组件的大小、位置、所属组都能在编辑态里拖。
 * 「常用功能」不再是特权区，它就是第一组（需求 §2.1）。
 */
@OptIn(ExperimentalFoundationApi::class)
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
    /** 顶栏最左侧那个按钮（`:app` 是「扫一扫」）；null = 这一端没有。 */
    topLeading: (@Composable () -> Unit)? = null,
    /** 顶栏右侧那一排（`:app` 是「搜索」+「通知」）。null = 这一端没有顶栏。 */
    topActions: (@Composable () -> Unit)? = null,
) {
    val honored = host.honoredCards
    // 用目录全量：图标配色按它在目录里的下标取，过滤会让配色挪位。
    val catalog = remember(host.audience) { AppServices.homeFor(host.audience) }
    // 配色随应用主题变化，不能放进 remember。
    val colorByKey = catalog.associate { it.route.id to serviceColor(it.route.id) }

    // ── 布局与主页设置 ──
    val layoutStore = remember { HomeLayoutStore() }
    val revision = HomeLayoutRevision.current
    val editing = remember { HomeEditState() }
    val stored = remember(revision, host.audience, host.quickRoutes) {
        layoutStore.load(host.audience, host.quickRoutes)
    }
    val layout = editing.draft ?: stored
    val grid = remember(revision) { layoutStore.grid }
    val liquid = remember(revision) { layoutStore.liquidCard }
    val backgroundImage = remember(revision) { layoutStore.backgroundImage }
    val backgroundBlur = remember(revision) { layoutStore.backgroundBlur }
    val backgroundOpacity = remember(revision) { layoutStore.backgroundOpacity }
    // 网格单元要不要给文字留位置（§12）：关掉之后单元变矮，1 格高的卡片自动退成纯图标
    val leaveTextSpace = remember(revision) { layoutStore.leaveTextSpace }
    val cellHeight = if (leaveTextSpace) HomeCellHeight else HomeCompactCellHeight

    /** 改当前布局：编辑态改草稿（点「对勾」才落盘），其余情况直接落盘。 */
    fun update(block: (HomeLayout) -> HomeLayout) {
        if (editing.on) {
            editing.draft = block(editing.draft ?: stored)
        } else {
            layoutStore.save(block(stored))
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
            stat = stat?.value,
            statDetail = stat?.detail,
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
            val stat = HomeCards.statOf(service.route.id, host.stats)
            val value = if (HomeCard.CATEGORY_STATS in honored) {
                stat?.value
                    ?: glance?.let { g -> g.number?.let { yuanText(it.toFloat()) } ?: g.value }
                    ?: HomeCards.statOf(service.route.id, host.stats)?.value
            } else null
            service.route.id to rowOf(service, value?.let { HomeStat(it, stat?.detail ?: glance?.caption) })
                .copy(tone = glance?.tone ?: HomeTone.NORMAL)
        }

    val quickVisible = HomeCard.QUICK_ACTIONS in honored
    val groups = layout.groups.filter { group ->
        HomeCard.SERVICE_GRID in honored &&
            (group.id != HomeLayoutDefaults.QUICK_GROUP || quickVisible) &&
            (editing.on || group.tiles.any { faces.containsKey(it.routeId) })
    }

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
    val glass = LocalGlassStyle.current && collapsible

    // 顶栏模糊带、卡片与顶栏按钮的液态玻璃，采的都是这**同一层背景**（主题底色 + 壁纸）：
    // 壁纸铺满全屏含顶栏（§1.1），玻璃透出来的统一是它；采整页会把组件自己采进去，形成回环
    val tileBackdrop = rememberLayerBackdrop()

    val session = remember { HomeDragSession() }
    val density = LocalDensity.current
    var pageSize by remember { mutableStateOf(Offset.Zero) }

    val scope = rememberCoroutineScope()

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                // 拖拽会话的坐标基准：所有组 / 块的 boundsInRoot 都减它，统一成页面局部坐标
                session.pageOrigin = it.boundsInRoot().topLeft
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
        // ── 背景层：主题底色 + 可选壁纸。同时是液态玻璃组件的采样源 ──
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(tileBackdrop),
        ) {
            Box(Modifier.matchParentSize().background(barSurface))
            if (backgroundImage.isNotEmpty()) {
                HeroCustomBackground(
                    imageData = backgroundImage,
                    modifier = Modifier.matchParentSize(),
                    blur = backgroundBlur,
                    brightness = 100,
                )
                // 透明度：缺多少就用主题底色盖回去多少，不整层 alpha（否则文字也会跟着淡）
                val cover = (100 - backgroundOpacity) / 100f
                if (cover > 0f) {
                    Box(Modifier.matchParentSize().background(barSurface.copy(alpha = cover)))
                }
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
            if (masthead) {
                // 顶栏那一排按钮先占住最上面，刊头大字从它下面开始
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
                    inbox = host.inbox.takeIf { HomeCard.INBOX in honored },
                )
            }

            // ── 卡片组 ──
            groups.forEachIndexed { index, group ->
                Spacer(Modifier.height(if (index == 0) 20.dp else GROUP_GAP))
                HomeGroupSection(
                    group = group,
                    faces = faces,
                    editing = editing.on,
                    liquid = liquid,
                    backdrop = tileBackdrop.takeIf { liquid },
                    session = session,
                    onOpen = { it.onClick() },
                    onStartEdit = { editing.begin(stored) },
                    onEditGroup = { editing.editGroup = group },
                    onDeleteGroup = { editing.confirmDeleteGroup = group },
                    onDropDrag = {
                        val id = session.tileId
                        val pointer = session.pointer
                        val target = session.boards.entries.firstOrNull { it.value.contains(pointer) }
                        if (id != null && target != null) {
                            val m = target.value
                            val x = m.toCellX(pointer.x - session.grab.x)
                            val y = m.toCellY(pointer.y - session.grab.y)
                            update { it.moveTile(id, target.key, x, y, grid, if (grid) 0f else EDGE_LINK_CELLS) }
                        }
                    },
                    onDropResize = { tileId, w, h -> update { it.resizeTile(tileId, w, h, grid) } },
                    onRemoveTile = { tileId -> update { it.removeTile(tileId) } },
                    onReorderMove = {
                        // 实时交换（§16）：手指越过相邻组的中线就立刻与之换序，被拖组跟手、
                        // 列表始终保持堆叠。换序吃掉 / 吐出的高度记进会话，跟手位移才连续
                        val groupId = session.reorderGroupId
                        if (groupId != null) {
                            val order = layout.groups
                            val index = order.indexOfFirst { it.id == groupId }
                            if (index >= 0) {
                                val gapPx = with(density) { GROUP_GAP.toPx() }
                                val nextMid = if (index < order.lastIndex) {
                                    val next = order[index + 1]
                                    session.groupTops[next.id]?.let { t ->
                                        session.groupHeights[next.id]?.let { h -> t + h / 2f }
                                    }
                                } else {
                                    null
                                }
                                val prevMid = if (index > 0) {
                                    val prev = order[index - 1]
                                    session.groupTops[prev.id]?.let { t ->
                                        session.groupHeights[prev.id]?.let { h -> t + h / 2f }
                                    }
                                } else {
                                    null
                                }
                                when {
                                    nextMid != null && session.reorderY > nextMid -> {
                                        val nextId = order[index + 1].id
                                        update { it.moveGroup(groupId, index + 1) }
                                        session.absorbReorderShift((session.groupHeights[nextId] ?: 0f) + gapPx)
                                    }
                                    prevMid != null && session.reorderY < prevMid -> {
                                        val prevId = order[index - 1].id
                                        update { it.moveGroup(groupId, index - 1) }
                                        session.absorbReorderShift(-((session.groupHeights[prevId] ?: 0f) + gapPx))
                                    }
                                }
                            }
                        }
                    },
                    // 和头卡同一套左右留白（标题行内部再各让 4dp，所以这里就写 16dp）；
                    // 入场沿用全 App 那套「错峰淡入上浮」，只播一次
                    modifier = Modifier.padding(horizontal = 16.dp).enterOnce(index),
                    cellHeight = cellHeight,
                )
            }

            if (editing.on) {
                Spacer(Modifier.height(GROUP_GAP))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    AddGroupRow { update { it.addGroup(HomeGroup(id = newGroupId(), title = "新分组")) } }
                }
            }
            Spacer(Modifier.height(SECTION_GAP + extraBottomPadding + 8.dp))
        }

        // 编辑态顶栏（取消 / 设置 / 保存）不需要宿主给按钮，所以这一端没有 topActions 也要画，
        // 否则桌面、Web 长按进了编辑态就出不来了
        if (collapsible || editing.on) {
            // 把首页背景层通过 Local 递给顶栏按钮（含宿主给的扫码 / 搜索 / 通知）做液态玻璃
            CompositionLocalProvider(LocalHomeTileBackdrop provides tileBackdrop.takeIf { liquid }) {
                HomeCollapsingBar(
                    progress = progress,
                    topInset = contentTopPadding,
                    bandShift = contentTopShift,
                    backdrop = if (glass) tileBackdrop else null,
                    tileLiquidBackdrop = tileBackdrop.takeIf { liquid },
                    onTapCollapsed = { scope.launch { scroll.animateScrollTo(0) } },
                    editing = editing.on,
                    leading = topLeading,
                    actions = topActions,
                    onCancelEdit = { editing.cancel() },
                    onSaveEdit = {
                        editing.draft?.let { layoutStore.save(it) }
                        editing.cancel()
                    },
                    onHomeSettings = {
                        // 齿轮 = 在原地打开「主页设置」抽屉：不跳设置页、编辑态不关闭，
                        // 手上的草稿原样留着，关掉抽屉接着摆（§31）
                        host.onOpenHomeSettings()
                    },
                    alignWithGreeting = masthead,
                )
            }
        }

        // ── 拖拽幽灵块：跟手画在最上层，落点由「哪个组接着它」决定 ──
        HomeDragGhost(
            session = session,
            faces = faces,
            liquid = liquid,
            backdrop = tileBackdrop.takeIf { liquid },
            cellHeight = cellHeight,
        )

        // ── 编辑态右侧的「添加卡片」悬浮球：展开成长条，长按功能拖出来放到组里 ──
        if (editing.on) {
            HomeAddTileOrb(
                faces = faces,
                catalog = catalog,
                liquid = liquid,
                backdrop = tileBackdrop.takeIf { liquid },
                session = session,
                onDropPalette = { routeId ->
                    val pointer = session.pointer
                    val target = session.boards.entries.firstOrNull { it.value.contains(pointer) }
                    if (target != null) {
                        val m = target.value
                        val x = m.toCellX(pointer.x - session.grab.x)
                        val y = m.toCellY(pointer.y - session.grab.y)
                        val newId = "palette/$routeId#${layout.group(target.key)?.tiles?.size ?: 0}"
                        update { it.addTileAt(target.key, routeId, newId, x, y, grid) }
                    }
                },
                modifier = Modifier.matchParentSize(),
            )
        }

        // ── 拖到屏幕上下边缘时自动滚动（带阻尼：越靠边越快） ──
        LaunchedEffect(session.dragging) {
            if (!session.dragging) return@LaunchedEffect
            val edge = with(density) { 104.dp.toPx() }
            val maxStep = with(density) { 14.dp.toPx() }
            while (true) {
                withFrameNanos { }
                // pointer 已是页面局部坐标，直接和页面尺寸比
                val y = session.pointer.y
                val step = when {
                    y < edge -> -(1f - (y / edge).coerceIn(0f, 1f)) * maxStep
                    y > pageSize.y - edge -> ((y - (pageSize.y - edge)) / edge).coerceIn(0f, 1f) * maxStep
                    else -> 0f
                }
                if (step != 0f) scroll.scrollBy(step)
            }
        }

        // 长按进编辑态：记住那一刻的滚动位置，编辑态布局稳定后若被改动就拉回去。
        // 编辑态会多出空组 / 标题行，内容变高，但滚动本身不该跳（§17 补充）
        LaunchedEffect(editing.on) {
            if (!editing.on) return@LaunchedEffect
            val anchor = scroll.value
            withFrameNanos { }
            if (scroll.value != anchor) scroll.scrollTo(anchor)
        }

        // ── 组名 / 颜色、删除组、往组里加功能这几个弹窗 ──
        editing.editGroup?.let { group ->
            val current = layout.group(group.id) ?: group
            GroupEditDialog(
                group = current,
                onDismiss = { editing.editGroup = null },
                onConfirm = { title, colorHex ->
                    update { it.renameGroup(current.id, title).tintGroup(current.id, colorHex) }
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
        editing.addTo?.let { group ->
            TilePickerDialog(
                candidates = catalog.filter { service ->
                    // 已经在这一组里的就不列了；在别的组里的允许再放一块（常用功能就是这么来的）
                    layout.group(group.id)?.tiles?.none { it.routeId == service.route.id } != false
                },
                onDismiss = { editing.addTo = null },
                onPick = { service ->
                    update { it.addTile(group.id, service.route.id, "${group.id}/${service.route.id}#${it.group(group.id)?.tiles?.size ?: 0}") }
                    editing.addTo = null
                },
            )
        }
    }
}

/** 组与组之间的间距（每组自带标题行，间距比组内组件缝隙大一档）。 */
private val GROUP_GAP = 20.dp

/** 自由拖拽模式的边界联动容差（格）：离邻居的边不到这个距离就吸过去。 */
private const val EDGE_LINK_CELLS = 0.28f

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
 * 首页背景层（主题底色 + 壁纸）的采样源：顶栏按钮——包括宿主给的扫码 / 搜索 / 通知——
 * 读它做液态玻璃。首页在顶栏这一层 provide（液态开关关的时候是 null，按钮退回非液态）。
 */
val LocalHomeTileBackdrop = compositionLocalOf<Backdrop?> { null }

// ── 顶栏 ──

/**
 * 首页的折叠顶栏，叠在滚动内容上面：最左是扫码（宿主给），正中间是应用名，
 * 右侧是搜索与通知。往上滑过刊头后玻璃底色浮出来，底边柔和淡出。
 *
 * 编辑态顶栏换成三个纯图标：左边取消、右边齿轮 + 对勾（需求 §1.3），
 * 按钮的视觉样式和常规态完全一致，只有图标不同。
 */
@Composable
private fun HomeCollapsingBar(
    progress: () -> Float,
    topInset: Dp,
    bandShift: () -> Dp,
    backdrop: Backdrop?,
    /** 顶栏圆钮液态玻璃的采样源（首页背景层）；null = 退回半透明圆钮。 */
    tileLiquidBackdrop: Backdrop?,
    onTapCollapsed: () -> Unit,
    editing: Boolean,
    leading: (@Composable () -> Unit)?,
    actions: (@Composable () -> Unit)?,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onHomeSettings: () -> Unit,
    alignWithGreeting: Boolean = false,
) {
    val collapsed by remember { derivedStateOf { progress() > 0.5f } }
    val surface = MiuixTheme.colorScheme.surface
    Box(Modifier.fillMaxWidth().height(topInset + BAR_HEIGHT)) {
        if (backdrop != null) {
            // 96dp 的模糊带比顶栏本身高，放在顶栏高度之外，才能往内容里多淡一段。
            // 采的是首页**背景层**：壁纸的模糊一直铺到屏幕顶边（§1.1 背景铺满含顶栏）。
            // 贴的是屏幕顶边而不是这一页的本地 0 点：壳会按 stable 顶栏高度把整页上移，
            // 不补回 [bandShift] 的话整条带会被顶到屏幕外。
            Box(
                Modifier
                    .fillMaxWidth()
                    .requiredHeight(PROGRESSIVE_BLUR_BAND_HEIGHT)
                    .offset { IntOffset(0, bandShift().roundToPx()) },
            ) {
                ProgressiveBarBlur(backdrop, Modifier.matchParentSize())
                if (SHOW_TINT_WASH) {
                    Box(Modifier.matchParentSize().background(tintWash(surface)))
                }
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
        // 编辑态换成「首页调整设置」，让人一眼知道现在在摆桌面
        Box(
            Modifier
                .matchParentSize()
                .padding(top = topInset)
                .height(BAR_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (editing) "首页调整设置" else "岱宗盒子",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = topInset)
                .height(BAR_HEIGHT)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.graphicsLayer {
                    translationY = if (alignWithGreeting) 0f else -BUTTON_LIFT.toPx() * (1f - progress())
                },
            ) {
                if (editing) {
                    HomeBarIconButton(Icons.Default.Close, "取消编辑", tileLiquidBackdrop, onCancelEdit)
                } else {
                    leading?.invoke()
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.graphicsLayer {
                    translationY = if (alignWithGreeting) 0f else -BUTTON_LIFT.toPx() * (1f - progress())
                },
            ) {
                if (editing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        HomeBarIconButton(Icons.Default.Settings, "主页设置", tileLiquidBackdrop, onHomeSettings)
                        HomeBarIconButton(Icons.Default.Check, "保存布局", tileLiquidBackdrop, onSaveEdit)
                    }
                } else {
                    actions?.invoke()
                }
            }
        }
    }
}

/**
 * 顶栏圆钮：液态玻璃（采首页背景层，和卡片同一套 `drawBackdrop`）；[liquid] 为 null 时
 * 退回半透明底色圆钮（需求 §1.2）。
 */
@Composable
private fun HomeBarIconButton(icon: ImageVector, label: String, liquid: Backdrop?, onClick: () -> Unit) {
    val dark = LocalIsDarkTheme.current
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

/** 每层遮罩占整条带的比例：useplanes 的层与层之间错开 12.5%。 */
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
                        HeroStatusLine(status, onStatus)
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

// ── 编辑态的弹窗与幽灵块 ──

/** 拖拽幽灵块：按手指位置画一块「和真组件一模一样」的预览，落点由首页算。 */
@Composable
private fun HomeDragGhost(
    session: HomeDragSession,
    faces: Map<String, HomeServiceRow>,
    liquid: Boolean,
    backdrop: Backdrop?,
    cellHeight: Dp,
) {
    if (!session.dragging) return
    val face = session.routeId?.let { faces[it] } ?: return
    // 从添加面板拖出来的块没有源组：格宽格高都一样，借任意一个 board 换算就行
    val board = session.fromGroupId?.let { session.boards[it] }
        ?: session.boards.values.firstOrNull()
        ?: return
    val density = LocalDensity.current
    val gapPx = with(density) { HomeTileGap.toPx() }
    val wPx = (session.sizeCells.x * board.colUnit - gapPx).coerceAtLeast(1f)
    val hPx = (session.sizeCells.y * board.rowUnit - gapPx).coerceAtLeast(1f)

    Box(
        Modifier
            // 位置直接用会话里那条「从原块出发、只累加位移」的轨迹，和手指的相对位置恒定
            .offset {
                IntOffset(session.ghostOrigin.x.roundToInt(), session.ghostOrigin.y.roundToInt())
            }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .zIndex(20f)
            .graphicsLayer {
                scaleX = 1.04f
                scaleY = 1.04f
                alpha = 0.96f
            },
    ) {
        HomeTileFaceBox(
            face = face,
            w = session.sizeCells.x,
            h = session.sizeCells.y,
            liquid = liquid,
            backdrop = backdrop,
            cellHeight = cellHeight,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 改一个组的名字与标题颜色（§2.3）：标题留空就整行不画。
 * 颜色用全应用同一套调色盘（`AppColorPicker`），「跟随主题」就是存空串。
 */
@Composable
private fun GroupEditDialog(
    group: HomeGroup,
    onDismiss: () -> Unit,
    onConfirm: (title: String, colorHex: String) -> Unit,
) {
    var title by remember(group.id) { mutableStateOf(group.title) }
    // 空串 = 跟随主题
    var followTheme by remember(group.id) { mutableStateOf(group.colorHex.isEmpty()) }
    // 主题色要在组合上下文取好再喂给 remember——remember 的 calculation lambda 不是
    // @Composable 上下文，里面直接读 MiuixTheme 会编译报错
    val defaultTint = MiuixTheme.colorScheme.primary
    var picked by remember(group.id) {
        mutableStateOf(CourseColors.parseHex(group.colorHex) ?: defaultTint)
    }
    WindowDialog(show = true, title = "分组设置", onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
        ) {
            TextField(
                value = title,
                onValueChange = { title = it.take(20) },
                label = "分组标题（留空则不显示标题）",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("标题颜色", fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text("跟随主题", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.width(6.dp))
                Switch(checked = followTheme, onCheckedChange = { followTheme = it })
            }
            if (!followTheme) {
                Spacer(Modifier.height(10.dp))
                AppColorPicker(
                    color = picked,
                    onColorChanged = { picked = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "确定",
                    onClick = { onConfirm(title.trim(), if (followTheme) "" else CourseColors.toHex(picked)) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

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

/** 往一个组里放功能：列出还没在这一组里的入口。 */
@Composable
private fun TilePickerDialog(
    candidates: List<AppService>,
    onDismiss: () -> Unit,
    onPick: (AppService) -> Unit,
) {
    WindowDialog(show = true, title = "添加功能", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (candidates.isEmpty()) {
                Text(
                    "这一组已经放满目录里的功能了",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(8.dp),
                )
            }
            candidates.forEach { service ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = SinkFeedback(),
                            onClick = { onPick(service) },
                        )
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        HomeIcons.of(service.route),
                        contentDescription = null,
                        tint = serviceColor(service.route.id),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(service.title, fontSize = 14.sp)
                }
            }
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

private val STATUS_GREEN = Color(0xFF2E7D32)
private val STATUS_BLUE = Color(0xFF1565C0)

/** `¥42.50` —— 自己拼两位小数。 */
private fun yuanText(value: Float): String {
    val cents = (value * 100 + 0.5).toLong()
    return "¥${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}
