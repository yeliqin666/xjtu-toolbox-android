package com.xjtu.toolbox.plugin.sample

import com.xjtu.toolbox.plugin.api.Plugin
import com.xjtu.toolbox.plugin.api.PluginContext
import com.xjtu.toolbox.plugin.api.PluginEntry
import com.xjtu.toolbox.plugin.api.PluginPage
import com.xjtu.toolbox.plugin.api.PluginTool

/** `plugin.json` 的 entryClass 指向这里；必须有无参构造。 */
class SamplePlugin : PluginEntry {
    override fun create(context: PluginContext): Plugin = SamplePluginImpl(context)
}

private class SamplePluginImpl(private val context: PluginContext) : Plugin {
    override fun pages(): List<PluginPage> = listOf(SamplePage(context))
    override fun tools(): List<PluginTool> = listOf(SampleTool(context))
}
