package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.agent.ToolReply
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

/**
 * 屁岱的待办工具：`add_todo` / `get_todos` / `complete_todo`，本体是 [SelfTodoStore]。
 *
 * 只实现 `:shared:model` 的 [AgentTools]，不依赖 `:feature:agent`：端的入口（`AgentHost.tools`）用
 * [com.xjtu.toolbox.agent.CompositeAgentTools] 把它和主工具层并在一起。和 `add_schedule_event` 的分界写在描述里：
 * 占具体时段的才是日程。
 */
object SelfTodoAgentTools : AgentTools {

    val NAMES = listOf("add_todo", "get_todos", "complete_todo")

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
                        "add_todo",
                        "往待办里记一件事：有截止或没有具体时间、不占课表时段的事（交报告、买票、回邮件）。出现在「消息 → 待办」，到点推送提醒。" +
                            "要占一段具体时间的（开会、自习、约饭）用 add_schedule_event，别用这个。",
                        params(
                            "title" to prop("string", "要做的事，一句话。"),
                            "due" to prop("string", "截止：yyyy-MM-dd（只到天，当天 9 点提醒）或 yyyy-MM-dd HH:mm（到点提醒）。缺省：不设截止。"),
                            "remind" to prop("boolean", "要不要到点提醒。缺省：有截止就提醒。"),
                            "priority" to enumProp("优先级。缺省：none。", "none", "low", "medium", "high"),
                            "note" to prop("string", "备注。"),
                            required = listOf("title"),
                        ),
                    ),
                    tool(
                        "get_todos",
                        "用户自己记的、还没办完的待办（含屁岱代记的）。不含学校事务、作业截止、评教等，那些在各自工具里。",
                        params(),
                    ),
                    tool(
                        "complete_todo",
                        "把用户自己的一条待办标为已完成。title 模糊匹配；匹配到多条时返回候选，让用户选。",
                        params("title" to prop("string", "待办标题或其中的关键词。"), required = listOf("title")),
                    ),
                ),
            ),
        )
    }

    /** 纯本地，不打学校服务器，不需要限流间隔。 */
    override fun rateLimitKeyOf(toolName: String): String? = null

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.bool(key: String): Boolean? = str(key)?.let { it.equals("true", ignoreCase = true) }

    override suspend fun execute(name: String, argsJson: String): String {
        val args = runCatching { AppJson.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        return when (name) {
            "add_todo" -> addTodo(args.str("title"), args.str("due"), args.bool("remind"), args.str("priority"), args.str("note"))
            "get_todos" -> getTodos()
            "complete_todo" -> completeTodo(args.str("title"))
            else -> ToolReply.notFound("tool", name)
        }
    }

    internal fun addTodo(title: String?, due: String?, remind: Boolean?, priority: String?, note: String?): String {
        val name = title?.trim()?.takeIf { it.isNotBlank() } ?: return ToolReply.missing("title")
        val raw = due?.trim()?.takeIf { it.isNotBlank() }
        val parsed = raw?.let {
            SelfTodoRules.parseDue(it) ?: return ToolReply.badFormat("due", "yyyy-MM-dd 或 yyyy-MM-dd HH:mm")
        }
        val level = when (priority?.trim()?.lowercase()) {
            null, "", "none" -> SelfTodoRules.PRIORITY_NONE
            "low" -> SelfTodoRules.PRIORITY_LOW
            "medium" -> SelfTodoRules.PRIORITY_MEDIUM
            "high" -> SelfTodoRules.PRIORITY_HIGH
            else -> return ToolReply.badValue("priority", "none/low/medium/high")
        }
        val todo = SelfTodoStore.create(
            title = name,
            content = note.orEmpty(),
            dueDate = parsed?.first,
            priority = level,
            source = "agent",
            allDay = parsed?.second == true,
            remind = remind ?: (parsed != null),
        ) ?: return ToolReply.failed("add_todo", "empty_title")
        val remindAt = SelfTodoRules.nextReminderAt(todo, Clock.System.now().toEpochMilliseconds())
        return "ok: added; title: ${todo.title}; due: ${raw ?: "none"}; " +
            "remind_at: ${remindAt?.let { SelfTodoRules.instantText(it) } ?: "none"}"
    }

    internal fun getTodos(): String {
        val open = SelfTodoStore.open()
        if (open.isEmpty()) return ToolReply.empty("todos")
        return buildString {
            append("待办（${open.size} 条未办）\n")
            open.forEach { t ->
                append("- ${t.title}")
                SelfTodoRules.dueText(t)?.let { append("｜截止 $it") }
                when (t.priority) {
                    SelfTodoRules.PRIORITY_LOW -> append("｜优先级 低")
                    SelfTodoRules.PRIORITY_MEDIUM -> append("｜优先级 中")
                    SelfTodoRules.PRIORITY_HIGH -> append("｜优先级 高")
                }
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