package com.xjtu.toolbox.inbox

import androidx.compose.ui.graphics.toArgb
import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.agent.ToolReply
import com.xjtu.toolbox.agent.ToolShow
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

    private val show = ToolShow.PARAM to prop("boolean", ToolShow.DESCRIPTION)

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
                        "往待办里记一件事：有截止或没有具体时间、不占课表时段的事（交报告、买票、回邮件）。出现在「消息 → 待办」，临近截止会推送一条提醒。" +
                            "要占一段具体时间的（开会、自习、约饭）用 add_schedule_event，别用这个。用户没说截止就不设，不必追问。",
                        params(
                            "title" to prop("string", "要做的事，一句话。"),
                            "due" to prop("string", "截止：yyyy-MM-dd（只到天，记成当天 23:59）或 yyyy-MM-dd HH:mm。缺省：不设截止。"),
                            "note" to prop("string", "备注。"),
                            "priority" to enumProp("重要程度，只在列表标题旁标一下，不改变排序。缺省：none。", "none", "low", "medium", "high"),
                            "color" to prop("string", "这条待办在列表里的图标色，#RRGGBB。缺省：默认绿。"),
                            show,
                            required = listOf("title"),
                        ),
                    ),
                    tool(
                        "get_todos",
                        "用户自己记的、还没办完的待办（含屁岱代记的）。不含学校事务、作业截止、评教等，那些在各自工具里。",
                        params(show),
                    ),
                    tool(
                        "complete_todo",
                        "把用户自己的一条待办标为已完成。title 模糊匹配；匹配到多条时返回候选，让用户选。",
                        params("title" to prop("string", "待办标题或其中的关键词。"), show, required = listOf("title")),
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
        return when (name) {
            "add_todo" -> addTodo(args.str("title"), args.str("due"), args.str("priority"), args.str("note"), args.str("color"))
            "get_todos" -> getTodos()
            "complete_todo" -> completeTodo(args.str("title"))
            else -> ToolReply.notFound("tool", name)
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
        val reminder = if (dueText != null) "截止前两天起推一次" else "无（没有截止）"
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