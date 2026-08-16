package com.xjtu.toolbox.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.ui.components.AppSearchBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TravelExplore

/**
 * 搜索结果条目。两种类型：
 * - [TypeScreen]：跳屏
 * - [TypeAgentPrompt]：把搜索词直接送给屁岱，让它自然分流
 *
 * 选择 [TypeAgentPrompt] 而不是工具直跳：用户搜"成绩"是想看成绩，搜"空教室"是想查空教室，
 * 但搜"高数成绩"是问问题。统一走 AI 自动分发，比维护一套 query 解析器靠谱得多。
 */
internal sealed class SearchEntry {
    abstract val title: String
    abstract val subtitle: String

    data class Screen(
        override val title: String,
        override val subtitle: String,
        val route: String,
        val aliases: List<String> = emptyList(),
    ) : SearchEntry()

    data class AgentPrompt(
        override val title: String,
        override val subtitle: String,
        val prompt: String,
        val aliases: List<String> = emptyList(),
    ) : SearchEntry()
}

/**
 * 静态搜索索引。
 *
 * 设计原则：覆盖**用户**输入而非**所有工具**——20 多个 tool 都列出来反而劝退，
 * 索引只列最常被搜的几条 + 让用户去 agent 屏用自然语言命中长尾。
 */
internal object GlobalSearchIndex {
    fun entries(): List<SearchEntry> = listOf(
        SearchEntry.Screen("我的课表", "查看本学期课表", Routes.SCHEDULE,
            listOf("课表", "课程", "schedule", "今天", "明天")),
        SearchEntry.Screen("空教室", "查找自习空教室", Routes.EMPTY_ROOM,
            listOf("空教室", "自习", "教室")),
        SearchEntry.Screen("校园卡", "余额与今日消费", Routes.CAMPUS_CARD,
            listOf("校园卡", "余额", "一卡通")),
        SearchEntry.Screen("教务通知", "教务处通知公告", Routes.NOTIFICATION,
            listOf("通知", "公告", "教务")),
        SearchEntry.Screen("成绩查询", "本学期成绩与 GPA", Routes.JWAPP_SCORE,
            listOf("成绩", "分数", "gpa", "绩点")),
        SearchEntry.Screen("成绩报表", "历年成绩明细", Routes.SCORE_REPORT,
            listOf("成绩单", "报表")),
        SearchEntry.Screen("图书馆", "借阅与座位", Routes.LIBRARY,
            listOf("图书", "借书", "座位", "自习室")),
        SearchEntry.Screen("校历", "学期与考试安排", Routes.SCHOOL_CALENDAR,
            listOf("校历", "学期", "周数")),
        SearchEntry.Screen("付款码", "出示校园付款码", Routes.PAYMENT_CODE,
            listOf("付款", "扫码")),
        SearchEntry.Screen("空闲场馆", "预约羽毛球/网球", Routes.VENUE,
            listOf("场馆", "羽毛", "网球场")),
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

    fun search(query: String): List<SearchEntry> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        return entries().filter { e ->
            val titles = listOf(e.title) + when (e) {
                is SearchEntry.Screen -> e.aliases
                is SearchEntry.AgentPrompt -> e.aliases
            }
            titles.any { it.lowercase().contains(q) }
        }
    }
}

/**
 * 全局搜索屏。从 Home 顶栏的搜索图标进入。
 *
 * 结果分两组：屏幕直达 / 问屁岱。最后一行永远带"问屁岱：xxx"兜底。
 */
@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onAskAgent: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by remember {
        derivedStateOf { GlobalSearchIndex.search(query) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "搜索",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
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
                label = "试试搜索：课表 / 校园卡 / 空教室",
                modifier = Modifier.fillMaxWidth()
            )

            if (query.isBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("或者直接问屁岱，AI 会帮你找。")
                return@Column
            }

            Spacer(Modifier.height(12.dp))
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (results.isEmpty()) {
                    item {
                        Text("没有匹配的屏幕，试试直接问屁岱：")
                        Spacer(Modifier.height(8.dp))
                    }
                } else {
                    items(results, key = { it::class.simpleName + it.title + it.subtitle }) { entry ->
                        when (entry) {
                            is SearchEntry.Screen -> SearchResultRow(
                                title = entry.title,
                                subtitle = entry.subtitle,
                                showAiIcon = false,
                                onClick = { onNavigate(entry.route) }
                            )
                            is SearchEntry.AgentPrompt -> SearchResultRow(
                                title = "问屁岱",
                                subtitle = entry.prompt,
                                showAiIcon = true,
                                onClick = { onAskAgent(entry.prompt) }
                            )
                        }
                    }
                }

                // 兜底：永远显示"问屁岱：xxx"
                item {
                    Spacer(Modifier.height(4.dp))
                    SearchResultRow(
                        title = "问屁岱：" + query,
                        subtitle = "让 AI 来处理",
                        showAiIcon = true,
                        onClick = { onAskAgent(query) }
                    )
                }
            }
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
    // PR-17 TalkBack：mergeDescendants=true 让无障碍服务一次读出整行（title + subtitle + icon），
    // 而不是分开念多个分散节点。
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = if (showAiIcon) {
                    "问屁岱：$title"
                } else {
                    title
                }
            }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAiIcon) {
                Icon(
                    Icons.Default.TravelExplore,
                    contentDescription = null, // 由 surface semantics 统一读出
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
        }
    }
}