package com.xjtu.toolbox.lms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.agent.InsightSpec
import com.xjtu.toolbox.agent.PidaiCard
import com.xjtu.toolbox.inbox.SelfTodoRules
import com.xjtu.toolbox.inbox.SelfTodoStore
import com.xjtu.toolbox.ui.components.AppInsetColor
import com.xjtu.toolbox.ui.components.PidaiChip
import com.xjtu.toolbox.ui.components.PidaiInsightCard
import com.xjtu.toolbox.ui.components.rememberInsight
import com.xjtu.toolbox.util.CampusTimeZone
import com.xjtu.toolbox.util.safeStringOrNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 思源课程页顶上的「待办」：本学期没交的作业、没做的测试和最近一周的课程公告，屁岱排先后。 */
private val LMS_TODOS = InsightSpec(
    id = "lms_todos_v2",
    ttlMs = 2 * 60 * 60 * 1000L,
    busyHint = "在排先后…",
    instruction = """
任务：把用户本学期思源学堂里要处理的事排个先后：没交的作业、没做的测试，以及最近的课程公告里要他做事的（改时间、交材料、考试安排）。
- 截止近的排前面；离截止不到 24 小时的标出来。截止时间是北京时间。
- 公告只挑要他动手或要知道的，寒暄类不要。
- 建议具体到这一项，不说空话；不知道题量就别估时间。
只输出：
{"summary":"不超过 30 字","items":[{"course":"课程名","title":"原标题","kind":"作业|测试|公告","due":"yyyy-MM-dd HH:mm 或 null","priority":3,"advice":"不超过 25 字","course_id":0}]}
priority：3 先做，2 这几天做，1 知道就行。最多 8 项；什么都没有就 items 为空。
    """.trimIndent(),
    card = PidaiCard.LMS,
)

/** 只整理当前学期课程；历史课程和查询失败单独标明，避免误判没有待办。 */
private suspend fun gatherTodos(source: LmsSource, courses: List<LmsCourseSummary>, cache: LmsPageCache): String? {
    val now = Clock.System.now()
    val today = now.toLocalDateTime(CampusTimeZone).date
    val latest = currentLmsSemester(today)
    val current = courses.filter { it.semesterLabel == latest }
    if (current.isEmpty()) {
        val available = courses.map { it.semesterLabel }.distinct().joinToString("、").ifBlank { "暂无课程" }
        return "当前日期：$today；当前学期：$latest。\n思源返回的课程中没有当前学期课程；可用学期：$available。\n这里只能说明思源尚未提供当前学期课程，不能据此推断用户没有课程或作业；历史学期不当作本学期。"
    }
    val weekAgo = now.minus(kotlin.time.Duration.parse("7d"))
    val gate = Semaphore(3)
    fun clock(i: Instant) = i.toLocalDateTime(CampusTimeZone).let { "${it.date} ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" }
    val results = coroutineScope {
        current.map { c ->
            async {
                gate.withPermit {
                    // 课程列表已经扫过活动、查过作业交没交，这里直接用；列表接口不给交没交，作业只认查过的
                    val activityResult = cache.activities[c.id]?.let { Result.success(it) } ?: runCatching { source.courseActivities(c.id) }
                    if (activityResult.exceptionOrNull() is kotlinx.coroutines.CancellationException) throw activityResult.exceptionOrNull()!!
                    val pending = cache.pendingOf(c.id).map { it.id }.toSet()
                    val tasks = activityResult.getOrDefault(emptyList())
                        .filter { !it.isClosed && (if (it.type == LmsActivityType.HOMEWORK) it.id in pending else it.type == LmsActivityType.EXAM && it.userSubmitCount == 0) }
                        .mapNotNull { a ->
                            val raw = (if (a.type == LmsActivityType.HOMEWORK) a.deadline ?: a.endTime else a.endTime) ?: return@mapNotNull null
                            val due = runCatching { Instant.parse(raw) }.getOrNull()?.takeIf { it > now } ?: return@mapNotNull null
                            val kind = if (a.type == LmsActivityType.EXAM) "测试" else "作业"
                            due to "- ${c.name}｜$kind｜${a.title}｜截止 ${clock(due)}｜course_id ${c.id}"
                        }
                    val bulletinResult = runCatching { source.courseBulletins(c.id) }
                    if (bulletinResult.exceptionOrNull() is kotlinx.coroutines.CancellationException) throw bulletinResult.exceptionOrNull()!!
                    val notices = bulletinResult.getOrDefault(emptyList())
                        .mapNotNull { b ->
                            val at = runCatching { Instant.parse(b.createdAt) }.getOrNull()?.takeIf { it > weekAgo } ?: return@mapNotNull null
                            val body = b.content.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(150)
                            at to "- ${c.name}｜公告｜${b.title}｜发布 ${clock(at)}｜$body｜course_id ${c.id}"
                        }
                    (tasks + notices) to c.name.takeIf { activityResult.isFailure || bulletinResult.isFailure }
                }
            }
        }.awaitAll()
    }
    val rows = results.flatMap { it.first }
    val missing = results.mapNotNull { it.second }
    val note = if (missing.isEmpty()) "" else "\n未查全的课程：${missing.joinToString("、")}。查询失败不能当作没有待办。"
    if (rows.isEmpty()) return "当前学期（$latest）已查到的思源课程暂无未交作业、测试或近一周公告。$note"
    return "学期：$latest\n" + rows.sortedBy { it.first }.joinToString("\n") { it.second } + note
}

