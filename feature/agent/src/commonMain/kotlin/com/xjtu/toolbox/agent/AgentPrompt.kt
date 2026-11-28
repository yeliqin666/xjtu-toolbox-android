package com.xjtu.toolbox.agent

/**
 * 屁岱 system prompt。
 *
 * 只写模型自己推不出来的：运行环境（渲染能力、卡片）、本校业务的判断规则和常识。
 * 模型本来就会的（防编造、语气分寸、安全兜底）、工具描述里有的、查得到的（App 说明在 `app_guide`、
 * 规章与 GPA 换算在 `search_campus_guide`）不写；边界靠代码（MCP 逐次确认等），不靠提示词表态。
 *
 * **一段对话只在开头生成一次，之后不再改动**（见 AgentViewModel）：中途改名字、
 * 记下新偏好，都从下一个新对话起生效。中途改写系统提示等于篡改上下文——前面几轮是按
 * 旧设定答的，前后人设对不上，前缀缓存也整段作废。
 * 所以这里只放一段对话里不会变的东西；会变的（实时时间、当前模型）放在每条用户消息头。
 */
object AgentPrompt {
    private const val REVISION = "<!-- xjtu-agent-prompt:v17 -->"
    fun isCurrent(prompt: String): Boolean = REVISION in prompt

    fun build(
        assistantName: String,
        userContext: String = "",
        /** 见 [AgentMemory]，拼好的那一段从 `AgentMemory.promptBlock()` 来。没有偏好时为空串。 */
        memoryBlock: String = "",
        /** 角色口吻（形象设置里那一段）；空 = 不加。 */
        persona: String = "",
    ): String = listOf(
        REVISION,
        """
你在西安交大学生用的 App「岱宗盒子」里，用户叫你「$assistantName」。

界面能渲染表格、`${'$'}…${'$'}` 公式和独占一行的 `![](https://…)` 图片；卡片里有的明细正文不重复。

画像里的校区、学院、上课地点只是学籍信息，不代表此刻在哪；推荐附近或出行以用户说的或定位为准。

校园背景：
- 校区：兴庆（主校区）、雁塔（医学部）、曲江、创新港（中国西部科技创新港）、海南陵水；书院：彭康、文治、宗濂、启德、仲英、南洋、崇实、励志、钱学森。
- 「晚上的课」指 9–11 节；「刚解放」指刚空出来的教室；教室如 `A-403` 在 4 楼、`西二楼-305` 在 3 楼、`中3楼-2314` 在 2 楼。
        """.trimIndent(),
        if (userContext.isBlank()) "" else "用户画像：\n$userContext",
        memoryBlock.trim(),
        personaBlock(persona),
    ).filter { it.isNotBlank() }.joinToString("\n\n")

    /**
     * 角色口吻（#423）：明说是用户选的，模型才肯演；口头禅限量，不然每句都塞。
     */
    private fun personaBlock(persona: String): String {
        val text = persona.trim().ifEmpty { return "" }
        return "说话风格（用户在设置里给你选的角色口吻，照着说）：\n$text\n" +
            "口头禅一条回答里最多一两处。"
    }
}
