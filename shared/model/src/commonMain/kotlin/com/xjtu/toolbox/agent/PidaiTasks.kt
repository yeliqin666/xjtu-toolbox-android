package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.CampusTimeZone
import com.xjtu.toolbox.util.safeStringOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

/** 用户交给小岱的定时任务：到点由后台跑一次，结果进收纳和通知。 */
@Serializable
data class PidaiTask(
    val id: String,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    /** 1=周一 … 7=周日；空 = 每天。 */
    val days: List<Int> = emptyList(),
    val lastRun: Long = 0L,
)

object PidaiTaskStore {
    const val MAX_TASKS = 5
    private const val KEY = "tasks"

    private val store get() = keyValueStore("pidai_tasks" + AccountContext.safeSuffix())
    private val serializer = ListSerializer(PidaiTask.serializer())

    fun all(): List<PidaiTask> =
        store.getString(KEY)?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun save(tasks: List<PidaiTask>) = store.putString(KEY, AppJson.encodeToString(serializer, tasks))

    fun add(task: PidaiTask): Boolean {
        val tasks = all()
        if (tasks.size >= MAX_TASKS) return false
        save(tasks + task)
        return true
    }

    fun remove(id: String) = save(all().filterNot { it.id == id })

    fun markRun(id: String, at: Long) = save(all().map { if (it.id == id) it.copy(lastRun = at) else it })

    /** [now] 这一刻该跑的任务：今天在 [PidaiTask.days] 里、过了设定时刻、今天这一次还没跑过。 */
    fun due(now: LocalDateTime, tasks: List<PidaiTask> = all()): List<PidaiTask> = tasks.filter { t ->
        if (t.days.isNotEmpty() && now.dayOfWeek.isoDayNumber !in t.days) return@filter false
        val slot = LocalDateTime(now.date, LocalTime(t.hour, t.minute))
        now >= slot && t.lastRun < slot.toInstant(CampusTimeZone).toEpochMilliseconds()
    }
}

/** 小岱管定时任务的三件工具：`schedule_task` / `list_tasks` / `cancel_task`。 */
object PidaiTaskAgentTools : AgentTools {

    private val DAY_CODES = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    override val toolDefinitions: String by lazy {
        fun prop(d: String) = buildJsonObject { put("type", "string"); put("description", d) }
        fun tool(name: String, desc: String, props: Map<String, JsonObject>, required: List<String>) = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", desc)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(props))
                    put("required", JsonArray(required.map { JsonPrimitive(it) }))
                })
            })
        }
        JsonArray(
            listOf(
                tool(
                    "schedule_task",
                    "让小岱在后台定时做一件事（查公开通知、联网搜、看校园地图），到点把结果发到通知和「消息收纳」。" +
                        "后台不登录学校系统，课表、成绩这类要登录的查不了。最多 ${PidaiTaskStore.MAX_TASKS} 个。",
                    mapOf(
                        "prompt" to prop("到点要做的事，写成给自己的指令，如「看教务处和学生处有没有奖学金评选通知，有就列出来」。"),
                        "time" to prop("每天几点，HH:mm。"),
                        "days" to prop("哪几天，逗号分隔：MON,TUE,WED,THU,FRI,SAT,SUN。缺省：每天。"),
                    ),
                    listOf("prompt", "time"),
                ),
                tool("list_tasks", "列出用户让小岱定时做的任务。", emptyMap(), emptyList()),
                tool("cancel_task", "取消一个定时任务。", mapOf("keyword" to prop("任务内容里的关键词。")), listOf("keyword")),
            ),
        ).toString()
    }

    override fun rateLimitKeyOf(toolName: String): String? = null

    override suspend fun execute(name: String, argsJson: String): String {
        val args = runCatching { AppJson.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        fun arg(k: String) = args[k].safeStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        return when (name) {
            "schedule_task" -> {
                val prompt = arg("prompt") ?: return ToolReply.missing("prompt")
                val time = arg("time") ?: return ToolReply.missing("time")
                val (h, m) = Regex("""^(\d{1,2}):(\d{2})$""").find(time)?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }
                    ?.takeIf { (a, b) -> a in 0..23 && b in 0..59 } ?: return ToolReply.badFormat("time", "HH:mm")
                val days = arg("days")?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }?.map { code ->
                    DAY_CODES.indexOf(code).takeIf { it >= 0 }?.plus(1) ?: return ToolReply.badValue("days", DAY_CODES.joinToString(","))
                }.orEmpty()
                val task = PidaiTask(
                    id = "t" + Clock.System.now().toEpochMilliseconds(),
                    prompt = prompt, hour = h, minute = m, days = days.distinct().sorted(),
                    // 今天已经过点的不补跑，从下一次开始
                    lastRun = Clock.System.now().toEpochMilliseconds(),
                )
                if (!PidaiTaskStore.add(task)) return "error: limit_reached; max: ${PidaiTaskStore.MAX_TASKS}"
                "ok: scheduled; time: $time; days: ${arg("days") ?: "every_day"}; prompt: $prompt"
            }
            "list_tasks" -> PidaiTaskStore.all().takeIf { it.isNotEmpty() }?.joinToString("\n") { t ->
                val days = if (t.days.isEmpty()) "每天" else t.days.joinToString(",") { DAY_CODES[it - 1] }
                "- ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')} $days｜${t.prompt}"
            } ?: ToolReply.empty("tasks")
            "cancel_task" -> {
                val kw = arg("keyword") ?: return ToolReply.missing("keyword")
                val hits = PidaiTaskStore.all().filter { it.prompt.contains(kw) }
                when {
                    hits.isEmpty() -> ToolReply.notFound("task", kw, PidaiTaskStore.all().map { it.prompt })
                    hits.size > 1 -> "error: ambiguous_match; param: keyword; candidates: ${hits.joinToString("；") { it.prompt }}"
                    else -> { PidaiTaskStore.remove(hits.single().id); "ok: cancelled; prompt: ${hits.single().prompt}" }
                }
            }
            else -> ToolReply.notFound("tool", name)
        }
    }
}
