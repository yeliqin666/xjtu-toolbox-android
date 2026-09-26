package com.xjtu.toolbox.nav

import android.content.Intent
import android.net.Uri

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
        val route: AppRoute,
        /** 额外的 prompt，agent 场景会用到。 */
        val prompt: String? = null,
    )

    fun resolve(intent: Intent): DeepLinkTarget? {
        val uri: Uri = intent.data ?: return null
        if (uri.scheme != SCHEME) return null
        return when (uri.host) {
            "agent" -> {
                val q = uri.getQueryParameter("q")?.takeIf { it.isNotBlank() }
                    ?: return DeepLinkTarget(AppRoute.Agent)
                DeepLinkTarget(AppRoute.Agent, q)
            }
            "schedule", "course" -> DeepLinkTarget(AppRoute.Schedule)
            "empty_room" -> DeepLinkTarget(AppRoute.EmptyRoom)
            "campus_card" -> DeepLinkTarget(AppRoute.CampusCard)
            "payment_code", "pay" -> DeepLinkTarget(AppRoute.PaymentCode)
            "notification" -> DeepLinkTarget(AppRoute.Notification)
            "score", "grade" -> DeepLinkTarget(AppRoute.JwappScore)
            else -> null
        }
    }
}