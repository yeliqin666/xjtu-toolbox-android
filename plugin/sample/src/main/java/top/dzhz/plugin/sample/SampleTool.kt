package top.dzhz.plugin.sample

import org.json.JSONObject
import com.xjtu.toolbox.plugin.api.PluginContext
import com.xjtu.toolbox.plugin.api.PluginTool

/** 小岱工具：统计本周课表条数，并附上课表原文。定义以 assets/plugin.json 为准。 */
internal class SampleTool(private val context: PluginContext) : PluginTool {

    override fun name(): String = "demo_course_count"

    override fun execute(argsJson: String): String {
        val date = runCatching { JSONObject(argsJson.ifBlank { "{}" }).optString("date") }
            .getOrDefault("")
            .trim()
        val args = if (date.isEmpty()) "{}" else JSONObject().put("date", date).toString()
        val raw = context.schoolTool("get_schedule", args)
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return "error: plugin_bad_school_json"
        if (!obj.optBoolean("ok")) return "error: " + obj.optString("error", "school_tool_failed")
        val text = obj.optString("text")
        val courseLines = text.lineSequence().count { it.contains("｜") }
        return buildString {
            append("scope: ").append(if (date.isEmpty()) "本周" else date).append('\n')
            append("course_lines: ").append(courseLines).append('\n')
            append(text.take(6000))
        }
    }
}
