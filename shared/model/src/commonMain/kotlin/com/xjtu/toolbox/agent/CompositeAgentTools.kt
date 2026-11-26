package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把几份工具层并成一份交给调度循环：工具清单首尾相接，执行按工具名找到报了它的那一份。
 *
 * 用途是让 feature 之间不互相依赖还能给屁岱加工具：比如 `:feature:notice` 的待办工具只实现 [AgentTools]，
 * 由端的入口（`AgentHost.tools`）把它和主工具层并起来交给 `:feature:agent`。
 * 同名工具以排在前面的为准；卡片从每一份里收，用户画像、记忆只问 [primary]。
 */
class CompositeAgentTools(
    private val primary: AgentTools,
    private val extras: List<AgentTools>,
) : AgentTools {

    private val all = listOf(primary) + extras

    private data class Catalog(
        val sources: List<String>,
        val definitions: String,
        val owners: Map<String, AgentTools>,
    )

    private val cachedCatalog = MutableStateFlow<Catalog?>(null)

    private fun catalog(): Catalog {
        val sources = all.map { it.toolDefinitions }
        cachedCatalog.value?.takeIf { it.sources == sources }?.let { return it }
        val owners = LinkedHashMap<String, AgentTools>()
        val seen = HashSet<String>()
        val merged = ArrayList<JsonElement>()
        for ((index, tools) in all.withIndex()) {
            val definitions = runCatching { AppJson.parseToJsonElement(sources[index]).jsonArray }.getOrNull()
            definitions.orEmpty().forEach { definition ->
                val name = runCatching {
                    definition.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                }.getOrNull()
                if (name == null || seen.add(name)) merged.add(definition)
                if (name != null) owners.getOrPut(name) { tools }
            }
            tools.legacyNames.forEach { owners.getOrPut(it) { tools } }
        }
        val definitions = AppJson.encodeToString(JsonArray.serializer(), JsonArray(merged))
        return Catalog(sources, definitions, owners).also { cachedCatalog.value = it }
    }

    override val toolDefinitions: String get() = catalog().definitions

    override fun rateLimitKeyOf(toolName: String): String? = (catalog().owners[toolName] ?: primary).rateLimitKeyOf(toolName)

    override suspend fun execute(name: String, argsJson: String): String =
        (catalog().owners[name] ?: primary).execute(name, argsJson)

    override fun drainWidgets(): List<AgentWidget> = all.flatMap { it.drainWidgets() }

    override fun drainImages(): List<String> = all.flatMap { it.drainImages() }

    override suspend fun userContext(allowNetwork: Boolean): String = primary.userContext(allowNetwork)

    override fun memoryBlock(): String = primary.memoryBlock()
}