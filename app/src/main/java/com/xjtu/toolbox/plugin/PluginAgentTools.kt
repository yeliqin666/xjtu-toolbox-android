package com.xjtu.toolbox.plugin

import com.xjtu.toolbox.agent.AgentTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把小岱的工具层接到插件上。
 *
 * 工具定义来自 `plugin.json`，所以 agent 启动时不需要加载插件 dex；只有模型真的调用
 * 某个工具时才在 IO 线程加载。异常在这里收口成错误文本，不影响 Agent 循环。
 */
internal class PluginAgentTools(private val manager: PluginManager) : AgentTools {

    override val toolDefinitions: String get() = manager.toolDefinitions()

    /** 插件工具是本地代码，不占学校站点的限流分组。 */
    override fun rateLimitKeyOf(toolName: String): String? = null

    override suspend fun execute(name: String, argsJson: String): String =
        withContext(Dispatchers.IO) { manager.executeTool(name, argsJson) }
}