internal fun currentLmsSemester(date: kotlinx.datetime.LocalDate): String = when (date.month.ordinal + 1) {
    1 -> "${date.year - 1}-${date.year} 秋"
    in 2..7 -> "${date.year - 1}-${date.year} 春"
    else -> "${date.year}-${date.year + 1} 秋"
}

@Composable
internal fun LmsTodoCard(
    source: LmsSource,
    courses: List<LmsCourseSummary>,
    cache: LmsPageCache,
    onOpenCourse: (LmsCourseSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf<String?>(null) }
    var gathering by remember { mutableStateOf(false) }
    // 等课程列表那一遍扫完再汇总，不重复请求
    val scanned = cache.openHomework != null
    LaunchedEffect(courses, scanned) {
        if (courses.isEmpty() || !scanned) return@LaunchedEffect
        gathering = true
        input = gatherTodos(source, courses, cache)
        gathering = false
    }
    val state = rememberInsight(LMS_TODOS, input)
    PidaiInsightCard(
        title = "待办",
        state = state,
        modifier = modifier,
        subtitle = if (gathering || !scanned) "在汇总本学期没交的作业、测试和公告…" else "本学期没交的作业、测试和新公告",
        askPrompt = { "思源这些作业，帮我具体规划一下这周怎么做完？" },
    ) { json ->
        json["summary"].safeStringOrNull()?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        (json["items"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }.forEach { item ->
            TodoRow(item, courses, onOpenCourse)
        }
    }
}

@Composable
private fun TodoRow(item: JsonObject, courses: List<LmsCourseSummary>, onOpenCourse: (LmsCourseSummary) -> Unit) {
    val title = item["title"].safeStringOrNull().orEmpty().ifBlank { return }
    val courseName = item["course"].safeStringOrNull().orEmpty()
    val course = courses.firstOrNull { it.id.toString() == item["course_id"].safeStringOrNull() } ?: courses.firstOrNull { it.name == courseName }
    val due = item["due"].safeStringOrNull()?.takeIf { it.isNotBlank() && it != "null" }
    val kind = item["kind"].safeStringOrNull().orEmpty()
    // 公告没有「交」这回事，不给记待办（有截止的除外）
    val done = kind == "公告" && item["due"].safeStringOrNull().let { it.isNullOrBlank() || it == "null" }
    val priority = item["priority"].safeStringOrNull()?.toIntOrNull() ?: 1
    val accent = course?.let { lmsAccent(it) } ?: MiuixTheme.colorScheme.primary
    var added by remember(title) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AppInsetColor)
            .clickable(enabled = course != null) { course?.let(onOpenCourse) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Text(
                courseName, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 6.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(kind.takeIf { it.isNotBlank() }, when { priority >= 3 -> "先做"; priority == 2 -> "这几天"; else -> null })
                    .joinToString(" · "),
                style = MiuixTheme.textStyles.footnote2,
                fontWeight = FontWeight.Bold,
                color = if (priority >= 3) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
            )
        }
        Text(title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item["advice"].safeStringOrNull()?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                due?.let { "截止 $it" }.orEmpty(),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            val parsed = due?.let { SelfTodoRules.parseDue(it) }
            if (parsed != null && !done) {
                PidaiChip(
                    if (added) "已记待办" else "记待办",
                    icon = if (added) Icons.Filled.Check else Icons.AutoMirrored.Filled.EventNote,
                    enabled = !added,
                    onClick = {
                        added = SelfTodoStore.create(
                            title = "[$courseName] $title", dueDate = parsed.first, source = "agent",
                            allDay = parsed.second, remind = true,
                        ) != null
                    },
                )
            }
        }
    }
}
