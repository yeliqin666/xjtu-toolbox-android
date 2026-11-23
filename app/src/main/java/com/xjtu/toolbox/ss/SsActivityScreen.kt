package com.xjtu.toolbox.ss

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.auth.LocalAuthExpiry
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.SsSession
import com.xjtu.toolbox.lms.htmlToPlainText
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.AppTabPager
import com.xjtu.toolbox.ui.components.DockedPageTabs
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.FullPageState
import com.xjtu.toolbox.ui.components.PageBottomTabsHost
import com.xjtu.toolbox.ui.components.SkeletonList
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.tabContentPadding
import com.xjtu.toolbox.ui.glass.withoutTop
import com.xjtu.toolbox.util.nowOnCampus
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 智慧社区活动（#465）：找活动、一键报名 / 取消、现场签到签退、我的报名。附件 / 团体报名跳网页。 */
@Composable
fun SsActivityScreen(site: SiteSession, onBack: () -> Unit, onOpenWeb: (String) -> Unit) {
    val vm: SsActivityViewModel = viewModel { SsActivityViewModel(site) }
    val context = LocalContext.current
    val authExpiry = LocalAuthExpiry.current
    LaunchedEffect(vm) { vm.authExpired.collect { authExpiry.onAuthExpired(AppRoute.SsActivity, onBack) } }
    LaunchedEffect(vm.toast) {
        vm.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.toast = null }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(tab) { if (tab == 1 && vm.mine == null) vm.loadMine() }
    val tabs = listOf("活动", "我的报名")
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "活动报名",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                bottomContent = { DockedPageTabs(tabs = tabs, selectedTabIndex = tab, onTabSelected = { tab = it }) },
            )
        },
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        PageBottomTabsHost(tabs = tabs, selectedTabIndex = tab, onTabSelected = { tab = it }, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(padding.withoutTop(glass)).fillMaxSize().glassSource(glass)) {
                AppTabPager(pageCount = 2, selectedTabIndex = tab, onTabSelected = { tab = it }, modifier = Modifier.fillMaxSize()) { page ->
                    val listPadding = tabContentPadding(PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp + glassTop, bottom = 16.dp))
                    if (page == 0) {
                        AppPullToRefresh(
                            isRefreshing = vm.refreshing,
                            onRefresh = { vm.reload(silent = true) },
                            scrollBehavior = scrollBehavior,
                            topPadding = glassTop,
                            modifier = Modifier.fillMaxSize(),
                        ) { ActivityList(vm, listPadding, glassTop) }
                    } else {
                        MineList(vm, listPadding, glassTop)
                    }
                }
            }
        }
        // 必须在 Scaffold 内容里：miuix 的 Overlay 只有 Scaffold 提供宿主，放外面不显示
        ActivitySheet(vm, onOpenWeb)
    }
}

