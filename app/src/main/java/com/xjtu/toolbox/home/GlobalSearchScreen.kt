package com.xjtu.toolbox.home

import com.xjtu.toolbox.nav.AppRoute
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.webvpn.WebVpnUtil
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.ChevronRight

/**
 * 搜索结果条目。两种类型：
 * - [SearchEntry.Screen]：跳屏
 * - [SearchEntry.AgentPrompt]：把搜索词直接送给小岱
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
        SearchEntry.AgentPrompt("问小岱", "我的校园卡余额还有多少？", "我的校园卡余额还有多少？",
            listOf("余额", "还有多少")),
        SearchEntry.AgentPrompt("问小岱", "今天我还有哪些课？", "今天我还有哪些课？",
            listOf("今天", "课程")),
        SearchEntry.AgentPrompt("问小岱", "GPA 怎么算？", "GPA 怎么算？",
            listOf("gpa 算法", "绩点算法")),
        SearchEntry.AgentPrompt("问小岱", "最近教务处有什么通知？", "教务处最近有什么通知？",
            listOf("新通知", "最新通知")),
        SearchEntry.AgentPrompt("问小岱", "帮我查空教室", "帮我查空教室",
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlobalSearchBody(
    query: String,
    onQueryChange: (String) -> Unit,
    accountType: AccountType,
    onNavigate: (AppRoute) -> Unit,
    onAskAgent: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenWithWebVpn: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 顶栏占掉的高度：结果从它下面开始排，滚动时能滑到顶栏的模糊下面。 */
    topPadding: Dp = 0.dp,
) {
    // query 是参数不是状态，derivedStateOf 会一直拿着第一次的值，得按它重算
    val results = remember(query, accountType) { GlobalSearchIndex.search(query, accountType) }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
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
                    onChip = onQueryChange,
                    onAskAgent = onAskAgent,
                    topPadding = topPadding,
                )
            } else {
                SearchResultList(
                    query = query,
                    results = results,
                    onNavigate = onNavigate,
                    onAskAgent = onAskAgent,
                    onOpenUrl = onOpenUrl,
                    onOpenWithWebVpn = onOpenWithWebVpn,
                    topPadding = topPadding,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchEmptyHints(
    onChip: (String) -> Unit,
    onAskAgent: (String) -> Unit,
    topPadding: Dp,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = topPadding + 20.dp)) {
        SectionLabel("常用入口")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickChips.forEach { chip ->
                Text(
                    chip,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(com.xjtu.toolbox.ui.components.AppCardColor)
                        .clickable { onChip(chip) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        SectionLabel("问小岱")
        ResultGroup {
            SearchResultRow(SearchIcon.Agent, "今天还有哪些课？", "", onClick = { onAskAgent("今天我还有哪些课？") })
            com.xjtu.toolbox.ui.components.RowDivider(startInset = ROW_TEXT_INSET)
            SearchResultRow(SearchIcon.Agent, "帮我查空教室", "", onClick = { onAskAgent("帮我查空教室") })
        }
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
    topPadding: Dp,
) {
    val url = remember(query) { searchUrlOf(query) }
    val screens = results.filterIsInstance<SearchEntry.Screen>()
    val prompts = results.filterIsInstance<SearchEntry.AgentPrompt>()

    // 末尾让出小白条和键盘，最后一条结果能滚到它们上面
    val bottomInset = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
    LazyColumn(contentPadding = PaddingValues(top = topPadding + 16.dp, bottom = 12.dp + bottomInset)) {
        if (url != null) {
            item(key = "url") {
                SectionLabel("网址")
                ResultGroup {
                    SearchResultRow(SearchIcon.Link, "直接访问", url, onClick = { onOpenUrl(url) })
                    com.xjtu.toolbox.ui.components.RowDivider(startInset = ROW_TEXT_INSET)
                    SearchResultRow(SearchIcon.Link, "通过 WebVPN 访问", "校外访问校内网站", onClick = { onOpenWithWebVpn(WebVpnUtil.toVpnUrl(url)) })
                }
                Spacer(Modifier.height(GROUP_GAP))
            }
        }
        if (screens.isNotEmpty()) {
            item(key = "screens") {
                SectionLabel("功能")
                ResultGroup {
                    screens.forEachIndexed { i, entry ->
                        if (i > 0) com.xjtu.toolbox.ui.components.RowDivider(startInset = ROW_TEXT_INSET)
                        SearchResultRow(SearchIcon.Route(entry.route), entry.title, entry.subtitle, onClick = { onNavigate(entry.route) })
                    }
                }
                Spacer(Modifier.height(GROUP_GAP))
            }
        }
        item(key = "agent") {
            SectionLabel("问小岱")
            ResultGroup {
                prompts.forEach { entry ->
                    SearchResultRow(SearchIcon.Agent, entry.prompt, "", onClick = { onAskAgent(entry.prompt) })
                    com.xjtu.toolbox.ui.components.RowDivider(startInset = ROW_TEXT_INSET)
                }
                SearchResultRow(SearchIcon.Agent, query, "用这句话提问", onClick = { onAskAgent(query) })
            }
        }
    }
}

/** 结果行前面的图标：功能用它自己的图标和配色，网址与提问各一种。 */
private sealed interface SearchIcon {
    data class Route(val route: AppRoute) : SearchIcon
    data object Agent : SearchIcon
    data object Link : SearchIcon
}

/** 行内文字起点：16 边距 + 34 图标 + 12 间距。 */
private val ROW_TEXT_INSET = 62.dp
private val GROUP_GAP = 20.dp

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/** 一组结果收进一张卡，行间细线。 */
@Composable
private fun ResultGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(com.xjtu.toolbox.ui.components.AppCardColor),
        content = content,
    )
}

@Composable
private fun SearchResultRow(
    icon: SearchIcon,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    val (vector, tint) = when (icon) {
        is SearchIcon.Route -> HomeIcons.of(icon.route) to com.xjtu.toolbox.ui.theme.serviceColor(icon.route.id)
        SearchIcon.Agent -> Icons.Default.TravelExplore to primary
        SearchIcon.Link -> Icons.Default.Language to primary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = if (icon == SearchIcon.Agent) "问小岱：$title" else title
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(vector, contentDescription = null, modifier = Modifier.size(19.dp), tint = tint)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
        )
    }
}
