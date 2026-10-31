package com.xjtu.toolbox.home

import com.xjtu.toolbox.ui.components.BackButton
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.FullScreenOverlay
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.webvpn.WebVpnUtil
import com.xjtu.toolbox.ui.components.AppSearchBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.ChevronRight
import kotlinx.coroutines.delay

/**
 * 搜索结果条目。两种类型：
 * - [SearchEntry.Screen]：跳屏
 * - [SearchEntry.AgentPrompt]：把搜索词直接送给屁岱
 */
internal sealed class SearchEntry {
    abstract val title: String
    abstract val subtitle: String
    abstract val aliases: List<String>

    data class Screen(
        override val title: String,
        override val subtitle: String,
        val route: AppRoute,
        override val aliases: List<String> = emptyList(),
    ) : SearchEntry()

    data class AgentPrompt(
        override val title: String,
        override val subtitle: String,
        val prompt: String,
        override val aliases: List<String> = emptyList(),
    ) : SearchEntry()
}

internal object GlobalSearchIndex {
    private val agentPrompts = listOf(
        SearchEntry.AgentPrompt("问屁岱", "我的校园卡余额还有多少？", "我的校园卡余额还有多少？",
            listOf("余额", "还有多少")),
        SearchEntry.AgentPrompt("问屁岱", "今天我还有哪些课？", "今天我还有哪些课？",
            listOf("今天", "课程")),
        SearchEntry.AgentPrompt("问屁岱", "GPA 怎么算？", "GPA 怎么算？",
            listOf("gpa 算法", "绩点算法")),
        SearchEntry.AgentPrompt("问屁岱", "最近教务处有什么通知？", "教务处最近有什么通知？",
            listOf("新通知", "最新通知")),
        SearchEntry.AgentPrompt("问屁岱", "帮我查空教室", "帮我查空教室",
            listOf("找空教室", "哪里空")),
    )

    fun entries(accountType: AccountType? = null): List<SearchEntry> {
        val services = if (accountType == null) AppServices.all else AppServices.visibleFor(accountType)
        return services.map { svc ->
            SearchEntry.Screen(
                title = svc.title,
                subtitle = svc.subtitle,
                route = svc.route,
                // 页内功能的名称也能搜到，比如「干饭人账单」直达校园卡
                aliases = svc.aliases + svc.features.map { it.substringBefore('：') },
            )
        } + agentPrompts
    }

    fun search(query: String, accountType: AccountType? = null): List<SearchEntry> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        return entries(accountType).filter { e ->
            val haystack = buildList {
                add(e.title)
                add(e.subtitle)
                addAll(e.aliases)
            }
            haystack.any { it.lowercase().contains(q) }
        }
    }
}

private val QuickChips = listOf("课表", "空教室", "校园卡", "成绩", "付款码", "通知")

private val UrlHost = Regex("""(?i)([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}(:\d{1,5})?|(\d{1,3}\.){3}\d{1,3}(:\d{1,5})?""")

/**
 * 搜索词看起来像网址就补全成可访问的 URL，否则 null。
 * 有 http(s):// 前缀的直接认；没有前缀的要求首段像域名（`xxx.xjtu.edu.cn/...`、`example.com`），补 https://。
 */
internal fun searchUrlOf(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    val scheme = Regex("""(?i)^https?://""").find(text)
    val rest = text.substring(scheme?.value?.length ?: 0)
    val host = rest.takeWhile { it != '/' && it != '?' && it != '#' }
    if (scheme != null) return text.takeIf { host.isNotEmpty() }
    return if (UrlHost.matches(host)) "https://$text" else null
}

