package com.xjtu.toolbox.nav

import android.content.Intent
import android.net.Uri
import com.xjtu.toolbox.agent.AgentArticleLink
import com.xjtu.toolbox.agent.AgentVision
import androidx.core.content.IntentCompat

/** xjtu 深链与“用屁岱阅读”入口。 */
object DeepLinkRouter {
    private const val SCHEME = "xjtu"
    const val PIDAI_LINK_ALIAS = "com.xjtu.toolbox.agent.ReadWithPidai"

    data class DeepLinkTarget(
        val route: AppRoute,
        /** 额外的 prompt，agent 场景会用到。 */
        val prompt: String? = null,
        val autoSend: Boolean = false,
        val imageUris: List<String> = emptyList(),
        val imageCount: Int = imageUris.size,
    )

    fun resolve(intent: Intent): DeepLinkTarget? {
        if (intent.component?.className == PIDAI_LINK_ALIAS) {
            if (intent.type?.startsWith("image/", ignoreCase = true) == true) {
                val streams = when (intent.action) {
                    Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                    Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                    else -> emptyList()
                }
                val clips = intent.clipData?.let { data -> (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri } }.orEmpty()
                return imageTarget(intent.component?.className, intent.action, intent.type,
                    listOfNotNull(intent.dataString) + (streams + clips).map { it.toString() })
            }
            val item = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            return articleTarget(intent.component?.className, intent.action, intent.dataString,
                listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT), item?.text, item?.uri?.toString()))
        }
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
            // 站内公告详情（内容随链接带上；首页公告卡「查看详情」走同一屏）。
            "notice_detail" -> DeepLinkTarget(
                AppRoute.NoticeDetail(
                    noticeId = uri.getQueryParameter("id").orEmpty().ifBlank { "link" },
                    level = uri.getQueryParameter("level").orEmpty().ifBlank { "info" },
                    title = uri.getQueryParameter("title").orEmpty(),
                    body = uri.getQueryParameter("body").orEmpty(),
                    url = uri.getQueryParameter("url").orEmpty(),
                ),
            )
            else -> null
        }
    }

    internal fun articleTarget(component: String?, action: String?, data: String?, texts: List<CharSequence> = emptyList()): DeepLinkTarget? {
        if (component != PIDAI_LINK_ALIAS) return null
        val url = when (action) {
            Intent.ACTION_VIEW -> AgentArticleLink.fromUrl(data)
            Intent.ACTION_SEND -> texts.firstNotNullOfOrNull(AgentArticleLink::fromText)
            else -> null
        } ?: return null
        return DeepLinkTarget(AppRoute.Agent, AgentArticleLink.prompt(url), autoSend = true)
    }

    internal fun imageTarget(component: String?, action: String?, mime: String?, uris: List<String>): DeepLinkTarget? {
        if (component != PIDAI_LINK_ALIAS || mime?.startsWith("image/", ignoreCase = true) != true ||
            action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
        val all = uris.distinct().filter { it.startsWith("content://") || it.startsWith("file://") }
        val images = all.take(AgentVision.MAX_IMAGES_PER_MESSAGE)
        if (images.isEmpty()) return null
        return DeepLinkTarget(AppRoute.Agent, "请看看${if (images.size == 1) "这张图片" else "这些图片"}，概括主要内容。",
            autoSend = true, imageUris = images, imageCount = all.size)
    }
}
