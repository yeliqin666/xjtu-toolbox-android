package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.agent.AgentCapability
import com.xjtu.toolbox.agent.AgentMemory
import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.agent.ToolReply
import com.xjtu.toolbox.agent.agentCalculate
import com.xjtu.toolbox.agent.agentGuideText
import com.xjtu.toolbox.agent.agentToolDefinitions
import com.xjtu.toolbox.agent.argInt
import com.xjtu.toolbox.agent.argStr
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.faculty.FacultySearchQuery
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.yellowpage.YellowPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 桌面这一端**真会照着办**的工具名单 —— 工具表就是按它裁出来的。
 *
 * | 工具 | 这一端拿什么办 |
 * |---|---|
 * | `calculate` | `:core` 的 [agentCalculate]（纯逻辑，一行端代码都没有） |
 * | `app_guide` | `:core` 的 [agentGuideText]（同上） |
 * | `preference` | [AgentMemory]（本端落在 0600 的 `agent_memory<账号后缀>.properties`） |
 * | `search_yellow_page` | `:core` 的 `YellowPageApi` + 本端那条免登录客户端（与黄页那一屏同一个站点、同一份实现） |
 * | `find_faculty` | `:data` 的 [FacultyApi]（与教师检索那一屏同一份实现） |
 *
 * ## 为什么是「裁一张表」而不是「照着 `:app` 再画一张」
 *
 * `:core` 的 [agentToolDefinitions] 是四端唯一一份描述表（描述文案、参数 schema 都在那里，
 * `AgentToolCatalogTest` 逐档钉着它）。桌面这一端不该抄第二份 —— 抄了就有两份文案要同步。
 * 所以这里只做一件事：**按名字留下这一端接得上的那几档**，其余一个字都不画。
 *
 * ## 为什么必须裁（而不是留下 24 档、答不上来的回 `not_found`）
 *
 * 与 `AgentCapability` 同一条纪律：**画了就得能用**。模型看见一档就会调，调了只拿到一句
 * `error: not_found`，用户看到的就是「这功能时灵时不灵」——那比不画它更坏。
 * `:core` 的目录里那 24 档「纯逻辑 + 取数」在 `:app` 上都能答（它有全部 `*Api`），
 * 桌面这一端现在只接了上面那 5 档的取数/本体，所以只画这 5 档；
 * 剩下 19 档卡的都是同一件事：**取数还没搬进 `:data` 并接上本端的宿主面**（见 `DesktopAgentHost` 的 TODO）。
 */
internal val DESKTOP_AGENT_TOOLS = listOf(
    // 顺序与 `:core` 目录里的那几档一致（`:core` 那份是**唯一**一份顺序定义，这里只是筛子）：
    // 裁完送给模型的顺序就与 `:app` 那边逐档相同。
    "search_yellow_page",
    "preference",
    "find_faculty",
    "app_guide",
    "calculate",
)

/**
 * 桌面这一端送给模型的工具表：`:core` 那份描述表按 [DESKTOP_AGENT_TOOLS] 裁一刀。
 *
 * [honored] 仍照旧交给 `:core`（端能力那三档 —— 闹钟 / 应用设置 / 自建日程写入 —— 只有声明了才画），
 * 桌面的那一份是空集（见 `DesktopAgentHost.honored`），所以裁完之后与 [DESKTOP_AGENT_TOOLS] 一一对应。
 */
internal fun desktopAgentToolDefinitions(honored: Set<AgentCapability>): String {
    val drawn = (AppJson.parseToJsonElement(agentToolDefinitions(honored)) as JsonArray)
        .filter { element ->
            element.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content in DESKTOP_AGENT_TOOLS
        }
    return JsonArray(drawn).toString()
}

/**
 * 桌面端的工具层（`:core` 的 [AgentTools] 在这一端的实现）。
 *
 * 只在 [execute] 里按名字分派；每一档的本体要么是 `:core` 的纯逻辑，要么是 `:data` 的取数 ——
 * 这一端自己不写取数与文案（与 `:app` 的 `AgentToolRegistry` 同一条口径：那边剩的也是「调用」）。
 *
 * @param honored 端能力清单（只影响工具表画哪几档，见 [desktopAgentToolDefinitions]）
 * @param memory 记住的用户偏好（拼进系统提示的那一段由 [memoryBlock] 取走）
 * @param yellowPage 黄页取数（`:core` 的端口；本端传 `YellowPageApi(yellowPageClient)`）
 * @param faculty 教师检索取数（`:data` 的类；单测注入假上游客户端）
 */
