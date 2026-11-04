package com.xjtu.toolbox.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.agent.InsightSpec
import com.xjtu.toolbox.agent.PidaiCard
import com.xjtu.toolbox.campusmap.CampusMaps
import com.xjtu.toolbox.inbox.SelfTodoRules
import com.xjtu.toolbox.inbox.SelfTodoStore
import com.xjtu.toolbox.ui.components.AppInsetColor
import com.xjtu.toolbox.ui.components.CampusMapView
import com.xjtu.toolbox.ui.components.MapMark
import com.xjtu.toolbox.ui.components.PidaiChip
import com.xjtu.toolbox.ui.components.PidaiChipRow
import com.xjtu.toolbox.ui.components.PidaiInputRow
import com.xjtu.toolbox.ui.components.PidaiInsightCard
import com.xjtu.toolbox.ui.components.rememberInsight
import com.xjtu.toolbox.util.safeStringOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 「一句话排今天」：照今天的课、考试、截止和用户一句话，排出从现在到睡前的安排和路线。 */
private val DAY_PLAN = InsightSpec(
    id = "day_plan_v3",
    tools = setOf("get_schedule", "get_exam_schedule", "campus_map", "my_location", "get_empty_rooms", "get_library", "app_guide", "get_card_info"),
    ttlMs = 2 * 60 * 60 * 1000L,
    thinking = true,
    busyHint = "在排…",
    instruction = """
任务：整理用户明确要求的目标日期日程，保留原意、事项、时间、地点与约束，不自行扩展活动。
- 固定安排以给定快照或工具结果为准；目标不是今天时，查询目标日期的课表和考试，不套用今日快照。
- blocks 只包含已确认的课程、考试以及用户明确要求的事项。空闲时段保持空闲，不依据学籍、临近截止或生活惯例填满全天。
- 用户未要求学习活动时，不添加学习活动。建议与已确认安排分开，只写进 tips；建议未经采纳不进入 blocks。
- 执行所必需的时间或地点不明确时，在 tips 中说明待补充信息，不编造确定值。截止时间不等同于任务开始时间或持续时长。
- 仅在用户要求地点推荐或路线安排时查询位置；画像校区和上课地点不能视为当前位置。无可靠当前位置时保持未知。
- 确需跨地点移动时依据地图结果评估可达性，把时间冲突或尚未确认的路程写入 tips，不挪动用户指定的安排。
- 目标是今天时省略已结束事项；没有符合条件的安排时 blocks 为空，summary 如实说明。
- 每块标 type：要占一段时间、到点去做的（课、考试、自习、运动、约饭、开会）为 event；只需在某时刻前办完、不占整段时间的（交材料、缴费、取快递、回消息）为 todo，start 写该办完的时刻，end 留空。
只输出：
{"summary":"不超过 30 字，这一天的主线","blocks":[{"start":"14:00","end":"15:50","title":"不超过 12 字","place":"楼名/食堂/图书馆区域，没有就空串","kind":"class|exam|study|meal|move|sport|rest|other","type":"event|todo","note":"不超过 20 字，可空"}],"tips":["最多两条，不超过 25 字"]}
    """.trimIndent(),
    card = PidaiCard.DAY_PLAN,
)

/** 交给小岱的今日快照（本地已有的数据，小岱就不必再查课表）。 */
internal fun dayPlanSnapshot(today: LocalDate, entries: List<String>, upcoming: List<UpcomingItem>): String = buildString {
    append("日期：$today\n今天的固定安排（开始–结束｜事项｜地点）：\n")
    if (entries.isEmpty()) append("- 无\n") else entries.forEach { append("- $it\n") }
    if (upcoming.isNotEmpty()) {
        append("接下来几天：\n")
        upcoming.take(8).forEach { append("- ${it.title}｜${it.subtitle}\n") }
    }
}

/** 排日程卡里能加进去的一项（课、考试、赶路不算）。[todo] 由小岱判断：只需到点前办完的进待办，其余进日程。 */
data class PlanItem(val title: String, val startMinute: Int, val endMinute: Int, val place: String, val note: String, val todo: Boolean = false)

private val HHMM = Regex("""(\d{1,2}):(\d{2})""")

private fun minuteOf(text: String?): Int? =
    HHMM.matchEntire(text?.trim().orEmpty())?.destructured?.let { (h, m) -> (h.toInt() * 60 + m.toInt()).takeIf { it in 0 until 24 * 60 } }

