package com.xjtu.toolbox.agent

import com.xjtu.toolbox.community.CommunityRepo
import com.xjtu.toolbox.community.SecureGithubSession
import com.xjtu.toolbox.community.postableCategories
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray

/**
 * 社区发帖（[AgentPostCard]）在 `:app` 这一端的落地：列出 `propose_post` 让模型能起草，
 * 执行只会来自预览卡上的「发布」——模型那边的同名调用被 [AgentRunner] 截成出卡，到不了这里。
 */
object CommunityPostAgentTools : AgentTools {
    override val toolDefinitions: String =
        AppJson.encodeToString(JsonArray.serializer(), JsonArray(listOf(AgentPostCard.definition())))

    override fun rateLimitKeyOf(toolName: String): String? = null

    override suspend fun execute(name: String, argsJson: String): String {
        if (name != AgentPostCard.TOOL) return ToolReply.notFound("tool", name)
        val post = AgentPostCard.parse(argsJson)
        val session = SecureGithubSession.get()
        if (session.login.value == null) return "error: 还没登录社区账号，请先到「社区」用 GitHub 登录后再发"
        val repo = session.repository
        val page = repo.list(CommunityRepo.OWNER, CommunityRepo.NAME, null)
            .getOrElse { return "error: 连不上社区：${it.message.orEmpty()}" }
        val categories = postableCategories(
            repo.categories(CommunityRepo.OWNER, CommunityRepo.NAME).getOrElse { return "error: 读不到社区板块：${it.message.orEmpty()}" },
            page.viewerPermission,
        )
        val category = categories.firstOrNull { it.name == post.category }
            ?: categories.firstOrNull { it.name == AgentPostCard.DEFAULT_CATEGORY }
            ?: categories.firstOrNull()
            ?: return "error: 社区没有可发帖的板块"
        val created = repo.create(page.repositoryId, category.id, post.title, post.body + FOOTER)
            .getOrElse { return "error: 发帖失败：${it.message.orEmpty()}" }
        return "ok: #${created.number} ${created.url}"
    }

    private const val FOOTER = "\n\n<sub>由小岱协助整理</sub>"
}
