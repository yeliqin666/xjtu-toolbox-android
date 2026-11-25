package com.xjtu.toolbox.zyxf

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.agent.AgentInsightHub
import com.xjtu.toolbox.agent.InsightSpec
import com.xjtu.toolbox.agent.PidaiCard
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.schedule.ExamCountdown
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.ui.components.PidaiChip
import com.xjtu.toolbox.ui.components.PidaiHero
import com.xjtu.toolbox.ui.components.PidaiIconAction
import com.xjtu.toolbox.ui.components.PidaiInsightCard
import com.xjtu.toolbox.ui.components.PidaiList
import com.xjtu.toolbox.ui.components.PidaiListRow
import com.xjtu.toolbox.ui.components.PidaiSummary
import com.xjtu.toolbox.ui.components.rememberInsight
import top.yukonga.miuix.kmp.basic.Icon
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 学辅页顶上的「为你找资料」：按本学期的课和临近的考试，在资料站里找历年卷、复习资料和笔记。 */
private val ZYXF_PICKS = InsightSpec(
    id = "zyxf_picks",
    tools = setOf("list_zyxf"),
    ttlMs = 24 * 60 * 60 * 1000L,
    busyHint = "在资料站里翻…",
    instruction = """
任务：在仲英学辅资料站给用户找他这学期的课用得上的资料：历年卷、复习资料、课件、笔记。
- 有考试临近的课先找；每门课用 list_zyxf 的 keyword 搜课程名（太长就用核心词，如「高等数学」→「高数」也试），工具最多调 6 次。
- 只挑名字看得出有用的文件（带年份的卷子、答案、复习提纲优先），别挑文件夹。每门最多 2 个，总共最多 8 个。
- 搜不到的课在 summary 里说一句。
只输出：
{"summary":"不超过 30 字","items":[{"course":"课程名","file_id":123,"name":"原文件名","path":"目录路径","why":"不超过 20 字"}]}
    """.trimIndent(),
    card = PidaiCard.ZYXF,
)

/** 本学期课表里的课名和 30 天内的考试（本机缓存，不联网）。 */
private fun zyxfPicksInput(context: Context): String? {
    val cache = DataCache(context)
    val term = ScheduleCache.readCurrentTerm(cache) ?: return null
    val courses = ScheduleCache.readCourses(cache, term).orEmpty()
        .filter { !it.isUserCreated }.map { it.courseName }.filter { it.isNotBlank() }.distinct()
    if (courses.isEmpty()) return null
    val today = todayOnCampus()
    val exams = ScheduleCache.readExams(cache, term).orEmpty().mapNotNull { e ->
        val d = ExamCountdown.parseDate(e.examDate) ?: return@mapNotNull null
        val days = (d.toEpochDays() - today.toEpochDays()).toInt()
        if (days in 0..30) "${e.courseName}（$days 天后考）" else null
    }
    return buildString {
        append("本学期的课：").append(courses.joinToString("、")).append('\n')
        append("临近的考试：").append(exams.joinToString("、").ifBlank { "暂无" })
    }
}

@Composable
internal fun ZyxfPicksCard(
    onPreview: (ZyxfApi.Entry) -> Unit,
    onDownload: (ZyxfApi.Entry) -> Unit,
    downloadState: (Int) -> String?,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val input = remember { zyxfPicksInput(context) }
    val state = rememberInsight(ZYXF_PICKS, input)
    PidaiInsightCard(
        title = "为你找资料",
        state = state,
        modifier = modifier,
        hint = if (input == null) "日程页同步过课表后，小岱按你的课来找" else "",
        askPrompt = { "学辅里这几份资料，挑最该看的帮我讲讲重点。" },
        primary = { json ->
            picks(json).firstOrNull()?.let { (_, entry) ->
                PidaiChip("让小岱讲这份", icon = Icons.AutoMirrored.Filled.Chat, emphasized = true, onClick = { explain(entry) })
            }
        },
    ) { json ->
        PidaiSummary(json["summary"].safeStringOrNull())
        val list = picks(json)
        list.firstOrNull()?.let { (item, entry) ->
            PidaiHero(
                title = entry.name,
                label = item["course"].safeStringOrNull().orEmpty(),
                detail = item["why"].safeStringOrNull().orEmpty(),
                onClick = { onPreview(entry) },
                leading = { FileIcon(entry, MiuixTheme.colorScheme.primary) },
                trailing = { DownloadAction(entry, onDownload, downloadState) },
            )
        }
        PidaiList(list.drop(1)) { (item, entry) ->
            PidaiListRow(
                title = entry.name,
                overline = item["course"].safeStringOrNull().orEmpty(),
                detail = item["why"].safeStringOrNull().orEmpty(),
                onClick = { onPreview(entry) },
                leading = { FileIcon(entry, MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                trailing = {
                    DownloadAction(entry, onDownload, downloadState)
                    PidaiIconAction(Icons.AutoMirrored.Filled.Chat, "让小岱讲", onClick = { explain(entry) })
                },
            )
        }
    }
}

private fun picks(json: JsonObject): List<Pair<JsonObject, ZyxfApi.Entry>> =
    (json["items"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }.mapNotNull { item ->
        val id = item["file_id"].safeStringOrNull()?.toIntOrNull() ?: return@mapNotNull null
        val name = item["name"].safeStringOrNull().orEmpty().ifBlank { return@mapNotNull null }
        item to ZyxfApi.Entry(
            id = id, name = name, path = item["path"].safeStringOrNull().orEmpty(), isFolder = false,
            ext = name.substringAfterLast('.', ""),
        )
    }

private fun explain(entry: ZyxfApi.Entry) = AgentInsightHub.insights.ask(
    "帮我讲讲学辅资料「${entry.name}」：先读它（file_id ${entry.id}），再挑重点讲，有题就讲思路。",
    null,
)

@Composable
private fun FileIcon(entry: ZyxfApi.Entry, tint: androidx.compose.ui.graphics.Color) {
    Icon(
        if (entry.ext.equals("pdf", ignoreCase = true)) Icons.Filled.PictureAsPdf else Icons.AutoMirrored.Filled.InsertDriveFile,
        contentDescription = null, tint = tint, modifier = Modifier.size(24.dp),
    )
}

/** 下载：进行中 / 已下载时换成状态字。 */
@Composable
private fun DownloadAction(entry: ZyxfApi.Entry, onDownload: (ZyxfApi.Entry) -> Unit, downloadState: (Int) -> String?) {
    val status = downloadState(entry.id)
    if (status != null) {
        Text(status, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
    } else {
        PidaiIconAction(Icons.Filled.Download, "下载", onClick = { onDownload(entry) })
    }
}
