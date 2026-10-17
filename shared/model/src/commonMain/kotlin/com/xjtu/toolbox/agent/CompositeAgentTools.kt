package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把几份工具层并成一份交给调度循环：工具清单首尾相接，执行按工具名找到报了它的那一份。
 *
 * 用途是让 feature 之间不互相依赖还能给屁岱加工具：比如 `:feature:notice` 的待办工具只实现 [AgentTools]，
 * 由端的入口（`AgentHost.tools`）把它和主工具层并起来交给 `:feature:agent`。
 * 同名工具以排在前面的为准；画卡片、用户画像、记忆这几件只问 [primary]。
 */
class CompositeAgentTools(
    private val primary: AgentTools,
    private val extras: List<AgentTools>,
) : AgentTools {

    private val all = listOf(primary) + extras

    private fun namesOf(tools: AgentTools): Set<String> = runCatching {
        AppJson.parseToJsonElement(tools.toolDefinitions).jsonArray.mapNotNullTo(LinkedHashSet()) {
            it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
        }
    }.getOrDefault(emptySet())

    private val owners: Map<String, AgentTools> by lazy {
        val map = LinkedHashMap<String, AgentTools>()
        all.forEach { t -> namesOf(t).forEach { map.getOrPut(it) { t } } }
        map
    }

    override val toolDefinitions: String by lazy {
        val seen = HashSet<String>()
        val merged = all.flatMap { t ->
            runCatching { AppJson.parseToJsonElement(t.toolDefinitions).jsonArray.toList() }.getOrDefault(emptyList())
        }.filter { el ->
            val name = runCatching { el.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content }.getOrNull()
            name == null || seen.add(name)
        }
        AppJson.encodeToString(JsonArray.serializer(), JsonArray(merged))
    }

    override fun rateLimitKeyOf(toolName: String): String? = (owners[toolName] ?: primary).rateLimitKeyOf(toolName)

    override suspend fun execute(name: String, argsJson: String): String =
        (owners[name] ?: primary).execute(name, argsJson)

    override fun drainWidgets(): List<AgentWidget> = primary.drainWidgets()

    override suspend fun userContext(allowNetwork: Boolean): String = primary.userContext(allowNetwork)

    override fun memoryBlock(): String = primary.memoryBlock()
}