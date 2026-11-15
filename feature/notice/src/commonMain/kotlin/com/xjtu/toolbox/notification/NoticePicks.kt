package com.xjtu.toolbox.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.agent.InsightSpec
import com.xjtu.toolbox.agent.PidaiCard
import com.xjtu.toolbox.inbox.SelfTodoRules
import com.xjtu.toolbox.inbox.SelfTodoStore
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.ui.components.AppInsetColor
import com.xjtu.toolbox.ui.components.PidaiChip
import com.xjtu.toolbox.ui.components.PidaiChipRow
import com.xjtu.toolbox.ui.components.PidaiInsightCard
import com.xjtu.toolbox.ui.components.rememberInsight
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 通知页顶上的「小岱精选」：按身份（或用户自选的来源）和关注主题挑出跟这个人有关的几条。 */
internal val NOTICE_PICKS = InsightSpec(
    id = "notice_picks",
    tools = setOf("get_notifications"),
    ttlMs = 3 * 60 * 60 * 1000L,
    busyHint = "在翻通知…",
    instruction = """
任务：从学校通知里挑出跟这位用户真正相关、值得点开的通知。要快：工具最多调 4 次。
- 范围：给了「来源」就只查这些来源（get_notifications 的 source，一次一个，最多 3 个）；写着「按身份自动」就不给 source 查一次（它按学校系统里的身份合并了教务、书院、学院等来源）；用户记住的学院、书院跟画像不一样时，改用记住的那个当 source 查。
- 给了「关注主题」就挑最要紧的 1 个主题用 keyword 查一次；其余主题在已拿到的结果里按标题筛。
- 跳过与身份不符的（研究生院通知之于本科生等）、已过期的活动、纯新闻宣传和人事任免。
- 最多 6 条，按对他的重要性排；同一件事多个来源只留一条。
只输出：
{"summary":"不超过 30 字的总括","picks":[{"title":"原标题","source":"来源名","date":"yyyy-MM-dd","link":"原链接","why":"不超过 25 字：为什么跟他有关","due":"报名或截止时间 yyyy-MM-dd 或 yyyy-MM-dd HH:mm，没有就 null","level":3}]}
level：3 必须处理，2 值得一看，1 顺便知道。没有值得看的就 picks 为空，summary 说一声。
    """.trimIndent(),
    card = PidaiCard.NOTICE,
)

/** 精选的范围设置：按账号记在本机。 */
private object NoticePicksPrefs {
    private val store get() = keyValueStore("notice_picks" + AccountContext.safeSuffix())
    val TOPICS = listOf("奖学金", "推免", "竞赛", "讲座", "考试", "选课", "实习", "评优", "志愿")

    var sources: Set<NotificationSource>
        get() = store.getString("sources").orEmpty().split(',').mapNotNull { n -> NotificationSource.entries.firstOrNull { it.name == n } }.toSet()
        set(v) = store.putString("sources", v.joinToString(",") { it.name })

    var topics: Set<String>
        get() = store.getString("topics").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        set(v) = store.putString("topics", v.joinToString(","))
}

@Composable
internal fun NoticePicksCard(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var sources by remember { mutableStateOf(NoticePicksPrefs.sources) }
    var topics by remember { mutableStateOf(NoticePicksPrefs.topics) }
    var editing by remember { mutableStateOf(false) }
    val input = remember(sources, topics) {
        buildString {
            append("今天是 ${todayOnCampus()}，用户打开了通知页。\n来源：")
            append(if (sources.isEmpty()) "按身份自动" else sources.joinToString("、") { it.displayName })
            append("\n关注主题：").append(topics.joinToString("、").ifBlank { "无" })
        }
    }
    val state = rememberInsight(NOTICE_PICKS, input)
    PidaiInsightCard(
        title = "小岱精选",
        state = state,
        modifier = modifier,
        subtitle = if (sources.isEmpty()) "按你的身份挑" else "从 ${sources.size} 个来源里挑",
        askPrompt = { "通知精选里这几条，哪些要马上处理、怎么办？" },
        controls = {
            PidaiChipRow {
                PidaiChip(
                    if (sources.isEmpty()) "范围：按身份自动" else "范围：${sources.size} 个来源",
                    icon = Icons.Filled.Tune,
                    selected = editing,
                    onClick = { editing = !editing },
                )
                NoticePicksPrefs.TOPICS.forEach { t ->
                    PidaiChip(t, selected = t in topics, onClick = {
                        topics = if (t in topics) topics - t else topics + t
                        NoticePicksPrefs.topics = topics
                    })
                }
            }
            if (editing) SourcePicker(sources) { sources = it; NoticePicksPrefs.sources = it }
        },
    ) { json ->
        json["summary"].safeStringOrNull()?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        val picks = (json["picks"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        picks.forEach { PickRow(it, onOpen) }
    }
}

/** 来源多选：按分类分行；一个都不选 = 按身份自动。 */
@Composable
private fun SourcePicker(selected: Set<NotificationSource>, onChange: (Set<NotificationSource>) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AppInsetColor).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PidaiChipRow {
            PidaiChip("按身份自动", icon = if (selected.isEmpty()) Icons.Filled.Check else null, selected = selected.isEmpty(), onClick = { onChange(emptySet()) })
        }
        SourceCategory.entries.forEach { cat ->
            val list = NotificationSource.entries.filter { it.category == cat }
            if (list.isEmpty()) return@forEach
            Text(cat.displayName, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            PidaiChipRow {
                list.forEach { s ->
                    PidaiChip(s.displayName, selected = s in selected, onClick = {
                        onChange(if (s in selected) selected - s else selected + s)
                    })
                }
            }
        }
    }
}

@Composable
private fun PickRow(pick: JsonObject, onOpen: (String) -> Unit) {
    val title = pick["title"].safeStringOrNull().orEmpty().ifBlank { return }
    val link = pick["link"].safeStringOrNull().orEmpty()
    val due = pick["due"].safeStringOrNull()?.takeIf { it.isNotBlank() && it != "null" }
    val level = pick["level"].safeStringOrNull()?.toIntOrNull() ?: 1
    var added by remember(title) { mutableStateOf(false) }
    val parsed = due?.let { SelfTodoRules.parseDue(it) }
    com.xjtu.toolbox.ui.components.NoticePickItem(
        title = title,
        metadata = listOfNotNull(pick["source"].safeStringOrNull(), pick["date"].safeStringOrNull(), due?.let { "截止 $it" })
            .filter { it.isNotBlank() }.joinToString(" · "),
        why = pick["why"].safeStringOrNull().orEmpty(), level = level,
        enabled = link.startsWith("https://") || link.startsWith("http://"), onOpen = { onOpen(link) },
        trailing = if (parsed != null) ({
            PidaiChip(
                if (added) "已记待办" else "记待办",
                icon = if (added) Icons.Filled.Check else Icons.AutoMirrored.Filled.EventNote,
                enabled = !added,
                onClick = {
                    added = SelfTodoStore.create(title = title, content = link, dueDate = parsed.first,
                        source = "agent", allDay = parsed.second, remind = true) != null
                },
            )
        }) else null,
    )
}
