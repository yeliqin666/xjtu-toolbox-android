package com.xjtu.toolbox.superapp

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.xjtu.toolbox.auth.AccessMode
import com.xjtu.toolbox.auth.LocalAuthExpiry
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.rememberPhoneDialer
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppChipTabRow
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.FullPageState
import com.xjtu.toolbox.ui.components.SkeletonList
import com.xjtu.toolbox.ui.components.appTonalButtonColors
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import com.xjtu.toolbox.util.withXianAreaCode
import com.xjtu.toolbox.webvpn.WebVpnUtil
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val ALL = "全部"

/**
 * 移动交大：学校服务大厅的分类与服务。点服务在内置浏览器里打开，**不注入 App 的登录态**——
 * 子服务各自走统一认证 / 开放平台 OAuth，登录与回调全由网页自己完成（见 [AppRoute.Browser.bare]）。
 * 长按看服务说明；「专区」点开是它的子服务。
 */
@Composable
fun SuperAppScreen(site: SiteSession, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val vm: SuperAppViewModel = viewModel { SuperAppViewModel(site, DataCache(context)) }
    val authExpiry = LocalAuthExpiry.current
    LaunchedEffect(vm) { vm.authExpired.collect { authExpiry.onAuthExpired(AppRoute.SuperApp, onBack) } }
    LaunchedEffect(vm.toast) {
        vm.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.toast = null }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(ALL) }
    var zone by remember { mutableStateOf<SuperAppService?>(null) }
    var detail by remember { mutableStateOf<SuperAppService?>(null) }

    val categories = vm.categories
    // 搜索要搜到专区里的子服务；同一服务挂在多个分类下只出一次
    val everything = remember(categories) {
        categories.orEmpty().flatMap { c -> c.services.flatMap { listOf(it) + it.children } }.distinctBy { it.url.ifEmpty { it.id } }
    }
    val results = remember(everything, query) { if (query.isBlank()) emptyList() else everything.filter { it.matches(query) } }
    val shown = remember(categories, selected) {
        categories.orEmpty().let { all -> if (selected == ALL) all else all.filter { it.name == selected }.ifEmpty { all } }
    }
    // 只能校内访问的服务：App 判定在校外（全局走 WebVPN）时换成网关地址，点的那一刻再判
    val offCampus = { site.manager?.currentAccessMode?.value == AccessMode.WEBVPN }
    val target = { s: SuperAppService, viaVpn: Boolean -> if (viaVpn) WebVpnUtil.toVpnUrl(s.url) else s.url }
    val open: (SuperAppService) -> Unit = { s -> if (s.isZone) zone = s else onOpen(target(s, s.campusOnly && offCampus())) }
    val showDetail: (SuperAppService) -> Unit = { s -> zone = null; detail = s }

    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    val glass = rememberPageGlass()
    Scaffold(
        topBar = { GlassTopAppBar(title = "移动交大", glass = glass, scrollBehavior = scrollBehavior, onBack = onBack) },
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        AppPullToRefresh(
            isRefreshing = vm.refreshing,
            onRefresh = vm::refresh,
            scrollBehavior = scrollBehavior,
            topPadding = glassTop,
            modifier = Modifier.padding(padding.withoutTop(glass)).glassSource(glass).fillMaxSize(),
        ) {
            when {
                categories == null && vm.error != null -> FullPageState(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = glassTop)) {
                    ErrorState(vm.error.orEmpty(), onRetry = vm::refresh, modifier = Modifier.fillMaxSize())
                }
                categories == null -> FullPageState(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = glassTop)) {
                    SkeletonList(Modifier.fillMaxSize(), rows = 5, rowHeight = 120.dp)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().overScrollVertical(),
                    contentPadding = PaddingValues(top = glassTop + 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "search") {
                        AppSearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            label = "搜索 ${everything.count { !it.isZone }} 项服务",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                    if (query.isNotBlank()) {
                        item(key = "results") {
                            if (results.isEmpty()) {
                                EmptyState(title = "没有找到相关服务", subtitle = "试试服务名、拼音或主管部门", modifier = Modifier.fillMaxWidth().padding(top = 48.dp))
                            } else {
                                SectionCard(title = "搜索结果", count = results.size) { ServiceGrid(results, open, showDetail) }
                            }
                        }
                    } else {
                        item(key = "chips") {
                            AppChipTabRow(
                                options = listOf(ALL) + categories.map { it.name },
                                selected = selected,
                                onSelect = { selected = it },
                                label = { it },
                            )
                        }
                        itemsIndexed(shown, key = { _, c -> c.name }) { i, c ->
                            SectionCard(title = c.name, count = c.services.size, modifier = Modifier.enterOnce(i)) {
                                ServiceGrid(c.services, open, showDetail)
                            }
                        }
                    }
                    item(key = "note") {
                        Text(
                            "服务在内置浏览器里打开，登录状态与 App 分开；校内服务在校外会自动走 WebVPN。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // miuix 的 Overlay 只有 Scaffold 提供宿主，必须放在内容里
        OverlayBottomSheet(show = zone != null, title = zone?.name.orEmpty(), onDismissRequest = { zone = null }) {
            val current = zone
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                if (current != null) ServiceGrid(current.children, { zone = null; open(it) }, showDetail)
            }
        }
        OverlayBottomSheet(show = detail != null, title = detail?.name.orEmpty(), onDismissRequest = { detail = null }) {
            detail?.let { s ->
                val autoVpn = s.campusOnly && offCampus()
                ServiceDetail(
                    s,
                    autoVpn = autoVpn,
                    onOpen = { detail = null; open(s) },
                    onOpenAlt = { detail = null; onOpen(target(s, !autoVpn)) },
                )
            }
        }
    }
}

/** 一个分类：标题、数量和服务宫格，装在一张圆角卡片里。 */
@Composable
private fun SectionCard(title: String, count: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(AppCardColor)
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text("$count", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** 等宽宫格：手机 4 列，宽屏按约 84dp 一格加列；最后一行不足的留空，不拉伸。 */
@Composable
private fun ServiceGrid(services: List<SuperAppService>, onClick: (SuperAppService) -> Unit, onLongClick: (SuperAppService) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 84.dp).toInt().coerceIn(4, 8)
        Column {
            services.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { s -> ServiceTile(s, Modifier.weight(1f), { onClick(s) }, { onLongClick(s) }) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServiceTile(s: SuperAppService, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClickLabel = "打开", onLongClickLabel = "服务详情", onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            ServiceIcon(s, 46.dp)
            // 专区：右上角标出里面有几项
            if (s.isZone) Box(
                Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp).size(18.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("${s.children.size}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            s.name,
            style = MiuixTheme.textStyles.footnote1,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 学校的图标是透明底的黑色线稿，深色模式直接放上去看不见，所以深浅主题都垫一块浅色底板。
 * 图没到或取不到时显示服务名的首字。
 */
@Composable
private fun ServiceIcon(s: SuperAppService, size: Dp) {
    var loaded by remember(s.icon) { mutableStateOf(false) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(ICON_PLATE),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) Text(s.name.take(1), fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold, color = ICON_FALLBACK)
        if (s.icon != null) AsyncImage(
            model = s.icon,
            contentDescription = null,
            modifier = Modifier.size(size * 0.68f),
            onState = { loaded = it is AsyncImagePainter.State.Success },
        )
    }
}

private val ICON_PLATE = Color(0xFFEFF3F9)
private val ICON_FALLBACK = Color(0xFF2F6BD8)

@Composable
private fun ServiceDetail(s: SuperAppService, autoVpn: Boolean, onOpen: () -> Unit, onOpenAlt: () -> Unit) {
    val dial = rememberPhoneDialer()
    Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceIcon(s, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (s.department.isNotBlank()) Text(s.department, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                    val meta = listOfNotNull(
                        "校内服务".takeIf { s.campusOnly },
                        "${s.children.size} 项服务".takeIf { s.isZone },
                        "${s.clicks} 次使用".takeIf { s.clicks > 0 },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (s.description.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(s.description, style = MiuixTheme.textStyles.body2)
            }
            if (s.contact.isNotBlank()) {
                val shown = s.contact.withXianAreaCode()
                val first = shown.split('/', '、', ',', '，', ' ').firstOrNull { it.any(Char::isDigit) }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("联系电话", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.width(64.dp))
                    Text(
                        shown,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (first != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        modifier = if (first != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "拨打") { dial(first.filter { it.isDigit() || it == '-' }) } else Modifier,
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
            Text(
                when {
                    s.isZone -> "查看专区"
                    autoVpn -> "经 WebVPN 打开"
                    else -> "打开"
                },
                color = MiuixTheme.colorScheme.onPrimary,
            )
        }
        if (!s.isZone) {
            Spacer(Modifier.height(10.dp))
            // 和上面相反的那条路：校园网判错了也有退路
            Button(onClick = onOpenAlt, modifier = Modifier.fillMaxWidth(), colors = appTonalButtonColors()) {
                Text(if (autoVpn) "直接打开（已连校园网）" else "通过 WebVPN 打开（校外）")
            }
        }
    }
}
