package com.xjtu.toolbox.inbox

import androidx.compose.ui.graphics.toArgb
import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.agent.ToolReply
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 小岱的待办工具 `todo`（add / list / done），本体是 [SelfTodoStore]。
 * 合并前的 `add_todo` / `get_todos` / `complete_todo` 仍可执行：计划卡片的「加入」走 `add_todo`。
 *
 * 只实现 `:shared:model` 的 [AgentTools]，不依赖 `:feature:agent`：端的入口（`AgentHost.tools`）用
 * [com.xjtu.toolbox.agent.CompositeAgentTools] 把它和主工具层并在一起。和 `add_schedule_event` 的分界写在描述里：
 * 占具体时段的才是日程。
 */
object SelfTodoAgentTools : AgentTools {

    override val legacyNames = setOf("add_todo", "get_todos", "complete_todo")

    private fun prop(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun enumProp(description: String, vararg values: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
    }

    private fun params(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

    private fun tool(name: String, description: String, params: JsonObject) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", params)
        })
    }

    override val toolDefinitions: String by lazy {
        AppJson.encodeToString(
            JsonArray.serializer(),
            JsonArray(
                listOf(
                    tool(
                        "todo",
                        "用户自己的待办（「消息 → 待办」，临近截止推送提醒）：add 记一条，list 列出未完成的，done 标完成。不含作业截止等学校事务。",
                        params(
                            "action" to enumProp("add / list / done。", "add", "list", "done"),
                            "title" to prop("string", "add：要做的事；done：标题关键词，多条匹配时返回候选。"),
                            "due" to prop("string", "add 的截止：yyyy-MM-dd（当天 23:59）或 yyyy-MM-dd HH:mm。缺省：无截止。"),
                            "note" to prop("string", "add 的备注。"),
                            "priority" to enumProp("add 的重要程度，只做标注。缺省：none。", "none", "low", "medium", "high"),
                            "color" to prop("string", "add 的图标色 #RRGGBB。缺省：绿。"),
                            required = listOf("action"),
                        ),
                    ),
                ),
            ),
        )
    }

    /** 纯本地，不打学校服务器，不需要限流间隔。 */
    override fun rateLimitKeyOf(toolName: String): String? = null

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    override suspend fun execute(name: String, argsJson: String): String {
        val args = runCatching { AppJson.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val action = if (name == "todo") args.str("action") else name
        return when (action) {
            "add", "add_todo" -> addTodo(args.str("title"), args.str("due"), args.str("priority"), args.str("note"), args.str("color"))
            "list", "get_todos" -> getTodos()
            "done", "complete_todo" -> completeTodo(args.str("title"))
            null -> ToolReply.missing("action")
            else -> if (name == "todo") ToolReply.badValue("action", "add/list/done") else ToolReply.notFound("tool", name)
        }
    }

    internal fun addTodo(title: String?, due: String?, priority: String?, note: String?, color: String?): String {
        val name = title?.trim()?.takeIf { it.isNotBlank() } ?: return ToolReply.missing("title")
        val raw = due?.trim()?.takeIf { it.isNotBlank() }
        val parsed = raw?.let {
            SelfTodoRules.parseDue(it) ?: return ToolReply.badFormat("due", "yyyy-MM-dd 或 yyyy-MM-dd HH:mm")
        }
        // 重要程度落成「收纳里这条待办」的标注（学校推来的待办也能标），
        // 不是 SelfTodo.priority —— 那个滴答字段界面不读，混用会出现「标了却不显示」
        val level = when (priority?.trim()?.lowercase()) {
            null, "", "none" -> InboxRules.IMPORTANCE_NONE
            "low" -> InboxRules.IMPORTANCE_LOW
            "medium" -> InboxRules.IMPORTANCE_MEDIUM
            "high" -> InboxRules.IMPORTANCE_HIGH
            else -> return ToolReply.badValue("priority", "none/low/medium/high")
        }
        val argb = color?.trim()?.takeIf { it.isNotBlank() }?.let {
            CourseColors.parseHex(it)?.toArgb() ?: return ToolReply.badFormat("color", "#RRGGBB")
        }
        val todo = SelfTodoStore.create(
            title = name,
            content = note.orEmpty(),
            dueDate = parsed?.first,
            source = "agent",
            allDay = parsed?.second == true,
            colorArgb = argb,
        ) ?: return ToolReply.failed("add_todo", "empty_title")
        if (level != InboxRules.IMPORTANCE_NONE) InboxStore.setImportance(SelfTodoRules.inboxId(todo.id), level)
        val dueText = SelfTodoRules.dueText(todo)
        // 如实说清提醒会落在什么时候（0–7 点会顺延），免得用户以为半夜也会响
        val reminder = if (dueText != null) "截止前 48 小时推一次（撞上 0–7 点顺延到 7 点）" else "无（没有截止）"
        return "ok: added; title: ${todo.title}; due: ${dueText ?: "none"}; reminder: $reminder"
    }

    internal fun getTodos(): String {
        val open = SelfTodoStore.open()
        if (open.isEmpty()) return ToolReply.empty("todos")
        // 重要程度存在收纳那边（按条目 id），不是任务字段
        val data = InboxStore.load()
        return buildString {
            append("待办（${open.size} 条未办，按截止时间排）\n")
            open.forEach { t ->
                append("- ${t.title}")
                SelfTodoRules.dueText(t)?.let { append("｜截止 $it") }
                InboxRules.importanceLabel(InboxRules.importanceOf(data, SelfTodoRules.inboxId(t.id)))
                    ?.let { append("｜$it") }
                if (t.content.isNotBlank()) append("｜备注 ${t.content.take(40)}")
                append('\n')
            }
        }
    }

    internal fun completeTodo(title: String?): String {
        val key = title?.trim()?.takeIf { it.isNotBlank() } ?: return ToolReply.missing("title")
        val open = SelfTodoStore.open()
        val hits = open.filter { it.title.contains(key, ignoreCase = true) }
        return when {
            hits.isEmpty() -> ToolReply.notFound("todo", key, open.take(8).map { it.title })
            hits.size > 1 -> "error: ambiguous_match; param: title; candidates: ${hits.take(8).joinToString("；") { it.title }}"
            else -> {
                SelfTodoStore.complete(hits.single().id)
                "ok: completed; title: ${hits.single().title}"
            }
        }
    }
}