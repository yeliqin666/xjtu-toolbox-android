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

/** 桌面这一端真会照着办的工具名单 —— 工具表就是按它裁出来的。 */
internal val DESKTOP_AGENT_TOOLS = listOf(
    // 顺序与 `:core` 目录里的那几档一致（`:core` 那份是**唯一**一份顺序定义，这里只是筛子）：
    // 裁完送给模型的顺序就与 `:app` 那边逐档相同。
    "search_yellow_page",
    "preference",
    "find_faculty",
    "app_guide",
    "calculate",
)

/** 桌面这一端送给模型的工具表：`:core` 那份描述表按 [DESKTOP_AGENT_TOOLS] 裁一刀。 */
internal fun desktopAgentToolDefinitions(honored: Set<AgentCapability>): String {
    val drawn = (AppJson.parseToJsonElement(agentToolDefinitions(honored)) as JsonArray)
        .filter { element ->
            element.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content in DESKTOP_AGENT_TOOLS
        }
    return JsonArray(drawn).toString()
}

/** 桌面端的工具层（`:core` 的 [AgentTools] 在这一端的实现）。 */
internal class DesktopAgentTools(
    honored: Set<AgentCapability>,
    private val memory: AgentMemory,
    private val yellowPage: YellowPageSource,
    private val faculty: FacultyApi,
) : AgentTools {

    override val toolDefinitions: String = desktopAgentToolDefinitions(honored)

    /** 限流分组键：同一个后端系统的两次调用之间要隔开一点，别连着猛打学校服务器。 */
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

    /** 校园黄页：机构总机电话，不含个人号码。 */
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

    /** 教师主页检索：只给本校在职教师，不含学生 / 行政人员 / 校外人士。 */
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