/** 小岱给的一块安排能不能加进日程；不能就 null。没写结束时间按一小时算。 */
private fun planItemOf(block: JsonObject): PlanItem? {
    if (block["kind"].safeStringOrNull() in setOf("class", "exam", "move")) return null
    val title = block["title"].safeStringOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val start = minuteOf(block["start"].safeStringOrNull()) ?: return null
    val end = minuteOf(block["end"].safeStringOrNull())?.takeIf { it > start } ?: (start + 60).coerceAtMost(24 * 60 - 1)
    return PlanItem(
        title, start, end,
        place = block["place"].safeStringOrNull().orEmpty(),
        note = block["note"].safeStringOrNull().orEmpty(),
        todo = block["type"].safeStringOrNull() == "todo",
    )
}

/** 待办按「该办完的时刻」建，提醒由用户在待办里自己开。 */
private fun addTodo(date: LocalDate, item: PlanItem): Boolean {
    val hhmm = "${(item.startMinute / 60).toString().padStart(2, '0')}:${(item.startMinute % 60).toString().padStart(2, '0')}"
    val due = SelfTodoRules.parseDue("$date $hhmm") ?: return false
    return SelfTodoStore.create(
        title = item.title,
        content = listOf(item.place, item.note).filter { it.isNotBlank() }.joinToString(" · "),
        dueDate = due.first,
        source = "agent",
        allDay = due.second,
    ) != null
}

