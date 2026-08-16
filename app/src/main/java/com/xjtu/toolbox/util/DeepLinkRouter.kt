package com.xjtu.toolbox.util

import android.content.Intent
import android.net.Uri

/**
 * App Links / 深链路由。
 *
 * Scheme: `xjtu://`
 * 路径模板：
 * - xjtu://agent?q=xxx            → 跳 agent + 喂 prompt
 * - xjtu://schedule               → 跳课表
 * - xjtu://empty_room             → 跳空教室
 * - xjtu://campus_card            → 跳校园卡
 * - xjtu://notification           → 跳通知
 * - xjtu://score                  → 跳成绩
 * - xjtu://grade/{term}           → 跳成绩（term 占位，暂不消费）
 * - xjtu://course/{courseId}      → 跳课表（占位）
 *
 * 失败（host 未知、缺少 q 等）→ null，由调用方降级到主屏。
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
                    ?: return DeepLinkTarget(com.xjtu.toolbox.Routes.AGENT)
                DeepLinkTarget(com.xjtu.toolbox.Routes.AGENT, q)
            }
            "schedule" -> DeepLinkTarget(com.xjtu.toolbox.Routes.SCHEDULE)
            "empty_room" -> DeepLinkTarget(com.xjtu.toolbox.Routes.EMPTY_ROOM)
            "campus_card" -> DeepLinkTarget(com.xjtu.toolbox.Routes.CAMPUS_CARD)
            "notification" -> DeepLinkTarget(com.xjtu.toolbox.Routes.NOTIFICATION)
            "score", "grade" -> DeepLinkTarget(com.xjtu.toolbox.Routes.JWAPP_SCORE)
            "course" -> DeepLinkTarget(com.xjtu.toolbox.Routes.SCHEDULE)
            else -> null
        }
    }
}