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
        "description":"查用户的培养方案和毕业进度：方案树每组的学分要求、按课程重算的已修学分、教务报告原值、没归进方案的课（可能换过课程号）、方案说明文字。用于回答「还差什么」「这门能不能抵」。抵扣只能给建议，要用户在学业进度页确认并向教务核实。结果以教务为准。",
        "parameters":{"type":"object","properties":{
        "group":{"type":"string","description":"课组 id 或名称。给了就返回该组全部原始字段和课程；不给返回总览。"}
        },"required":[]}}}]
    """.trimIndent()

    override fun rateLimitKeyOf(toolName: String): String? = "degree"

    override suspend fun execute(name: String, argsJson: String): String {
        if (name != TOOL) return ToolReply.notFound("tool", name)
        val args = runCatching { AppJson.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val group = args["group"].safeStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val progress = try {
            DegreeBuilder.build(source.fetch(), DegreeConfirmations.load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolReply.failed(TOOL, e.message)
        }
        if (group == null) return DegreeAgentJson.overview(progress)
        return DegreeAgentJson.detail(progress, group)
            ?: ToolReply.notFound("group", group, progress.nodes.flatMap { it.flattened }.map { it.name })
    }

    private companion object {
        const val TOOL = "get_degree_progress"
    }
}