internal class DesktopAgentTools(
    honored: Set<AgentCapability>,
    private val memory: AgentMemory,
    private val yellowPage: YellowPageSource,
    private val faculty: FacultyApi,
) : AgentTools {

    override val toolDefinitions: String = desktopAgentToolDefinitions(honored)

    /**
     * 限流分组键：同一个后端系统的两次调用之间要隔开一点，别连着猛打学校服务器。
     * 这一端只有两档走网络（黄页、教师检索），其余是纯本地 ⇒ 返回 null。
     */
    override fun rateLimitKeyOf(toolName: String): String? = when (toolName) {
        "search_yellow_page" -> "yellow_page"
        "find_faculty" -> "faculty"
        else -> null
    }

    /** 记住的用户偏好，拼进系统提示。走工具层是因为 ViewModel 手上只有这一格缝。 */
    override fun memoryBlock(): String = memory.promptBlock()

    override suspend fun execute(name: String, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = runCatching { AppJson.parseToJsonElement(argsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        when (name) {
            "calculate" -> agentCalculate(args.argStr("expression").orEmpty())
            "app_guide" -> agentGuideText(args.argStr("topic"))
            // 给 value 是记下，不给是删掉（与 `:app` 同一条判据）
            "preference" -> {
                val key = args.argStr("key") ?: ""
                val value = args.argStr("value").orEmpty()
                if (value.isBlank()) memory.forget(key) else memory.remember(key, value)
            }
            "search_yellow_page" -> searchYellowPage(
                query = args.argStr("query"),
                category = args.argStr("category"),
                limit = args.argInt("limit") ?: 10,
            )
            "find_faculty" -> findFaculty(
                name = args.argStr("name") ?: "",
                college = args.argStr("college"),
                limit = args.argInt("limit") ?: 3,
            )
            // 工具表里没画的档回 not_found：模型可能凭旧对话里的记忆调过来（那段历史是在别的端上生成的）。
            else -> ToolReply.notFound("tool", name)
        }
    }

    /**
     * 校园黄页：机构总机电话，不含个人号码。
     *
     * 判据与文案逐条对齐 `:app` 的 `AgentToolRegistry.searchYellowPage`（那边读的是
     * `appYellowPageApi(context)`，这一端读注入的同一个端口）；差别只有一处：
     * 桌面没有宿主缓存（`:core` 的黄页缓存缝要一份按账号隔离的落盘实现），屏那边也传 `cache = null`
     * ⇒ 这一档**不缓存、每次现拉**，屏上的语义不变。
     */
    private suspend fun searchYellowPage(query: String?, category: String?, limit: Int): String {
        return try {
            val data = yellowPage.getData()
            val categoryId = category?.takeIf { it.isNotBlank() }?.let { name ->
                data.categories.firstOrNull {
                    it.name == name || it.name.contains(name) || name.contains(it.name)
                }?.id ?: return ToolReply.notFound("category", name, data.categories.map { it.name })
            }
            val keyword = query.orEmpty().trim()
            val result = data.departments.asSequence()
                .filter { categoryId == null || it.categoryId == categoryId }
                .filter {
                    keyword.isBlank() ||
                        it.name.contains(keyword, ignoreCase = true) ||
                        it.phone.contains(keyword, ignoreCase = true)
                }
                .take(limit.coerceIn(1, 20))
                .toList()
            if (result.isEmpty()) return ToolReply.empty("yellow_page")
            buildString {
                append("校园黄页")
                if (data.updateTime.isNotBlank()) append("（更新于${data.updateTime}）")
                append("：\n")
                result.forEach { department ->
                    val categoryName = data.categories.firstOrNull { it.id == department.categoryId }?.name
                    append("${department.name}｜${department.phone}")
                    categoryName?.let { append("｜$it") }
                    append("\n")
                }
                if (result.size == limit.coerceIn(1, 20)) append("（已按 limit 截断）")
            }.trimEnd()
        } catch (e: Exception) {
            ToolReply.failed("search_yellow_page", e.message)
        }
    }

    /**
     * 教师主页检索：只给本校在职教师，不含学生 / 行政人员 / 校外人士。
     *
     * 判据与文案逐条对齐 `:app` 的 `AgentToolRegistry.findFaculty`：学院只能在客户端筛
     *（检索接口的学院参数要的是数字 id，而模型手上只有名字），筛空了退回不筛 ——
     * 宁可多给几条，也别因为「电信学部 / 电信学院」这种写法差异把正确结果全滤掉。
     */
    private suspend fun findFaculty(name: String, college: String?, limit: Int): String {
        if (name.isBlank()) return ToolReply.missing("name")
        val n = limit.coerceIn(1, 8)
        val all = try {
            faculty.searchAll(query = FacultySearchQuery(name = name), limit = 60)
        } catch (e: Exception) {
            return ToolReply.failed("find_faculty", e.message)
        }
        val members = college?.takeIf { it.isNotBlank() }
            ?.let { c -> all.filter { it.collegeName.contains(c) }.ifEmpty { all } }
            ?: all
        if (members.isEmpty()) return ToolReply.empty("name: $name")

        val exact = members.filter { it.name == name }
        val picked = (if (exact.isNotEmpty()) exact else members)
            .sortedByDescending { it.clickTimes }
            .take(n)

        return buildString {
            if (exact.isEmpty()) {
                append("无同名，相近结果：\n")
            } else if (exact.size > 1) {
                append("有 ${exact.size} 位同名老师：\n")
            }
            picked.forEachIndexed { i, m ->
                if (i > 0) append("\n")
                append("【${m.name}】")
                listOfNotNull(
                    m.collegeName.takeIf { it.isNotBlank() },
                    m.proRank.takeIf { it.isNotBlank() },
                    m.tutorLabel.takeIf { it.isNotBlank() },
                ).takeIf { it.isNotEmpty() }?.let { append(" " + it.joinToString(" · ")) }
                append("\n")
                m.researchDirections.takeIf { it.isNotEmpty() }?.let {
                    append("研究方向：${it.joinToString("、")}\n")
                }
                m.officeLocation.takeIf { it.isNotBlank() }?.let { append("办公地点：$it\n") }
                m.email.takeIf { it.isNotBlank() }?.let { append("邮箱：$it\n") }
                listOfNotNull(
                    m.phone.takeIf { it.isNotBlank() },
                    m.mobilePhone.takeIf { it.isNotBlank() },
                    m.contact.takeIf { it.isNotBlank() },
                ).distinct().takeIf { it.isNotEmpty() }?.let {
                    append("电话：${it.joinToString(" / ")}\n")
                }
                m.homepageUrl.takeIf { it.isNotBlank() }?.let { append("主页：$it\n") }
                m.profile.takeIf { it.isNotBlank() }?.let {
                    append("简介：${it.replace(Regex("\\s+"), " ").take(200)}\n")
                }
            }
        }.trim()
    }
}
