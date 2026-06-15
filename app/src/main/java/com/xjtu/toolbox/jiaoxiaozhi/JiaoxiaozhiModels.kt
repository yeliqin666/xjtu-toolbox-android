package com.xjtu.toolbox.jiaoxiaozhi

data class JiaoxiaozhiModel(
    val id: String,
    val label: String,
    val description: String,
)

object JiaoxiaozhiModels {
    const val DEFAULT_ID = "qwen-plus"

    val all = listOf(
        JiaoxiaozhiModel(
            id = "qwen-plus",
            label = "Qwen-Plus",
            description = "速度与效果均衡"
        ),
        JiaoxiaozhiModel(
            id = "qwen-max",
            label = "Qwen-Max",
            description = "适合复杂任务"
        ),
        JiaoxiaozhiModel(
            id = "ep-20250207092149-pvc95",
            label = "DeepSeek-R1",
            description = "侧重推理、数学与代码"
        ),
        JiaoxiaozhiModel(
            id = "ep-20250219175323-5mvmg",
            label = "Doubao1.5-Pro",
            description = "响应稳定、综合能力均衡"
        ),
    )

    fun byId(id: String): JiaoxiaozhiModel = all.firstOrNull { it.id == id } ?: all.first()
}

data class JiaoxiaozhiMessage(
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class JiaoxiaozhiSession(
    val id: String,
    val title: String,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val locked: Boolean = false,
)

data class JiaoxiaozhiConversation(
    val messages: List<JiaoxiaozhiMessage> = emptyList(),
)
