package com.xjtu.toolbox.degree

import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.agent.ToolReply
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.safeStringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 小岱的 `get_degree_progress`：培养方案树、重算后的进度、没归位的课。只读，不触发教务重新计算。 */
class DegreeAgentTools(private val source: DegreeSource) : AgentTools {

    override val toolDefinitions: String = """
        [{"type":"function","function":{"name":"$TOOL",
        "description":"本人培养方案与毕业进度：各课组学分要求、按课程重算的已修学分、教务原值、没归进方案的课（可能换过课程号）、方案说明。给 plan 用本人成绩对照别的方案（规划转专业），估算能算多少、还差什么。按课程重算的是估算，以教务为准。",
        "parameters":{"type":"object","properties":{
        "group":{"type":"string","description":"课组 id 或名称。给了就返回该组全部原始字段和课程；不给返回总览。"},
        "plan":{"type":"string","description":"别的培养方案名称关键词，如「计算机」。不给就是用户自己的方案。"},
        "grade":{"type":"string","description":"配合 plan 的年级，如 2023。缺省：用户自己的年级。"}
        },"required":[]}}}]
    """.trimIndent()

    override fun rateLimitKeyOf(toolName: String): String? = "degree"

    override suspend fun execute(name: String, argsJson: String): String {
        if (name != TOOL) return ToolReply.notFound("tool", name)
        val args = runCatching { AppJson.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        fun arg(key: String) = args[key].safeStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val group = arg("group")
        val planKeyword = arg("plan")
        return try {
            if (planKeyword != null) otherPlan(planKeyword, arg("grade"), group) else ownPlan(group)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolReply.failed(TOOL, e.message)
        }
    }

    private suspend fun ownPlan(group: String?): String {
        val progress = DegreeBuilder.build(source.fetch(), DegreeConfirmations.load())
        if (group == null) return DegreeAgentJson.overview(progress)
        return DegreeAgentJson.detail(progress, group)
            ?: ToolReply.notFound("group", group, progress.nodes.flatMap { it.flattened }.map { it.name })
    }

    private suspend fun otherPlan(keyword: String, grade: String?, group: String?): String {
        val year = grade ?: source.fetch().grade.orEmpty()
        val page = source.searchPlans(keyword, year)
        val plan = page.plans.singleOrNull()
            ?: page.plans.firstOrNull { it.name.contains(keyword) && page.plans.size == 1 }
            ?: return when {
                page.plans.isEmpty() -> ToolReply.empty("plans; keyword: $keyword; grade: $year")
                else -> "result: ambiguous_plan; total: ${page.total}; matches: ${page.plans.take(MAX_LISTED).joinToString("; ") { it.name }}"
            }
        val progress = DegreeBuilder.build(source.fetchPlan(plan))
        if (group == null) return DegreeAgentJson.overview(progress, otherPlan = true)
        return DegreeAgentJson.detail(progress, group)
            ?: ToolReply.notFound("group", group, progress.nodes.flatMap { it.flattened }.map { it.name })
    }

    private companion object {
        const val TOOL = "get_degree_progress"
        const val MAX_LISTED = 20
    }
}
