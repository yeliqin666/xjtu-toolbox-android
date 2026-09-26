@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.xjtu.toolbox.faculty

import com.xjtu.toolbox.ui.components.BackButton
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.rememberRetainedLazyListState
import com.xjtu.toolbox.ui.glass.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 教师主页检索。
 *
 * 数据分层原则（跟 [FacultyApi] 一致，UI 上不要打破）：
 * - 列表与名片的所有字段来自检索接口的 JSON，**全校 4173 人都有**；
 * - 个人主页解析只做补充，7.5% 的老师什么都没填，属正常情况不是错误；
 * - 主页打不开、非标准地址、或解析不出东西时，一律给「在浏览器中打开」——
 *   与其猜着渲染，不如让用户看学校的原样页面。
 *
 * @param onOpenUrl 交给上层路由到内置浏览器（Routes.BROWSER）
 */
/**
 * 教师检索的会话级状态。
 *
 * FacultyScreen 是 NavHost 的一个目的地：跳到内置浏览器看老师主页再返回时，
 * 它的 composition 已经被销毁重建，`remember` 里的检索条件和结果全部丢失，
 * 用户会看到一次完整的冷加载——这不自然，也白白多打一次学校的接口。
 *
 * 用进程内单例保存最后一次的检索状态。只在本次运行期间有效，
 * 不做持久化：教师名录不是用户数据，重启后重新拉一次没有代价。
 */