@Composable
internal fun DayPlanCard(
    snapshot: String,
    today: LocalDate,
    modifier: Modifier = Modifier,
    /** 把安排写进日程；null = 这一端不能写（只陈列）。 */
    onAdd: ((LocalDate, List<PlanItem>) -> Unit)? = null,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable(today.toString()) { mutableStateOf(today.toString()) }
    var asked by rememberSaveable(today.toString()) { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val targetDate = remember(target) { LocalDate.parse(target) }
    val state = rememberInsight(
        DAY_PLAN,
        asked?.let { said ->
            buildString {
                append("目标日期：$targetDate ${dayLabel(targetDate, today)}\n")
                append("用户说：${said.ifBlank { "（仅整理已确认的固定安排）" }}\n")
                if (targetDate != today) append("（那天的课和考试请自己查；下面是今天的情况，供参考）\n")
                append(snapshot)
            }
        },
    )
    fun submit() {
        val text = draft.trim()
        // 同一句话、同一天再点一次 = 重排（不然只会拿回缓存）
        if (text == asked) state.refresh() else asked = text
    }
    val choices = remember(today) { dayChoices(today) }
    PidaiInsightCard(
        title = "小岱排日程",
        state = state,
        modifier = modifier,
        subtitle = "选一天、说一句，按你的要求整理",
        askPrompt = { "你排的${dayLabel(targetDate, today)}这个安排，再帮我调一调。" },
        controls = {
            PidaiChipRow {
                choices.forEach { (label, date) ->
                    PidaiChip(label, selected = date == targetDate, onClick = { target = date.toString() })
                }
                PidaiChip(
                    if (targetDate in choices.map { it.second }) "选日期" else "$targetDate",
                    icon = Icons.Filled.CalendarMonth,
                    selected = targetDate !in choices.map { it.second },
                    onClick = { picking = true },
                )
            }
            PidaiInputRow(
                value = draft,
                onValueChange = { draft = it },
                placeholder = if (targetDate == today) "下午复习高数，晚上想打球…" else "那天想做什么？",
                onSubmit = { submit() },
                enabled = !state.loading,
            )
            PidaiChipRow {
                DAY_PLAN_PRESETS.forEach { p ->
                    PidaiChip(p, onClick = { draft = if (draft.isBlank()) p else "$draft，$p" })
                }
            }
        },
    ) { json ->
        json["summary"].safeStringOrNull()?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
        }
        val blocks = (json["blocks"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        // 这次结果里已经加进去的块（按块的下标记），重排后换一批
        var added by remember(json) { mutableStateOf(emptySet<Int>()) }
        // 日程要这一端能写日程；待办哪端都能建
        fun addable(item: PlanItem?) = item != null && (item.todo || onAdd != null)
        fun add(indexed: List<Pair<Int, PlanItem>>) {
            val (todos, events) = indexed.partition { it.second.todo }
            val ok = todos.filter { addTodo(targetDate, it.second) } + events
            if (events.isNotEmpty()) onAdd?.invoke(targetDate, events.map { it.second })
            added = added + ok.map { it.first }
        }
        val items = blocks.map(::planItemOf)
        blocks.forEachIndexed { i, block ->
            val item = items[i]
            PlanRow(block, todo = item?.todo == true, added = i in added,
                onAdd = if (addable(item)) { { add(listOf(i to item!!)) } } else null)
        }
        val pending = items.withIndex().mapNotNull { (i, it) -> it?.takeIf { addable(it) && i !in added }?.let { i to it } }
        if (pending.size > 1) {
            PidaiChipRow {
                PidaiChip("全部加入（${pending.size}）", icon = Icons.Filled.EventAvailable, onClick = { add(pending) })
            }
        }
        DayPlanMap(blocks)
        (json["tips"] as? JsonArray).orEmpty().mapNotNull { it.safeStringOrNull() }.filter { it.isNotBlank() }.forEach {
            Text("· $it", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
    com.xjtu.toolbox.ui.components.AppDatePickerDialog(
        show = picking,
        title = "排哪一天",
        date = targetDate,
        minDate = today,
        maxDate = today.plus(60, DateTimeUnit.DAY),
        onDismiss = { picking = false },
        onConfirm = { target = it.toString(); picking = false },
    )
}

/** 今天、明天、后天，加上最近的周六周日（已在前三天里的不重复）。 */
private fun dayChoices(today: LocalDate): List<Pair<String, LocalDate>> {
    val base = listOf("今天" to today, "明天" to today.plus(1, DateTimeUnit.DAY), "后天" to today.plus(2, DateTimeUnit.DAY))
    val weekend = (1..7).map { today.plus(it, DateTimeUnit.DAY) }
        .filter { it.dayOfWeek.isoDayNumber >= 6 && base.none { b -> b.second == it } }
        .take(2)
        .map { (if (it.dayOfWeek.isoDayNumber == 6) "周六" else "周日") to it }
    return base + weekend
}

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "（今天）"
    today.plus(1, DateTimeUnit.DAY) -> "（明天）"
    today.plus(2, DateTimeUnit.DAY) -> "（后天）"
    else -> "（周${"一二三四五六日"[date.dayOfWeek.isoDayNumber - 1]}）"
}

@Composable
private fun PlanRow(block: JsonObject, todo: Boolean, added: Boolean, onAdd: (() -> Unit)?) {
    val start = block["start"].safeStringOrNull().orEmpty()
    val title = block["title"].safeStringOrNull().orEmpty().ifBlank { return }
    val kind = block["kind"].safeStringOrNull().orEmpty()
    val fixed = kind == "class" || kind == "exam"
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (fixed) MiuixTheme.colorScheme.primary.copy(alpha = 0.08f) else AppInsetColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(52.dp)) {
            Text(start, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold,
                color = if (fixed) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
            block["end"].safeStringOrNull()?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
            val sub = listOfNotNull(block["place"].safeStringOrNull(), block["note"].safeStringOrNull()).filter { it.isNotBlank() }
            if (sub.isNotEmpty()) {
                Text(sub.joinToString(" · "), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        // 课和考试本来就在课表里；其余安排按小岱标的类型进日程或待办
        if (onAdd != null) {
            PidaiChip(
                if (added) "已加入" else if (todo) "加入待办" else "加入日程",
                icon = if (added) Icons.Filled.Check else if (todo) Icons.Filled.AddTask else Icons.Filled.EventAvailable,
                enabled = !added,
                onClick = onAdd,
            )
        }
    }
}

/** 今天要去的地方按顺序连成路线；地图上找不到的地点列在下面，不悄悄丢掉。 */
@Composable
private fun DayPlanMap(blocks: List<JsonObject>) {
    val places = remember(blocks) {
        blocks.mapNotNull { b -> b["place"].safeStringOrNull()?.trim()?.takeIf { it.isNotBlank() } }.distinct()
    }
    val resolved = remember(places) { places.associateWith { CampusMaps.find(it) } }
    val stops = remember(blocks, resolved) {
        blocks.mapNotNull { b -> b["place"].safeStringOrNull()?.trim()?.let { resolved[it] } }
            .fold(mutableListOf<Pair<com.xjtu.toolbox.campusmap.CampusMapData, com.xjtu.toolbox.campusmap.CampusPlace>>()) { acc, hit ->
                if (acc.lastOrNull()?.second !== hit.second) acc += hit
                acc
            }
    }
    val missing = resolved.filterValues { it == null }.keys
    val map = stops.groupingBy { it.first }.eachCount().maxByOrNull { it.value }?.key
    if (map != null) {
        val marks = stops.filter { it.first === map }.mapIndexed { i, (_, p) -> MapMark(p, p.name, order = i + 1) }
        CampusMapView(map, marks, route = true, height = 240.dp)
    }
    if (missing.isNotEmpty()) {
        Text(
            "地图上暂时没有：${missing.joinToString("、")}",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 一键填进输入框的常用说法。 */
private val DAY_PLAN_PRESETS = listOf("复习备考", "赶作业", "去图书馆自习", "运动一下", "早点睡", "约朋友吃饭")