@Composable
private fun ActivityList(vm: SsActivityViewModel, padding: PaddingValues, glassTop: androidx.compose.ui.unit.Dp) {
    LazyColumn(Modifier.fillMaxSize().overScrollVertical(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "search") {
            Column {
                TextField(
                    value = vm.keyword,
                    onValueChange = { vm.keyword = it },
                    label = "搜活动名称，如「讲座」",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("集体活动", selected = vm.groupProof) { vm.showGroupProof() }
                    listOf(SsActivityApi.Order.TIME to "按时间", SsActivityApi.Order.HOT to "按热度", SsActivityApi.Order.POINTS to "按积分").forEach { (o, label) ->
                        Chip(label, selected = !vm.groupProof && vm.order == o) { vm.sortBy(o) }
                    }
                }
                if (vm.groupProof) Text(
                    "讲座、报告、分享会这类活动通常能开集体活动证明，活动介绍里多半不写；能否开以主办方为准。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        when {
            vm.loading -> item(key = "loading") { SkeletonList(Modifier.fillMaxWidth().height(400.dp)) }
            vm.error != null -> item(key = "error") {
                ErrorState(message = vm.error.orEmpty(), onRetry = { vm.reload() }, modifier = Modifier.fillMaxWidth().height(320.dp))
            }
            vm.activities.isEmpty() -> item(key = "empty") {
                EmptyState(title = "没有找到活动", subtitle = "换个关键词试试", modifier = Modifier.fillMaxWidth().height(320.dp))
            }
            else -> {
                items(vm.activities, key = { it.id }) { a ->
                    LaunchedEffect(a.id) { vm.prefetch(a.id) }
                    ActivityCard(a, vm, vm.cardInfo[a.id]) { vm.open(a.id) }
                    if (a.id == vm.activities.last().id) LaunchedEffect(a.id) { vm.loadMore() }
                }
                if (!vm.endReached) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}

@Composable
private fun MineList(vm: SsActivityViewModel, padding: PaddingValues, glassTop: androidx.compose.ui.unit.Dp) {
    val mine = vm.mine
    when {
        vm.mineError != null -> FullPageState(Modifier.fillMaxSize().padding(top = glassTop)) {
            ErrorState(message = vm.mineError.orEmpty(), onRetry = { vm.loadMine() }, modifier = Modifier.fillMaxSize())
        }
        mine == null -> FullPageState(Modifier.fillMaxSize().padding(top = glassTop)) { SkeletonList(Modifier.fillMaxSize()) }
        mine.isEmpty() -> FullPageState(Modifier.fillMaxSize().padding(top = glassTop)) {
            EmptyState(title = "还没有报名记录", subtitle = "在「活动」里找感兴趣的报名", modifier = Modifier.fillMaxSize())
        }
        // 不设 key：同一活动可能有多条报名记录（重新提交、取消后再报），重复 key 会直接崩
        else -> LazyColumn(Modifier.fillMaxSize().overScrollVertical(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(mine) { m ->
                Card(Modifier.fillMaxWidth().clickable(enabled = m.activityId.isNotBlank()) { vm.open(m.activityId) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(m.cover, vm, Modifier.padding(end = 12.dp).size(width = 72.dp, height = 54.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOf(m.start, m.activityStatus, m.review).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 站内封面（要带会话 cookie）：有地址就先占位，文字不会在图到了以后再被挤开；图到了淡入，缓存过的直接显示。 */
@Composable
private fun Cover(url: String?, vm: SsActivityViewModel, modifier: Modifier) {
    if (url == null) return
    val image by produceState(vm.cachedCover(url), url) { if (value == null) value = vm.cover(url) }
    val alpha by animateFloatAsState(if (image != null) 1f else 0f, tween(260), label = "cover")
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f))) {
        image?.let {
            Image(
                it, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().graphicsLayer { this.alpha = alpha },
            )
        }
    }
}

/** 正文里明写的「集体活动 / 校级活动 / 院级活动」加粗染主题色。 */
@Composable
private fun highlightTagWords(text: String): AnnotatedString {
    val accent = MiuixTheme.colorScheme.primary
    return remember(text, accent) {
        val pattern = Regex(EXPLICIT_TAG_WORDS.joinToString("|"))
        buildAnnotatedString {
            var last = 0
            pattern.findAll(text).forEach { m ->
                append(text, last, m.range.first)
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(m.value) }
                last = m.range.last + 1
            }
            append(text, last, text.length)
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val fg = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Text(
        label, style = MiuixTheme.textStyles.footnote1, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private fun statusColor(s: SsActivityStatus) = when (s) {
    SsActivityStatus.ONGOING -> Color(0xFF2E9D5B)
    SsActivityStatus.UPCOMING -> Color(0xFF3478F6)
    SsActivityStatus.ENDED -> Color(0xFF8E8E93)
}

@Composable
private fun StatusPill(s: SsActivityStatus) {
    val c = statusColor(s)
    Text(
        s.label, style = MiuixTheme.textStyles.footnote2, color = c,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Tag(text: String, highlight: Boolean) {
    val c = if (highlight) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Text(
        text, style = MiuixTheme.textStyles.footnote2, color = c, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.10f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ActivityCard(a: SsActivity, vm: SsActivityViewModel, info: SsActivityViewModel.CardInfo?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(a.cover, vm, Modifier.padding(end = 12.dp).size(width = 84.dp, height = 64.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(a.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                StatusPill(a.status)
            }
            Spacer(Modifier.height(6.dp))
            val meta = listOf(a.type, a.org).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 详情（后台预取）到了用明写的标签；没到之前只能按标题推测
            val explicit = info?.explicitTags.orEmpty()
            val tags = explicit.map { it to true } + listOfNotNull(
                (GUESSED_GROUP to false).takeIf { a.likelyGroupProof && "集体活动" !in explicit },
                (NO_APPLY to false).takeIf { (info?.needApply ?: a.needApply) == false },
            ) + listOf(a.nature, a.ability).filter { it.isNotBlank() }.map { it to false }
            if (tags.isNotEmpty()) FlowRow(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tags.forEach { (label, confirmed) -> Tag(label, highlight = confirmed) }
            }
            Text(
                listOfNotNull(
                    a.start.takeIf { it.isNotBlank() },
                    "${a.participants} 人参与",
                    a.points.takeIf { it.isNotBlank() && it != "0" }?.let { "$it 分" },
                ).joinToString(" · "),
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        }
    }
}

private const val GUESSED_GROUP = "疑似集体活动"
private const val NO_APPLY = "无需报名"

@Composable
private fun ActivitySheet(vm: SsActivityViewModel, onOpenWeb: (String) -> Unit) {
    var signing by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val d = vm.detail
    OverlayBottomSheet(show = vm.detailId != null, title = d?.base?.title ?: "活动详情", onDismissRequest = { vm.close() }) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            when {
                vm.detailError != null -> Text(vm.detailError.orEmpty(), color = MiuixTheme.colorScheme.error)
                d == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> DetailBody(d, vm, onOpenWeb, onSign = { code = ""; signing = true })
            }
        }
    }
    OverlayDialog(show = signing, title = "现场签到 / 签退", summary = "输入现场给出的 6 位验证码", onDismissRequest = { signing = false }) {
        TextField(
            value = code,
            onValueChange = { code = it.filter(Char::isLetterOrDigit).take(6) },
            label = "验证码",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            TextButton(text = "取消", onClick = { signing = false }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "提交",
                enabled = code.length == 6,
                onClick = { signing = false; vm.signIn(code) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun DetailBody(d: SsActivityDetail, vm: SsActivityViewModel, onOpenWeb: (String) -> Unit, onSign: () -> Unit) {
    val a = d.base
    val facts = listOfNotNull(
        "状态" to a.status.label,
        ("时间" to listOf(a.start, a.end).filter { it.isNotBlank() }.joinToString(" 至 ")).takeIf { it.second.isNotBlank() },
        ("地点" to d.place).takeIf { d.place.isNotBlank() },
        ("主办" to a.org).takeIf { a.org.isNotBlank() },
        ("报名" to "${d.applyStart} 至 ${d.applyEnd}").takeIf { d.needApply && (d.applyStart.isNotBlank() || d.applyEnd.isNotBlank()) },
        ("报名" to "无需报名，按时参加即可").takeIf { !d.needApply },
        ("名额" to "${d.quota} 人，已有 ${a.participants} 人").takeIf { d.quota != null },
        ("范围" to d.scope).takeIf { d.scope.isNotBlank() },
        ("课外学分" to listOf(a.nature, a.ability).filter { it.isNotBlank() }.joinToString(" · ")).takeIf { a.nature.isNotBlank() || a.ability.isNotBlank() },
        ("积分" to listOf(a.points, d.pointsBreakdown.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()).joinToString("")).takeIf { a.points.isNotBlank() && a.points != "0" },
        ("集体活动" to "标题像讲座类活动，通常可开证明，以主办方为准").takeIf { a.likelyGroupProof && "集体活动" !in d.explicitTags },
        ("我的状态" to "已报名").takeIf { d.applied },
    )
    // 描述里明写的标签直接确认；没写「集体活动」但标题像讲座的，标成「疑似」
    val tags = d.explicitTags.map { it to true } +
        listOfNotNull(
            (GUESSED_GROUP to false).takeIf { a.likelyGroupProof && "集体活动" !in d.explicitTags },
            (NO_APPLY to false).takeIf { !d.needApply },
        )
    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        Cover(a.cover, vm, Modifier.padding(bottom = 10.dp).fillMaxWidth().aspectRatio(16f / 9f))
        if (tags.isNotEmpty()) FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tags.forEach { (label, confirmed) -> Tag(label, highlight = confirmed) }
        }
        facts.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(k, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.width(64.dp))
                Text(v, style = MiuixTheme.textStyles.footnote1)
            }
        }
        val body = remember(d.contentHtml, d.notice) {
            listOf(htmlToPlainText(d.contentHtml), htmlToPlainText(d.notice).let { if (it.isBlank()) it else "须知：$it" })
                .filter { it.isNotBlank() }.joinToString("\n\n")
        }
        if (body.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(highlightTagWords(body), style = MiuixTheme.textStyles.body2)
        }
    }
    Spacer(Modifier.height(14.dp))
    val blocker = d.applyBlocker(nowOnCampus())
    val web = "${SsSession.APP_INDEX}#/hdxq?HDDM=${a.id}"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            d.applied && d.onsiteSignIn && a.status != SsActivityStatus.ENDED ->
                Button(onClick = onSign, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text("现场签到 / 签退", color = MiuixTheme.colorScheme.onPrimary)
                }
            d.needApply && !d.applied && d.applyMode != SsApplyMode.ONE_CLICK ->
                Button(onClick = { onOpenWeb(web) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (d.applyMode == SsApplyMode.GROUP) "去网页团体报名" else "去网页上传附件报名", color = MiuixTheme.colorScheme.onPrimary)
                }
            d.needApply && !d.applied ->
                Button(onClick = { vm.apply() }, enabled = blocker == null && !vm.busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (vm.busy) "提交中…" else blocker ?: "一键报名", color = MiuixTheme.colorScheme.onPrimary)
                }
        }
        if (d.canCancel) {
            TextButton(text = "取消报名", onClick = { vm.cancel() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
        }
        TextButton(text = "在网页中打开", onClick = { onOpenWeb(web) }, modifier = Modifier.fillMaxWidth())
    }
}