/**
 * 全局搜索。盖在主界面最外层的整屏浮层（见 [FullScreenOverlay]），盖过首页大标题和悬浮底栏。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
    onAskAgent: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    /** 参数是已转成 WebVPN 域的网址。 */
    onOpenWithWebVpn: (String) -> Unit,
    accountType: AccountType = AccountType.UNDERGRADUATE,
) {
    FullScreenOverlay {
        var query by rememberSaveable { mutableStateOf("") }
        val results by remember(accountType) { derivedStateOf { GlobalSearchIndex.search(query, accountType) } }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(80)
            runCatching { focusRequester.requestFocus() }
        }

        BackHandler(onBack = onBack)

        // 纯色底：整页模糊会把首页头卡的液体色透成一大片色块，反而脏
        val scaffoldColor = MiuixTheme.colorScheme.surface

        Box(Modifier.fillMaxSize().background(scaffoldColor)) {
            Scaffold(
                containerColor = scaffoldColor,
                // 底部不让位：结果列表从小白条下面滚过，末尾净空由列表自己补（见 SearchResultList）
                contentWindowInsets = WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                topBar = {
                    TopAppBar(
                        title = "搜索",
                        color = scaffoldColor,
                        navigationIcon = {
                            BackButton(onBack)
                        },
                        actions = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "清空")
                                }
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(Modifier.height(4.dp))
                    AppSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        label = "课表、成绩、空教室、问屁岱…",
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )

                    AnimatedContent(
                        targetState = query.isBlank(),
                        transitionSpec = {
                            (fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 12 })
                                .togetherWith(fadeOut(tween(120)))
                        },
                        label = "searchBody",
                        modifier = Modifier.fillMaxSize(),
                    ) { blank ->
                        if (blank) {
                            SearchEmptyHints(
                                onChip = { query = it },
                                onAskAgent = onAskAgent,
                            )
                        } else {
                            SearchResultList(
                                query = query,
                                results = results,
                                onNavigate = onNavigate,
                                onAskAgent = onAskAgent,
                                onOpenUrl = onOpenUrl,
                                onOpenWithWebVpn = onOpenWithWebVpn,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchEmptyHints(
    onChip: (String) -> Unit,
    onAskAgent: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            "常用入口",
            style = MiuixTheme.textStyles.subtitle,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickChips.forEach { chip ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MiuixTheme.colorScheme.surfaceVariant,
                    // 先裁成圆角再接点击，按下的高亮才跟着圆角走
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { onChip(chip) },
                ) {
                    Text(
                        chip,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "直接问屁岱",
            style = MiuixTheme.textStyles.subtitle,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "搜不到也没关系，把问题丢给 AI。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(12.dp))
        SearchResultRow(
            title = "今天还有哪些课？",
            subtitle = "问屁岱",
            showAiIcon = true,
            onClick = { onAskAgent("今天我还有哪些课？") },
        )
        Spacer(Modifier.height(8.dp))
        SearchResultRow(
            title = "帮我查空教室",
            subtitle = "问屁岱",
            showAiIcon = true,
            onClick = { onAskAgent("帮我查空教室") },
        )
    }
}

@Composable
private fun SearchResultList(
    query: String,
    results: List<SearchEntry>,
    onNavigate: (AppRoute) -> Unit,
    onAskAgent: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenWithWebVpn: (String) -> Unit,
) {
    val url = remember(query) { searchUrlOf(query) }
    val screens = results.filterIsInstance<SearchEntry.Screen>()
    val prompts = results.filterIsInstance<SearchEntry.AgentPrompt>()

    // 末尾让出小白条和键盘，最后一条结果能滚到它们上面
    val bottomInset = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
    LazyColumn(
        contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (url != null) {
            item(key = "url-direct") {
                SearchResultRow(title = "直接访问", subtitle = url, showAiIcon = false, onClick = { onOpenUrl(url) })
            }
            item(key = "url-vpn") {
                SearchResultRow(
                    title = "通过 WebVPN 访问",
                    subtitle = "校外访问校内网站",
                    showAiIcon = false,
                    onClick = { onOpenWithWebVpn(WebVpnUtil.toVpnUrl(url)) },
                )
            }
        }
        if (screens.isNotEmpty()) {
            item(key = "hdr-screen") {
                Text(
                    "功能",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 2.dp, top = 4.dp),
                )
            }
            items(screens, key = { "s-${it.route.id}-${it.title}" }) { entry ->
                SearchResultRow(
                    title = entry.title,
                    subtitle = entry.subtitle,
                    showAiIcon = false,
                    onClick = { onNavigate(entry.route) }
                )
            }
        }
        if (prompts.isNotEmpty()) {
            item(key = "hdr-ai") {
                Text(
                    "问屁岱",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 2.dp, top = 8.dp),
                )
            }
            items(prompts, key = { "a-${it.prompt}" }) { entry ->
                SearchResultRow(
                    title = entry.prompt,
                    subtitle = "让 AI 处理",
                    showAiIcon = true,
                    onClick = { onAskAgent(entry.prompt) }
                )
            }
        }
        if (results.isEmpty()) {
            item(key = "empty") {
                Text(
                    "没有直接匹配的功能",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        item(key = "fallback") {
            SearchResultRow(
                title = "问屁岱：$query",
                subtitle = "用这句话提问",
                showAiIcon = true,
                onClick = { onAskAgent(query) }
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    title: String,
    subtitle: String,
    showAiIcon: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MiuixTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = if (showAiIcon) "问屁岱：$title" else title
            }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAiIcon) {
                Icon(
                    Icons.Default.TravelExplore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
