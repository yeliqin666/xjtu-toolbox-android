package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.AppJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 工具层的纯逻辑那半边：工具清单（[agentToolDefinitions]）、它按 [AgentCapability] 做的取舍、参数访问器（[argStr] / [argInt] / [argBool]）、闹钟判据（[agentAlarmPlan]）、算术（[agentCalculate]）、引导文案（[agentGuideText]）与缓存年龄文案（[agentCacheAgeText]）。 */
class AgentToolCatalogTest {

    /** 工具清单里画出来的工具名，按画出来的顺序。 */
    private fun tools(honored: Set<AgentCapability>): List<String> =
        (AppJson.parseToJsonElement(agentToolDefinitions(honored)) as JsonArray)
            .map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }

    /** 所有端都画的那 24 档（纯逻辑 + 取数）。 */
    private val alwaysDrawn = listOf(
        "get_calendar", "get_schedule", "get_exam_schedule", "search_school_courses",
        "get_empty_rooms", "get_attendance", "get_grades", "get_card_info", "get_notifications",
        "search_yellow_page", "preference", "find_faculty", "web_search", "web_fetch",
        "get_library", "list_zyxf", "read_zyxf_file", "get_textbooks", "get_coupons",
        "get_lms", "get_lms_activity", "get_fitness_score", "app_guide", "calculate",
    )

    /** 进工具表的那三档（[AgentCapability.toolGated]）；另两档（相册、存进下载）只管屏。 */
    private val gated = AgentCapability.toolGated.map { it.tool!! }

    @Test
    fun `进工具表的端能力就是清单里那三个工具名`() {
        assertEquals(listOf("set_alarm", "app_setting", "add_schedule_event"), gated)
        // 五档总数与「只管屏的那两档」也一并钉住：加减一档要两边一起改
        assertEquals(5, AgentCapability.entries.size)
        assertEquals(listOf(AgentCapability.IMAGE_INPUT, AgentCapability.FILE_SAVE), AgentCapability.entries.filter { it.tool == null })
    }

    @Test
    fun `全 honored 的端画全 27 档`() {
        val drawn = tools(AgentCapability.entries.toSet())
        assertEquals(27, drawn.size)
        assertEquals((alwaysDrawn + gated).sorted(), drawn.sorted())
        assertEquals(drawn.size, drawn.distinct().size, "工具名不能重复")
    }

    @Test
    fun `什么都不接的端只画那 24 档`() {
        val drawn = tools(emptySet())
        assertEquals(alwaysDrawn.size, drawn.size)
        assertEquals(alwaysDrawn.sorted(), drawn.sorted())
        gated.forEach { assertTrue(it !in drawn, "没接的端不该画 $it") }
    }

    @Test
    fun `honored 里只列一档就只画那一档`() {
        val drawn = tools(setOf(AgentCapability.ALARM))
        assertEquals(alwaysDrawn.size + 1, drawn.size)
        assertTrue("set_alarm" in drawn)
        assertTrue("app_setting" !in drawn)
        assertTrue("add_schedule_event" !in drawn)
    }

    @Test
    fun `ofTool 只认这三档 其余一律 null`() {
        assertEquals(AgentCapability.ALARM, AgentCapability.ofTool("set_alarm"))
        assertEquals(AgentCapability.APP_SETTINGS, AgentCapability.ofTool("app_setting"))
        assertEquals(AgentCapability.SCHEDULE_EVENT_WRITE, AgentCapability.ofTool("add_schedule_event"))
        // 纯逻辑与取数那 24 档都不在清单里（= 任何端都放行）
        alwaysDrawn.forEach { assertNull(AgentCapability.ofTool(it), "$it 不该被当成端能力") }
        assertNull(AgentCapability.ofTool("不存在的工具"))
    }

    @Test
    fun `描述里的 JSON 是合法对象且带 parameters`() {
        val full = tools(AgentCapability.entries.toSet())
        val raw = AppJson.parseToJsonElement(agentToolDefinitions(AgentCapability.entries.toSet())) as JsonArray
        raw.forEachIndexed { i, el ->
            val fn = el.jsonObject["function"]!!.jsonObject
            assertEquals(full[i], fn["name"]!!.jsonPrimitive.content)
            assertTrue((fn["description"] ?: error("缺 description")).jsonPrimitive.content.isNotBlank())
            assertEquals("object", fn["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("function", el.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `无参工具的参数表是空对象`() {
        val raw = AppJson.parseToJsonElement(agentToolDefinitions(setOf(AgentCapability.ALARM))) as JsonArray
        val exam = raw.first { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "get_exam_schedule" }
        val params = exam.jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(0, (params["properties"] as JsonObject).size)
        assertEquals(0, (params["required"] as JsonArray).size)
    }

    @Test
    fun `工具参数访问器认字符串里的数字`() {
        val args = AppJson.parseToJsonElement(
            """{"s":"abc","i":"5","f":5.9,"b":"TRUE","n":null}"""
        ).jsonObject
        assertEquals("abc", args.argStr("s"))
        assertEquals(5, args.argInt("i"))
        assertEquals(5, args.argInt("f"))
        assertTrue(args.argBool("b") == true)
        assertNull(args.argStr("n"))
        assertNull(args.argInt("s"))
        assertNull(args.argBool("n"))
        assertNull(args.argStr("缺这个键"))
    }

    @Test
    fun `设置项布尔的写法认中英文与数字`() {
        listOf("true", "1", "on", "yes", "开", "开启", " TRUE ").forEach {
            assertEquals(true, agentParseBoolSetting(it), it)
        }
        listOf("false", "0", "off", "no", "关", "关闭", "False").forEach {
            assertEquals(false, agentParseBoolSetting(it), it)
        }
        assertNull(agentParseBoolSetting("也许"))
        assertNull(agentParseBoolSetting(""))
    }

    @Test
    fun `闹钟判据 参数范围与重复星期`() {
        assertEquals(AgentAlarmPlan.Ok(9, 0, emptyList()), agentAlarmPlan(9, null, null))
        assertEquals(AgentAlarmPlan.Ok(9, 30, emptyList()), agentAlarmPlan(9, 30, ""))
        // 重复星期：认中英文与缩写，去重保序，认不出来的丢掉
        assertEquals(
            AgentAlarmPlan.Ok(7, 0, listOf(1, 7)),
            agentAlarmPlan(7, null, "MON,周一, sunday ,这不是星期"),
        )
        assertEquals(
            AgentAlarmPlan.Invalid(ToolReply.missing("hour")),
            agentAlarmPlan(null, 0, null),
        )
        assertEquals(
            AgentAlarmPlan.Invalid(ToolReply.outOfRange("hour", "0-23")),
            agentAlarmPlan(24, 0, null),
        )
        assertEquals(
            AgentAlarmPlan.Invalid(ToolReply.outOfRange("minute", "0-59")),
            agentAlarmPlan(8, 70, null),
        )
    }

    @Test
    fun `算术工具 整数与小数两条口径`() {
        assertEquals("2^10 = 1024", agentCalculate("2^10"))
        assertEquals("1-2 = -1", agentCalculate("1-2"))
        assertEquals("(3.7*4+4.0*3)/(4+3) = 3.8286", agentCalculate("(3.7*4+4.0*3)/(4+3)"))
        assertEquals("1/4 = 0.25", agentCalculate("1/4"))
        // 去尾零：0.5000 写成 0.5
        assertEquals("1/2 = 0.5", agentCalculate("1/2"))
        assertEquals(ToolReply.missing("expression"), agentCalculate("   "))
        assertTrue(agentCalculate("1+").startsWith("无法计算「1+」"), agentCalculate("1+"))
    }

    @Test
    fun `引导文案三种取法`() {
        assertEquals(APP_GUIDE, agentGuideText("app"))
        assertEquals(APP_GUIDE, agentGuideText(" APP "))
        assertEquals(XINGQING_GUIDE, agentGuideText("campus"))
        assertEquals(XINGQING_GUIDE, agentGuideText("xingqing"))
        assertEquals(APP_GUIDE + "\n\n" + XINGQING_GUIDE, agentGuideText(null))
        assertEquals(APP_GUIDE + "\n\n" + XINGQING_GUIDE, agentGuideText("不认识的 topic"))
    }

    @Test
    fun `缓存年龄文案`() {
        assertEquals("刚刚", agentCacheAgeText(0))
        assertEquals("刚刚", agentCacheAgeText(59_999))
        assertEquals("约1分钟前", agentCacheAgeText(60_000))
        assertEquals("约59分钟前", agentCacheAgeText(3_599_999))
        assertEquals("约1小时前", agentCacheAgeText(3_600_000))
        assertEquals("约23小时前", agentCacheAgeText(86_399_999))
        assertEquals("约1天前", agentCacheAgeText(86_400_000))
    }

    @Test
    fun `什么都没接的宿主 三档一律 not_found`() {
        val host = AgentHost.None
        assertEquals(emptySet(), host.honored)
        assertEquals(
            ToolReply.notFound("tool", AgentCapability.ALARM.tool ?: ""),
            host.setAlarm(9, 0, null, null),
        )
        assertEquals(
            ToolReply.notFound("tool", AgentCapability.APP_SETTINGS.tool ?: ""),
            host.readAppSettings(),
        )
        assertEquals(
            ToolReply.notFound("tool", AgentCapability.APP_SETTINGS.tool ?: ""),
            host.writeAppSetting("dark_mode", "dark"),
        )
    }
}
