package com.xjtu.toolbox.util

import android.content.Intent
import android.net.Uri
import com.xjtu.toolbox.Routes

/**
 * App Links / 深链路由。
 *
 * Scheme: `xjtu://`
 * 已支持的 host：
 * - agent / schedule / empty_room / campus_card / notification / score / grade / course
 *
 * `xjtu://agent?q=xxx` 中的 q 解析成 [DeepLinkTarget.prompt]，交给 AgentScreen 自动发送。
 * 其余 host 暂不消费 path（`grade/{term}` / `course/{courseId}` 仅 host 命中）。
 *
 * 失败（host 未知、scheme 不匹配）→ null，由调用方降级到主屏。
 */
object DeepLinkRouter {
    private const val SCHEME = "xjtu"

    data class DeepLinkTarget(
        val route: String,
        /** 额外的 prompt，agent 场景会用到。 */
        val prompt: String? = null,
    )

    fun resolve(intent: Intent): DeepLinkTarget? {
        val uri: Uri = intent.data ?: return null
        if (uri.scheme != SCHEME) return null
        return when (uri.host) {
            "agent" -> {
                val q = uri.getQueryParameter("q")?.takeIf { it.isNotBlank() }
                    ?: return DeepLinkTarget(Routes.AGENT)
                DeepLinkTarget(Routes.AGENT, q)
            }
            "schedule", "course" -> DeepLinkTarget(Routes.SCHEDULE)
            "empty_room" -> DeepLinkTarget(Routes.EMPTY_ROOM)
            "campus_card" -> DeepLinkTarget(Routes.CAMPUS_CARD)
            "payment_code", "pay" -> DeepLinkTarget(Routes.PAYMENT_CODE)
            "notification" -> DeepLinkTarget(Routes.NOTIFICATION)
            "score", "grade" -> DeepLinkTarget(Routes.JWAPP_SCORE)
            else -> null
        }
    }
}