@Composable
fun FacultyScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val vm: FacultyViewModel = viewModel()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val listState = rememberRetainedLazyListState("faculty_results")
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    val members = vm.members

    // 回到顶部单独一个 effect：放在查询成功的回调里时列表还没渲染（仍在转圈），scrollToItem 会一直挂起
    LaunchedEffect(vm.searchGeneration) {
        if (vm.searchGeneration > 0 && !vm.loading && members.isNotEmpty()) runCatching { listState.scrollToItem(0) }
    }
    // 触底加载下一页
    LaunchedEffect(listState, members.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last -> if (last != null && last >= members.size - 3) vm.loadMore() }
    }

    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "教师主页",
                largeTitle = "教师主页",
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    BackButton(onBack)
                },
            )
        }
    ) { padding ->
        // 宽屏：左栏检索 + 列表，右栏常驻详情（点左边的老师直接换右边的内容，不再弹底部弹窗）。
        // 以前宽屏只是把整页限宽 720dp 居中，列表窄窄一条，两边空着。
        val isWide = com.xjtu.toolbox.ui.isWideLayout()
        Row(
            Modifier
                .padding(padding.withoutTop(glass))
                .glassSource(glass)
                .fillMaxSize()
                // 少了这一句 largeTitle 不会随滚动折叠——scrollBehavior 只是被创建、
                // 没有任何滚动源喂给它。项目里其他页面都是挂在内容顶层 Column 上。
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
        Column(
            Modifier
                .then(if (isWide) Modifier.width(420.dp) else Modifier.weight(1f))
                .fillMaxHeight()
        ) {
            // 搜索框和筛选条是列表的头两项，跟着列表滚到玻璃顶栏下面（宽屏时它们只属于左栏，
            // 挂进横跨两栏的顶栏不合适）。加载中 / 出错 / 没结果也是这个列表里的一项：
            // 每改一个字都会重新检索，要是那几种状态把列表换掉，搜索框跟着换位置就会丢焦点。
            val searchBar: @Composable () -> Unit = {
            AppSearchBar(
                query = vm.nameQuery,
                onQueryChange = vm::changeName,
                label = "搜索教师姓名",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            }

            // 筛选条件写成一整行：「学院 全部 ▾ | 学科 全部 ▾ | 职称 全部 ▾」，每一段点开底部选择器。
            // 以前是三颗浅色小胶囊加一颗「清除」，和下面卡片里的一堆小胶囊叠在一起，显得廉价。
            // 选过的那一段用主题色写出选中的值；有任何条件时最右边出现「清除」。
            val filterBar: @Composable () -> Unit = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterSegment(
                    label = "学院",
                    value = vm.college?.name,
                    onClick = { picker = PickerTarget.COLLEGE },
                    modifier = Modifier.weight(1f),
                )
                FilterSegmentDivider()
                FilterSegment(
                    label = "学科",
                    value = vm.discipline?.name,
                    onClick = { picker = PickerTarget.DISCIPLINE },
                    modifier = Modifier.weight(1f),
                )
                FilterSegmentDivider()
                FilterSegment(
                    label = "职称",
                    value = vm.proRank.ifBlank { null },
                    onClick = { picker = PickerTarget.PRO_RANK },
                    modifier = Modifier.weight(1f),
                )
                if (vm.college != null || vm.discipline != null || vm.proRank.isNotBlank()) {
                    IconButton(onClick = vm::clearFilters) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "清除筛选",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.width(18.dp),
                        )
                    }
                }
            }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.glassTop(glass), bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "search") { searchBar() }
                item(key = "filter") { filterBar() }
                val stateBox: @Composable (@Composable () -> Unit) -> Unit = { body ->
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { body() }
                }
                when {
                    vm.loading -> item(key = "state") { stateBox { LoadingState("正在检索教师…") } }
                    vm.error != null -> item(key = "state") { stateBox { ErrorState(vm.error.orEmpty(), onRetry = vm::retry) } }
                    members.isEmpty() -> item(key = "state") {
                        stateBox {
                            EmptyState(
                                title = "没有找到匹配的教师",
                                subtitle = "换个姓名或放宽筛选条件试试",
                                icon = Icons.Outlined.PersonSearch,
                            )
                        }
                    }
                    else -> {
                    item(key = "count") {
                        // 服务端 totalnum 对姓名检索是模糊计数，标注清楚免得用户以为漏了人
                        Text(
                            if (vm.nameQuery.isBlank()) "共 $vm.total 位教师"
                            else "约 $vm.total 位相关教师，精确匹配排在前面",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    items(members, key = { it.teacherId }) { member ->
                        FacultyCard(member) { vm.detail = member }
                    }
                    if (vm.loadingMore) {
                        item { LoadingState("正在加载更多…") }
                    }
                    }
                }
            }
        }

        if (isWide) {
            top.yukonga.miuix.kmp.basic.VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // 宽屏选中的老师不算一层页面：返回直接退出教师主页，不先清右栏
                val picked = vm.detail
                if (picked != null) {
                    // 按老师分开记滚动位置：不 key 的话换一位老师还停在上一位滚到的地方，顶部被顶栏盖住
                    androidx.compose.runtime.key(picked.teacherId) {
                        FacultyDetailPane(
                            member = picked,
                            api = vm.api,
                            onOpenUrl = onOpenUrl,
                            topPadding = padding.glassTop(glass),
                        )
                    }
                } else {
                    // 让出顶栏高度，否则提示贴在屏幕最上沿、压在顶栏那一层里
                    EmptyState(
                        title = "选一位老师",
                        subtitle = "左边点任意一位，详情和个人主页栏目会显示在这里",
                        icon = Icons.Outlined.PersonSearch,
                        modifier = Modifier.padding(top = padding.glassTop(glass)),
                    )
                }
            }
        }

        // ⚠️ 两个弹窗必须写在 Scaffold 的内容 lambda **里面**。
        // OverlayBottomSheet 默认 renderInRootScaffold = true，需要在组合树祖先中
        // 找到 Scaffold 宿主才能渲染；放在 Scaffold 外面它会静默地什么都不显示——
        // 筛选弹窗拉不开、点老师没反应，都是这一个原因。
        // 同时 show 必须由外部布尔驱动（false→true），不能条件式创建后把 show 初值设成 true。
        // 宽屏详情在右栏，不弹窗
        FacultyDetailSheet(
            member = if (isWide) null else vm.detail,
            api = vm.api,
            onOpenUrl = onOpenUrl,
            onDismiss = { vm.detail = null },
        )

        val pickerTarget = picker
        val pickerOptions = when (pickerTarget) {
            PickerTarget.COLLEGE -> vm.filters.colleges
            PickerTarget.DISCIPLINE -> vm.filters.disciplines
            PickerTarget.PRO_RANK -> FacultyFilters.proRanksFrom(members)
                .mapIndexed { i, name -> FacultyOption(id = i + 1, name = name, depth = 0) }
            null -> emptyList()
        }
        OptionPickerSheet(
            show = pickerTarget != null,
            title = when (pickerTarget) {
                PickerTarget.COLLEGE -> "选择学院"
                PickerTarget.DISCIPLINE -> "选择学科"
                PickerTarget.PRO_RANK -> "选择职称"
                null -> ""
            },
            options = pickerOptions,
            // 职称候选来自已加载结果，翻页越多越全——说明一句，免得用户以为列表缺项
            hint = if (pickerTarget == PickerTarget.PRO_RANK)
                "职称取自当前已加载的教师，向下翻页可发现更多" else "",
            onPick = { option ->
                when (pickerTarget) {
                    PickerTarget.COLLEGE -> vm.pickCollege(option)
                    PickerTarget.DISCIPLINE -> vm.pickDiscipline(option)
                    PickerTarget.PRO_RANK -> vm.pickProRank(option?.name.orEmpty())
                    null -> Unit
                }
                picker = null
            },
            onDismiss = { picker = null },
        )
        }
    }

}

