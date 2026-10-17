package com.xjtu.toolbox.agent

import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.stringValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 屁岱两端共用的一小撮文案与映射：工具行标签、回复下方的跳转建议、上下文阈值。 */

/** 一次工具调用的执行中文案。 */
fun agentToolLabel(name: String): String = when (name) {
    "get_calendar"          -> "查询时间与校历…"
    "get_schedule"          -> "查询课表…"
    "get_exam_schedule"     -> "查询考试安排…"
    "search_school_courses" -> "查询全校课程…"
    "get_empty_rooms"       -> "查询空教室…"
    "get_attendance"        -> "查询考勤记录…"
    "get_grades"            -> "查询成绩…"
    "get_fitness_score"     -> "查询体测成绩…"
    "get_card_info"         -> "查询校园卡信息…"
    "get_notifications"     -> "查询通知公告…"
    "search_yellow_page"    -> "查询校园黄页…"
    "web_search"            -> "联网搜索…"
    "web_fetch"             -> "阅读网页…"
    "set_alarm"             -> "打开系统闹钟…"
    "add_schedule_event"    -> "添加日程…"
    "get_library"           -> "查询图书馆…"
    "get_textbooks"         -> "查询日程教材…"
    "get_coupons"           -> "查询加餐券…"
    "get_lms"               -> "查询思源学堂…"
    "get_lms_activity"      -> "读取课程活动…"
    "list_zyxf"             -> "检索学辅资料…"
    "read_zyxf_file"        -> "读取学辅资料…"
    "find_faculty"          -> "查询教师…"
    "preference"            -> "记下偏好…"
    "app_setting"           -> "读取 / 修改设置…"
    "calculate"             -> "计算…"
    "app_guide"             -> "查看 App 指南…"
    else                    -> "调用工具 $name…"
}

/** 工具执行结束后的收尾文案：`查询课表…` → `已查询课表`。 */
fun agentCompleteToolLabel(running: String): String {
    val base = running.trimEnd('…', '.', '。', ' ')
    return if (base.startsWith("已")) base else "已$base"
}

/** 本轮涉及的工具 → 回复下方那排跳转按钮，`List<Pair<显示文字, [AppRoute.id]>>`。 */
fun agentNavSuggestions(calledTools: List<String>): List<Pair<String, String>> =
    calledTools.mapNotNull { toolName ->
        when (toolName) {
            "get_schedule", "get_exam_schedule", "add_schedule_event" -> "查看课表" to AppRoute.Schedule
            "get_calendar"                      -> "查看校历"   to AppRoute.SchoolCalendar
            "search_school_courses"             -> "全校课程"   to AppRoute.SchoolCourse
            "get_empty_rooms"                   -> "空闲教室"   to AppRoute.EmptyRoom
            "get_attendance"                    -> "查看考勤"   to AppRoute.Attendance
            "get_grades"                        -> "成绩查询"   to AppRoute.JwappScore
            "get_card_info"                     -> "校园卡"     to AppRoute.CampusCard
            "get_notifications"                 -> "通知公告"   to AppRoute.Notification
            "search_yellow_page"                -> "校园黄页"   to AppRoute.YellowPage
            "get_library"                       -> "图书馆"     to AppRoute.Library
            "get_textbooks"                     -> "日程教材"   to AppRoute.Schedule
            "get_coupons"                       -> "加餐券"     to AppRoute.Coupon
            "get_lms", "get_lms_activity"       -> "思源学堂"   to AppRoute.Lms()
            "app_setting"                       -> "设置"       to AppRoute.Settings
            else                                -> null
        }?.let { (label, route) -> label to route.id }
    }.distinctBy { it.second }

/** 「这一轮把上下文烧到了上限」的 token 阈值。 */
const val AGENT_CONTEXT_TOKEN_LIMIT = 800_000L

/** 上下文耗尽时那句文案。 */
const val AGENT_CONTEXT_EXHAUSTED_MESSAGE = "本对话上下文已达到上限，请新建对话后继续。"

/** 修复 LLM 历史的完整性：丢弃「带 `tool_calls` 却没有(完整) tool 回应」的 assistant 残体，以及孤儿 tool 消息。 */
fun agentSanitizeHistory(history: MutableList<JsonElement>): MutableList<JsonElement> {
    val items = (0 until history.size).map { history[it].jsonObject }
    val out = ArrayList<JsonObject>()
    var i = 0
    while (i < items.size) {
        val m = items[i]
        val role = m.get("role")?.stringValue
        val tcs = if (m.containsKey("tool_calls") && !m.get("tool_calls").isNull) m.arr("tool_calls") else null
        when {
            role == "assistant" && tcs != null -> {
                val ids = tcs.mapNotNull { it.jsonObject.get("id")?.stringValue }.toSet()
                val toolMsgs = ArrayList<JsonObject>()
                var j = i + 1
                while (j < items.size && items[j].get("role")?.stringValue == "tool") { toolMsgs.add(items[j]); j++ }
                val covered = toolMsgs.mapNotNull { it.get("tool_call_id")?.stringValue }.toSet()
                if (ids.isNotEmpty() && ids.all { it in covered }) {
                    out.add(m); toolMsgs.forEach { out.add(it) }
                } // 否则：丢弃该 assistant 及其不完整 tool 回应
                i = j
            }
            role == "tool" -> i++   // 孤儿 tool，丢弃
            else -> { out.add(m); i++ }
        }
    }
    return if (out.size != items.size) out.toMutableList<JsonElement>() else history
}
