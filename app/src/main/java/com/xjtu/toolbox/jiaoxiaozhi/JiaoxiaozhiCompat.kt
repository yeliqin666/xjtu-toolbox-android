package com.xjtu.toolbox.jiaoxiaozhi

import com.xjtu.toolbox.auth.SessionManager
import java.util.UUID

/**
 * 交晓智的稳定兼容边界。
 *
 * UI、屁岱工具和未来的 provider 适配器都只依赖这里，不接触 Blade 鉴权、
 * endpoint 型模型 ID 或上游 SSE 事件。当前只提供单轮纯文本能力；后续可在此
 * 增加 messages/tool-call 协议，而不改业务调用方。
 */
class JiaoxiaozhiCompat(private val sessionManager: SessionManager) {

    suspend fun ask(
        question: String,
        modelId: String = JiaoxiaozhiModels.DEFAULT_ID,
        conversationId: String = UUID.randomUUID().toString(),
        networkEnabled: Boolean = true,
        onDelta: suspend (String) -> Unit = {},
    ): String {
        val session = sessionManager.getSite(JiaoxiaozhiSiteSession.SITE_KEY) as JiaoxiaozhiSiteSession
        val credentials = sessionManager.credentials
            ?: throw IllegalStateException("请先在应用中登录校园账号")
        session.ensureLogin(credentials.first, credentials.second)
        return try {
            JiaoxiaozhiApi(session).ask(
                question = question,
                sessionId = conversationId,
                modelId = JiaoxiaozhiModels.byId(modelId).id,
                networkEnabled = networkEnabled,
                onDelta = onDelta,
            )
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            session.ensureLogin(credentials.first, credentials.second, force = true)
            JiaoxiaozhiApi(session).ask(
                question = question,
                sessionId = conversationId,
                modelId = JiaoxiaozhiModels.byId(modelId).id,
                networkEnabled = networkEnabled,
                onDelta = onDelta,
            )
        }
    }
}
