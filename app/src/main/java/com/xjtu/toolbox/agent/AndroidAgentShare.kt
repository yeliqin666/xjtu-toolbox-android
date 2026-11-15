package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.restoreIfNeeded
import com.xjtu.toolbox.platform.keyValueStore
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

internal fun androidAgentShareStore(): AgentShareStore {
    AccountContext.restoreIfNeeded()
    return AgentShareStore(keyValueStore("agent_shares" + AccountContext.safeSuffix()),
        deleteImages = { paths -> paths.forEach { File(it).delete() } })
}

internal fun receiveAgentShare(text: String, images: List<String> = emptyList()) {
    if (androidAgentShareStore().add(text, images) != null) AgentShareInbox.received()
}

internal data class ImportedAgentShare(val text: String, val images: List<String>, val unsupported: Boolean)

/**
 * 把分享或拖入的附件读成文字与图片。类型先看提供方、再看文件名；
 * 两者都说不清（空或 octet-stream，QQ 等常见）时先按图片解码，不行再按文本读。
 */
internal suspend fun importAgentShare(context: Context, text: String, uris: List<String>, mime: String?): ImportedAgentShare = withContext(Dispatchers.IO) {
    val texts = mutableListOf(text)
    val images = mutableListOf<String>()
    var unsupported = false
    try {
        for (raw in uris) {
            ensureActive()
            val uri = Uri.parse(raw)
            try {
                val type = (context.contentResolver.getType(uri) ?: mime).orEmpty().lowercase()
                val name = displayName(context, uri)
                val extension = name.substringAfterLast('.', "").lowercase()
                val isImage = type.startsWith("image/") || extension in IMAGE_EXTENSIONS
                val isText = type.startsWith("text/") || type == "message/rfc822" || extension in TEXT_EXTENSIONS
                val unknown = !isImage && !isText && (type.isEmpty() || type == "application/octet-stream" || type == "*/*")
                val image = if (isImage || unknown) attachAgentImage(context, uri) else null
                val content = if (image == null && (isText || unknown)) readSharedText(context, uri, html = type == "text/html" || extension in HTML_EXTENSIONS) else null
                when {
                    image != null -> images += image
                    content != null -> texts += content
                    else -> unsupported = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { unsupported = true }
        }
        ImportedAgentShare(texts.filter { it.isNotBlank() }.joinToString("\n\n").take(MAX_CHARS), images, unsupported)
    } catch (cancelled: CancellationException) {
        images.forEach { File(it).delete() }
        throw cancelled
    }
}

private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment.orEmpty()

/** 读不出像样的文字（二进制、PDF、压缩包、乱码过多）返回 null。 */
private fun readSharedText(context: Context, uri: Uri, html: Boolean): String? {
    val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
        val out = StringBuilder()
        val buffer = CharArray(2048)
        while (out.length < MAX_CHARS) {
            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_CHARS - out.length))
            if (count < 0) break
            out.append(buffer, 0, count)
        }
        out.toString()
    } ?: return null
    if (content.isBlank() || '\u0000' in content || content.startsWith("%PDF") || content.startsWith("PK\u0003\u0004") ||
        content.count { it == '�' } > content.length / 20) return null
    return if (html) android.text.Html.fromHtml(content, android.text.Html.FROM_HTML_MODE_LEGACY).toString() else content
}

private const val MAX_CHARS = 30_000
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "heif", "bmp")
private val HTML_EXTENSIONS = setOf("html", "htm")
private val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "json", "xml") + HTML_EXTENSIONS
