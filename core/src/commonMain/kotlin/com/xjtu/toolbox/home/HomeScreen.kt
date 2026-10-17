package com.xjtu.toolbox.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
            )
        }
    }

    val categorySection: @Composable () -> Unit = {
        val groups = HomeCards.groups(host.audience, host.stats, host.openableRouteIds)
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
                // 卡片主题：有状态的功能出数据格，其余收成一排紧凑入口，回答「这一块现在怎么样」。
                CategoryCards(count = groups.size, spacing = SECTION_GAP) { index ->
                    val group = groups[index]
                    HomeSceneCard(
                        title = group.category.title,
                        subtitle = group.category.subtitle,
                        icon = HomeIcons.category(group.category),
                        accent = legacyColor(HomeIcons.accentKey(group.category)),
                        rows = group.rows.map { row ->
                            rowOf(
                                row.service,
                                // 不画状态时传 null（屏按「只有入口」处理），而不是传一个空串装作有数据
                                row.stat?.takeIf { HomeCard.CATEGORY_STATS in honored }?.let {
                                    HomeStat(row.stat, row.statDetail)
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(contentTopPadding))
        headerSection()
        host.inbox?.let { summary ->
            if (HomeCard.INBOX in honored) {
                Spacer(Modifier.height(SECTION_GAP))
                HomeInboxCard(summary) { host.onRoute(AppRoute.Inbox) }
            }
        }
        Spacer(Modifier.height(SECTION_GAP))
        categorySection()
        Spacer(Modifier.height(SECTION_GAP + extraBottomPadding))
    }
}

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
) {
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

    Box(
        modifier
            .fillMaxWidth()
            .appCardShadow(shape = RoundedCornerShape(CARD_RADIUS), strong = true)
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor),
    ) {
        Box(Modifier.matchParentSize()) {
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
        host.heroArt?.invoke(this)
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            if (HomeCard.GREETING in honored) {
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
                    onClick = { nextUp.route?.let { route -> host.onRoute(route) } ?: host.onProfile() },
                )
            }
            if (glances.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    glances.forEach { glance ->
                        HeroGlance(
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
                            onClick = {
                                glance.route?.let { host.onRoute(it) }
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
            if (HomeCard.QUICK_ACTIONS in honored && quickRows.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    quickRows.forEach { row ->
                        HomeQuickAction(
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
private fun HeroStatusLine(status: HomeStatusLine, onClick: () -> Unit) {
    val tint = when (status.tone) {
        HomeTone.ACCENT -> STATUS_BLUE
        HomeTone.ALERT -> MiuixTheme.colorScheme.error
        HomeTone.NORMAL -> STATUS_GREEN
        HomeTone.MUTED -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Row(
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = SinkFeedback(),
            onClick = onClick,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(tint)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "${status.label} · ${status.detail}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
private fun HeroNextUp(nextUp: HomeNextUp?, onClick: () -> Unit) {
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier
            .fillMaxWidth()
            .heroInset()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = primary,
                )
                Text(
                    item.timeCaption.orEmpty(),
                    style = MiuixTheme.textStyles.footnote2,
                    color = muted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .width(1.dp)
                    .height(30.dp)
                    .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.18f))
            )
            Spacer(Modifier.width(12.dp))
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
            ExpressiveIcon(icon = item.icon, color = primary, size = 38.dp, iconSize = 20.dp)
            Spacer(Modifier.width(12.dp))
            item.detail?.takeIf { it.isNotBlank() }?.let { detail = AnnotatedString(it) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MiuixTheme.textStyles.body1,
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
) {
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(
        modifier
            .heroInset(16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                color = valueColor,
            )
        } else {
            Text(
                value,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (caption != null) {
            Text(caption, style = MiuixTheme.textStyles.footnote2, color = muted, maxLines = 1)
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

/** 卡片主题的分类卡：上面是有实时状态的功能（数据格），下面是其余功能的紧凑入口。 */
@Composable
private fun HomeSceneCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    rows: List<HomeServiceRow>,
) {
    if (rows.isEmpty()) return
    val dark = LocalIsDarkTheme.current
    val live = rows.filter { it.hasStat }
    val rest = rows.filterNot { it.hasStat }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .appCardShadow(shape = RoundedCornerShape(CARD_RADIUS))
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (rest.isEmpty()) 16.dp else 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .squircleBackground(accent.copy(alpha = if (dark) 0.24f else 0.12f), 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (live.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            live.chunked(2).forEachIndexed { i, pair ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { ServiceStatCell(it, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
        if (rest.isNotEmpty()) {
            Spacer(Modifier.height(if (live.isNotEmpty()) 8.dp else 10.dp))
            rest.chunked(4).forEach { group ->
                Row(Modifier.fillMaxWidth()) {
                    group.forEach { HomeServiceTile(it, Modifier.weight(1f)) }
                    repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 数据格：功能色淡染的面，名称在上，数值加粗，说明一行。 */
@Composable
private fun ServiceStatCell(
    row: HomeServiceRow,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val origin = rememberExpandOriginSource()
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(
        modifier = modifier
            .expandOriginSource(origin)
            .squircleClip(18.dp)
            .background(row.color.copy(alpha = if (dark) 0.14f else 0.07f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = {
                    origin.arm(row.key, 18.dp, density)
                    row.onClick()
                }
            )
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(row.icon, contentDescription = null, tint = row.color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                row.title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            row.stat.orEmpty(),
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        row.statDetail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(1.dp))
            Text(
                it,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
