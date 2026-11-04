package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.AppJson
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `propose_plan`：把对话或材料里能排进日程、待办的事列成一张卡，用户逐条点「加入」才写入。
 * 由 [AgentRunner] 直接出卡（不经 present_result）；写入复用 add_schedule_event / add_todo，
 * 冲突检测、学期换算都和模型自己调用时一致。
 */
internal object AgentPlanCard {
    const val TOOL = "propose_plan"
    private const val MAX_ITEMS = 8
    private val HHMM = Regex("""([01]?\d|2[0-3]):[0-5]\d""")

    fun definition(): JsonObject = agentTool(TOOL,
        "把可以记下的日程、待办列成卡片，用户逐条点「加入」才写入。适合从材料或对话里理出来、用户还没明确让记的事，代替文字询问「要不要帮你加」。" +
            "用户已经明确要你记的，直接 add_schedule_event / add_todo。返回后卡片已展示，正文不必逐条复述。",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("items") {
                    put("type", "array")
                    put("description", "最多 $MAX_ITEMS 条，按时间排。")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            put("title", agentStrProp("事项，不超过 20 字。"))
                            put("type", agentEnumProp("event 占一段时间、到点去做；todo 只需在某时刻前办完。", "event", "todo"))
                            put("date", agentStrProp("yyyy-MM-dd。"))
                            put("start", agentStrProp("HH:mm。event 必填；todo 为截止时刻，可空（当天截止）。"))
                            put("end", agentStrProp("HH:mm。event 缺省：开始后 1 小时。"))
                            put("place", agentStrProp("地点，没有就不填。"))
                            put("note", agentStrProp("备注，不超过 40 字。"))
                        }
                        putJsonArray("required") { add("title"); add("type"); add("date") }
                    }
                }
                put("caption", agentStrProp("卡片上方一句说明，可空。"))
            }
            putJsonArray("required") { add("items") }
        })

    /** 参数不合规时抛出，错误原样回给模型重试。 */
    fun parse(args: String): PlanWidget {
        val root = AppJson.parseToJsonElement(args.ifBlank { "{}" }) as? JsonObject ?: error("参数须为对象")
        val rows = root["items"] as? JsonArray ?: error("缺少 items")
        require(rows.isNotEmpty()) { "items 不能为空" }
        require(rows.size <= MAX_ITEMS) { "items 最多 $MAX_ITEMS 条" }
        val items = rows.mapIndexed { i, raw ->
            val o = raw as? JsonObject ?: error("items[${i + 1}] 须为对象")
            val title = o.argStr("title")?.trim()?.takeIf { it.isNotEmpty() } ?: error("items[${i + 1}].title 不能为空")
            val todo = when (o.argStr("type")) { "todo" -> true; "event" -> false; else -> error("items[${i + 1}].type 须为 event 或 todo") }
            val date = o.argStr("date")?.trim().orEmpty()
            require(runCatching { LocalDate.parse(date) }.isSuccess) { "items[${i + 1}].date 须为 yyyy-MM-dd" }
            fun time(key: String): String = o.argStr(key)?.trim().orEmpty().also {
                require(it.isEmpty() || HHMM.matches(it)) { "items[${i + 1}].$key 须为 HH:mm" }
            }
            val start = time("start")
            val end = time("end")
            require(todo || start.isNotEmpty()) { "items[${i + 1}] 是 event，start 必填" }
            require(end.isEmpty() || minutes(end) > minutes(start)) { "items[${i + 1}].end 须晚于 start" }
            PlanProposal(title.take(30), date, start, if (todo) "" else end,
                o.argStr("place")?.trim().orEmpty(), o.argStr("note")?.trim().orEmpty().take(60), todo)
        }
        return PlanWidget(items, root.argStr("caption")?.trim().orEmpty().take(80))
    }

    fun receipt(widget: PlanWidget): String = ToolReply.CARD_SHOWN +
        "\ncard_display: plan; rows=${widget.items.size}; user_taps_to_add; not_added_yet"

    /** 卡片上点「加入」时转给哪个工具、带什么参数。 */
    fun addCall(item: PlanProposal, force: Boolean = false): Pair<String, String> = if (item.todo) {
        "add_todo" to buildJsonObject {
            put("title", item.title)
            put("due", if (item.start.isEmpty()) item.date else "${item.date} ${item.start}")
            if (item.note.isNotEmpty() || item.place.isNotEmpty()) put("note", listOf(item.place, item.note).filter { it.isNotEmpty() }.joinToString(" · "))
        }.toString()
    } else {
        "add_schedule_event" to buildJsonObject {
            put("title", item.title)
            put("date", item.date)
            put("start", item.start)
            if (item.end.isNotEmpty()) put("end", item.end)
            if (item.place.isNotEmpty()) put("location", item.place)
            if (item.note.isNotEmpty()) put("note", item.note)
            if (force) put("force", true)
        }.toString()
    }

    private fun minutes(hhmm: String): Int = hhmm.substringBefore(':').toInt() * 60 + hhmm.substringAfter(':').toInt()
}
