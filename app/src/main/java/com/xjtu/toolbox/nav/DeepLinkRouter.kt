package com.xjtu.toolbox.nav

import android.content.Intent
import android.net.Uri
import com.xjtu.toolbox.agent.AgentArticleLink
import com.xjtu.toolbox.agent.AgentVision
import androidx.core.content.IntentCompat

/** xjtu 深链与“用屁岱阅读”入口。 */
object DeepLinkRouter {
    private const val SCHEME = "xjtu"
    private val BARE_URL = Regex("""https?://\S+""")
    const val PIDAI_LINK_ALIAS = "com.xjtu.toolbox.agent.ReadWithPidai"
    const val PIDAI_SHARE_ALIAS = "com.xjtu.toolbox.agent.ShareWithPidai"

    data class DeepLinkTarget(
        val route: AppRoute,
        /** 额外的 prompt，agent 场景会用到。 */
        val prompt: String? = null,
        val autoSend: Boolean = false,
        val imageUris: List<String> = emptyList(),
        val imageCount: Int = imageUris.size,
        val sharedMaterial: Boolean = false,
        val mimeType: String? = null,
    )

    fun resolve(intent: Intent): DeepLinkTarget? {
        if (intent.component?.className == PIDAI_SHARE_ALIAS && intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            val clips = intent.clipData?.let { data -> (0 until data.itemCount).map { data.getItemAt(it) } }.orEmpty()
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let {
                android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            }
            val texts = listOfNotNull(text) +
                (if (text == null) intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT).orEmpty() else emptyList()) + clips.mapNotNull { it.text }
            return sharedTarget(texts.map { it.toString() }, (streams + clips.mapNotNull { it.uri }).map { it.toString() },
                intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString())?.copy(mimeType = intent.type)
        }
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
            else -> null
        }
    }

    /**
     * [subject] 是邮件主题式的元数据（EXTRA_SUBJECT）：正文只是一条链接时当它的标题；
     * 否则正文自己说得清，拼进去只会重复（微信转发聊天记录就是邮件式分享，主题与正文首行同义）。
     */
    internal fun sharedTarget(texts: List<String>, uris: List<String>, subject: String? = null): DeepLinkTarget? {
        val body = (texts + uris.filter { it.startsWith("https://") || it.startsWith("http://") })
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val title = subject?.trim()?.takeIf { it.isNotEmpty() && body.singleOrNull()?.matches(BARE_URL) == true }
        val text = (listOfNotNull(title) + body).joinToString("\n\n").take(30_000)
        val images = uris.distinct().filter { it.startsWith("content://") }
        if (text.isEmpty() && images.isEmpty()) return null
        return DeepLinkTarget(AppRoute.Agent, text, imageUris = images.take(AgentVision.MAX_IMAGES_PER_MESSAGE),
            imageCount = images.size, sharedMaterial = true)
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
