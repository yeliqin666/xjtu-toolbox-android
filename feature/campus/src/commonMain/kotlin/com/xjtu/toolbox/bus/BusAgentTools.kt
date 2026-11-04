package com.xjtu.toolbox.bus

import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.agent.AgentWidget
import com.xjtu.toolbox.agent.BusWidget
import com.xjtu.toolbox.agent.BusWidgetItem
import com.xjtu.toolbox.agent.ToolReply
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.CampusTimeZone
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.todayOnCampus
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 小岱的 `get_campus_bus`：查某天的校车班次与余量。 */
class BusAgentTools(
    private val source: BusSource,
    private val today: () -> LocalDate = { todayOnCampus() },
    private val nowHhmm: () -> String = {
        Clock.System.now().toLocalDateTime(CampusTimeZone).time.toString().take(5)
    },
) : AgentTools {

    override val toolDefinitions: String = """
        [{"type":"function","function":{"name":"$TOOL",
        "description":"查校车（创新港班车、兴庆班车）某天的班次、途经站点和座位余量。查今天时默认不列已发车的班次。学生坐校车无需预约，排队上车，别叫学生去预约。站点可写站名或校区名（兴庆、雁塔、创新港）。",
        "parameters":{"type":"object","properties":{
        "date":{"type":"string","description":"今天、明天、后天或 yyyy-MM-dd。缺省：今天。"},
        "from":{"type":"string","description":"上车站或校区。"},
        "to":{"type":"string","description":"下车站或校区。"},
        "line":{"type":"string","description":"线路名关键词，如「7A」「兴庆B2」。"},
        "include_departed":{"type":"boolean","description":"查今天时也列出已发车的班次。缺省：false。"}
        },"required":[]}}}]
    """.trimIndent()

    override fun rateLimitKeyOf(toolName: String): String? = "campus_bus"

    private val pendingWidgets = mutableListOf<AgentWidget>()

    override fun drainWidgets(): List<AgentWidget> = pendingWidgets.toList().also { pendingWidgets.clear() }

    override suspend fun execute(name: String, argsJson: String): String {
        if (name != TOOL) return ToolReply.notFound("tool", name)
        val args = runCatching { AppJson.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        fun arg(key: String) = args[key].safeStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        val date = parseDate(arg("date")) ?: return ToolReply.badFormat("date", "今天|明天|后天|yyyy-MM-dd")
        val all = try {
            source.runs(date)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolReply.failed(TOOL, e.message)
        }
        val from = arg("from")
        val to = arg("to")
        val line = arg("line")
        val matched = all.filter { run ->
            BusRules.runsBetween(run, from, to) && (line == null || run.lineName.contains(line, ignoreCase = true))
        }
        val hideDeparted = date == today() && arg("include_departed") != "true"
        val now = nowHhmm()
        val shown = if (hideDeparted) matched.filterNot { BusRules.hasDeparted(it, now) } else matched
        val scope = "bus $date" + listOfNotNull(from?.let { "from $it" }, to?.let { "to $it" }, line?.let { "line $it" }).joinToString("") { " $it" }
        if (shown.isEmpty()) {
            return ToolReply.empty(scope) + if (matched.isNotEmpty()) "; departed_hidden: ${matched.size}" else ""
        }
        pendingWidgets += BusWidget(
            title = date.toString(),
            items = shown.map {
                BusWidgetItem(it.time, BusRules.shortLineName(it.lineName), "${it.from} → ${it.to}", it.seatsLeft, it.seats, it.plate, it.isTight)
            },
        )
        return buildString {
            appendLine("date: $date; runs: ${shown.size}; boarding: ${BusRules.BOARDING_TIP}")
            if (shown.size < matched.size) appendLine("departed_hidden: ${matched.size - shown.size}")
            shown.take(MAX_RUNS).forEach { appendLine(describe(it)) }
            if (shown.size > MAX_RUNS) appendLine("truncated: ${shown.size - MAX_RUNS}")
            append(ToolReply.EXTERNAL_DATA)
        }
    }

    private fun describe(run: BusRun) = buildString {
        append("${run.time} ${run.lineName}（${run.category}）")
        append(" 途经：${run.stops.joinToString("→") { stop -> stop.name + (stop.time?.takeIf { it != run.time }?.let { "($it)" } ?: "") }}")
        append(" 余量：${run.seatsLeft}/${run.seats}")
        if (run.plate.isNotEmpty()) append(" 车牌：${run.plate}")
    }

    private fun parseDate(raw: String?): LocalDate? = when (raw) {
        null, "今天" -> today()
        "明天" -> today().plus(DatePeriod(days = 1))
        "后天" -> today().plus(DatePeriod(days = 2))
        else -> runCatching { LocalDate.parse(raw) }.getOrNull()
    }

    private companion object {
        const val TOOL = "get_campus_bus"
        const val MAX_RUNS = 30
    }
}