private enum class PickerTarget { COLLEGE, DISCIPLINE, PRO_RANK }

// ==================== 列表条目 ====================

@Composable
private fun FacultyCard(member: FacultyMember, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FacultyAvatar(member, size = 52)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    member.name,
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 身份一行小字（职称 · 博导 · 硕导 · 学院），研究方向一行。
                // 以前职称、导师、研究方向各是一颗浅色小胶囊，一张卡里堆四五颗，列表一长就显得廉价；
                // 这些都是说明文字，不能点，就该写成文字。
                val meta = buildList {
                    if (member.proRank.isNotBlank()) add(member.proRank)
                    if (member.isDoctoralTutor) add("博导")
                    if (member.isMasterTutor) add("硕导")
                    if (member.collegeName.isNotBlank()) add(member.collegeName)
                }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        meta.joinToString(" · "),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val focus = member.researchDirections.take(3).joinToString("、").ifBlank { member.discipline }
                if (focus.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        focus,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ==================== 详情 ====================

@Composable
private fun FacultyDetailSheet(
    /** null 表示未选中任何教师；此时弹窗保持在组合树里但 show=false */
    member: FacultyMember?,
    api: FacultyApi,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 内容需要一个非空的 member 才能渲染。关闭动画期间 member 已经变 null，
    // 所以保留最后一次的值，避免收起过程中内容突然塌成空白。
    var lastMember by remember { mutableStateOf<FacultyMember?>(null) }
    if (member != null) lastMember = member
    val shown = lastMember ?: return

    var homepage by remember(shown.teacherId) { mutableStateOf<HomepageResult?>(null) }
    LaunchedEffect(member?.teacherId) {
        val target = member ?: return@LaunchedEffect
        homepage = api.fetchHomepage(target)
    }

    OverlayBottomSheet(
        show = member != null,
        title = shown.name,
        onDismissRequest = onDismiss,
    ) {
        FacultyDetailBody(
            shown = shown,
            homepage = homepage,
            onOpenUrl = onOpenUrl,
            modifier = Modifier
                .fillMaxWidth()
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        )
    }
}

/**
 * 宽屏右栏的教师详情：和底部弹窗同一份内容（[FacultyDetailBody]），只是常驻在右边，
 * 点左边列表换人时直接换内容，不用一次次拉起、收起弹窗。
 */
@Composable
private fun FacultyDetailPane(
    member: FacultyMember,
    api: FacultyApi,
    onOpenUrl: (String) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp,
) {
    var homepage by remember(member.teacherId) { mutableStateOf<HomepageResult?>(null) }
    LaunchedEffect(member.teacherId) {
        homepage = api.fetchHomepage(member)
    }
    Column(
        Modifier
            .fillMaxSize()
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(topPadding + 12.dp))
        Text(
            member.name,
            style = MiuixTheme.textStyles.title2,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        FacultyDetailBody(shown = member, homepage = homepage, onOpenUrl = onOpenUrl)
        Spacer(Modifier.height(24.dp))
    }
}

/** 教师详情的内容本身，弹窗和宽屏右栏共用。 */
@Composable
private fun FacultyDetailBody(
    shown: FacultyMember,
    homepage: HomepageResult?,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
        Column(modifier) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FacultyAvatar(shown, size = 64)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val meta = buildList {
                            if (shown.proRank.isNotBlank()) add(shown.proRank)
                            if (shown.isDoctoralTutor) add("博导")
                            if (shown.isMasterTutor) add("硕导")
                        }.ifEmpty { listOf("教师") }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            meta.forEachIndexed { i, text ->
                                MetaChip(text, emphasized = i == 0)
                            }
                        }
                        if (shown.collegeName.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                shown.collegeName,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (shown.englishName.isNotBlank()) {
                            Text(
                                shown.englishName,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (shown.researchDirections.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionTitle("研究方向")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    shown.researchDirections.forEach { TagPill(it) }
                }
            }

            // JSON 字段优先——它对全校 4173 人都有值，比主页解析可靠
            val basics = buildList {
                add("学科" to shown.discipline)
                add("学位" to shown.degree)
                add("学历" to shown.education)
                add("毕业院校" to shown.graduatedUniversity)
                add("职务" to shown.job)
                add("办公地点" to shown.officeLocation)
                add("邮箱" to shown.email)
                add("联系方式" to shown.contact)
                add("电话" to listOf(shown.phone, shown.mobilePhone)
                    .filter { it.isNotBlank() }.joinToString(" / "))
                add("通讯地址" to shown.address)
                add("入职时间" to shown.entryTime)
            }.filter { it.second.isNotBlank() }

            if (basics.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionTitle("基本信息")
                InfoCard { basics.forEach { (label, value) -> InfoRow(label, value) } }
            }

            if (shown.profile.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                SectionTitle("个人简介")
                InfoCard {
                    Text(
                        shown.profile,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            when (val result = homepage) {
                null -> LoadingState("正在读取个人主页…")

                is HomepageResult.Success -> {
                    // 主页字段只补 JSON 没有的，重复的不再显示一遍
                    val extra = result.profile.fields
                        .filterKeys { key -> basics.none { it.first == key } }
                        .filterValues { it.isNotBlank() }
                    if (extra.isNotEmpty()) {
                        SectionTitle("主页补充")
                        InfoCard { extra.forEach { (label, value) -> InfoRow(label, value) } }
                        Spacer(Modifier.height(12.dp))
                    }
                    // 按一级栏目分组渲染。拍平会把「基本信息（一级）」和它同名的
                    // 子页并列成两条，看着像重复；分组后层级关系一目了然。
                    val groups = result.profile.columns.groupBySection()
                    if (groups.isNotEmpty()) {
                        SectionTitle("主页栏目")
                        InfoCard {
                            groups.forEachIndexed { i, group ->
                                if (i > 0) HorizontalDivider()
                                ColumnGroupBlock(group, onOpenUrl)
                            }
                        }
                    }
                }

                is HomepageResult.Unavailable -> HintText("这位老师还没有启用个人主页。")

                is HomepageResult.NotStandard -> HintText(
                    "这位老师的主页地址指向站外页面，只能直接打开查看。"
                )

                is HomepageResult.Error -> HintText("个人主页暂时打不开：${result.message}")
            }

            if (shown.homepageUrl.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenUrl(shown.homepageUrl) },
                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.width(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "在浏览器中打开个人主页",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
}

// ==================== 选择器 ====================

/**
 * 长列表选择器。学科有 2293 项、学院 137 项，弹出菜单撑不住，
 * 所以用底部弹窗 + 关键词过滤；[FacultyOption.depth] 转成缩进保留原层级。
 */
@Composable
private fun OptionPickerSheet(
    show: Boolean,
    title: String,
    options: List<FacultyOption>,
    hint: String = "",
    onPick: (FacultyOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    // 每次重新打开都从空关键词开始，否则上次筛「电气」的残留会让人以为列表缺项
    LaunchedEffect(show) { if (show) keyword = "" }

    val filtered = remember(options, keyword) {
        val k = keyword.trim()
        val visible = options.filterNot { it.isUnlimited }
        if (k.isEmpty()) visible else visible.filter { it.name.contains(k, ignoreCase = true) }
    }

    OverlayBottomSheet(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            if (hint.isNotBlank()) {
                Text(
                    hint,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            AppSearchBar(
                query = keyword,
                onQueryChange = { keyword = it },
                label = "筛选",
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp).overScrollVertical(),
            ) {
                item {
                    LinkRow("不限") { onPick(null) }
                    HorizontalDivider()
                }
                items(filtered, key = { it.id }) { option ->
                    Text(
                        option.name,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(option) }
                            // depth 是服务端 `|--` 前缀的字符数，除以 2 换算成层级
                            .padding(
                                start = (option.depth / 2 * 14).dp,
                                top = 12.dp, bottom = 12.dp, end = 8.dp,
                            ),
                    )
                }
            }
        }
    }
}

// ==================== 细粒度组件 ====================

/** 条件栏里的一段：上面小字写维度，下面写当前值（没选就是「全部」）。 */
@Composable
private fun FilterSegment(
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                value ?: "全部",
                style = MiuixTheme.textStyles.body2,
                fontWeight = if (value != null) FontWeight.Medium else FontWeight.Normal,
                color = if (value != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(16.dp),
        )
    }
}

@Composable
private fun FilterSegmentDivider() {
    Box(
        Modifier
            .width(0.5.dp)
            .height(28.dp)
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.3f)),
    )
}

@Composable
private fun TagPill(text: String, onTonal: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50.dp),
        color = if (onTonal) MiuixTheme.colorScheme.surface
        else MiuixTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun MetaChip(text: String, emphasized: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (emphasized) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MiuixTheme.colorScheme.surface,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MiuixTheme.textStyles.footnote2,
            color = if (emphasized) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 详情页的分区容器。
 *
 * 之前所有信息都直接平铺在弹窗背景上，标题和内容没有视觉分组，
 * 越往下越像一张流水账。收进卡片后每个分区自成一块，扫视时层级清楚。
 */
@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.subtitle,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(68.dp),
        )
        Text(
            value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 一级栏目 + 其子页。
 *
 * 一级自身也可点（它就是该栏目的落地页），子页缩进一层并用更轻的字重，
 * 让「哪些是同一组」在扫视时就能看出来。
 */
@Composable
private fun ColumnGroupBlock(group: FacultyColumnGroup, onOpenUrl: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        LinkRow(group.section.displayName) { onOpenUrl(group.section.url) }
        group.children.forEach { child ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenUrl(child.url) }
                    .padding(start = 16.dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 一根短竖线代替项目符号，比圆点更贴 MIUIX 的克制感
                Box(
                    Modifier
                        .width(2.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    child.displayName,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(16.dp),
        )
    }
}

@Composable
private fun HintText(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
