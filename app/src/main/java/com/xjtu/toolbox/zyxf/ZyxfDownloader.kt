package com.xjtu.toolbox.zyxf

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.lms.LmsDownloadStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 仲英学辅资料站的文件下载。
 *
 * 独立于 `classreplay.DownloadManager`：那一套是为**课程回放视频**设计的（断点续传、
 * 并发限流、暂停/恢复、按 camera/audio 分轨），字段和交互都围绕视频任务。资料站下载的是
 * 课件、真题、笔记这类小文件，一次请求就完，塞进视频任务表只会让下载管理页的分类变得混乱。
 *
 * 所以这里走另一条路：**下完直接落到公共 Downloads/XJTUToolBox 并登记进
 * [LmsDownloadStore]**，与成绩单、思源课件同一个目录和同一份记录——用户找文件时不用记
 * 「哪一类在哪儿」。
 *
 * 关键设计：**文件名与类型一律以服务器响应为准**，不做扩展名白名单。资料站的格式
 * 五花八门（.md/.tex/.caj/.wps/.tar.gz、甚至没有扩展名），任何本地清单都会漏，
 * 漏掉的表现就是"点了没反应"。
 */
object ZyxfDownloader {

    private const val TAG = "ZyxfDownloader"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 下载并保存。
     *
     * @param fallbackName WebView 猜的文件名，仅在响应头没给时使用
     * @param referer 来源页。教务处附件下载要带，缺了会回到验证码 HTML。
     * @return 成功时返回最终保存的文件名，失败返回 null
     */
    fun download(
        context: Context,
        url: String,
        fallbackName: String,
        userAgent: String?,
        cookie: String?,
        referer: String? = "https://zyxf.top/",
        category: String = LmsDownloadStore.CATEGORY_ZYXF,
    ): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "*/*")
                // 带上 WebView 的 UA 与 cookie：资料站不少链接要登录态，
                // 缺了会拿到 403 或登录页 HTML —— 那种"下载成功"其实是个坏文件。
                .apply {
                    userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
                    cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
                    referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) }
                }
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "download failed: HTTP ${resp.code} $url")
                    return null
                }
                val disposition = resp.header("Content-Disposition")
                val mime = resp.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    .orEmpty()

                val name = fileNameOf(disposition, url, fallbackName)
                val bytes = resp.body?.bytes() ?: return null
                // 验证码页 / 挑战页也是 200。存成「附件.docx」用户只会看到打不开。
                if (isHtmlDisguisedAsFile(mime, disposition, bytes)) {
                    Log.w(TAG, "refusing html body as file: $url")
                    return null
                }
                Log.d(TAG, "downloaded name=$name mime=$mime size=${bytes.size}")

                LmsDownloadStore.saveBytes(
                    context = context,
                    fileName = name,
                    mimeType = mime.ifBlank { "application/octet-stream" },
                    bytes = bytes,
                    category = category,
                )?.let { name }
            }
        } catch (e: Exception) {
            Log.e(TAG, "download error: $url", e)
            null
        }
    }

    /**
     * 定文件名，优先级：`Content-Disposition` → URL 末段 → 调用方兜底。
     *
     * `filename*=UTF-8''%E4%B8%AD%E6%96%87.pdf`（RFC 5987）要先解百分号编码，
     * 否则中文名会存成一串 %E4%B8%AD。
     */
    private fun fileNameOf(disposition: String?, url: String, fallback: String): String {
        disposition?.let { d ->
            Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
                .find(d)?.groupValues?.get(1)?.let { raw ->
                    runCatching { URLDecoder.decode(raw.trim(), "UTF-8") }
                        .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.sanitized() }
                }
            Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
                .find(d)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }?.let { return it.sanitized() }
        }
        url.substringAfterLast('/').substringBefore('?')
            .takeIf { it.isNotBlank() }
            ?.let { raw ->
                val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                return decoded.sanitized()
            }
        return fallback.ifBlank { "download_${System.currentTimeMillis()}" }.sanitized()
    }

    private fun String.sanitized(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "download" }
}

internal fun isHtmlDisguisedAsFile(
    mime: String,
    disposition: String?,
    bytes: ByteArray,
): Boolean {
    val disp = disposition.orEmpty()
    if (disp.contains("attachment", ignoreCase = true) ||
        disp.contains("filename", ignoreCase = true)
    ) {
        return false
    }
    if (mime.contains("text/html", ignoreCase = true) ||
        mime.contains("application/xhtml", ignoreCase = true)
    ) {
        return true
    }
    val head = bytes.decodeToString(endIndex = minOf(bytes.size, 256)).trimStart()
        .lowercase()
    return head.startsWith("<!doctype html") || head.startsWith("<html")
}

/** 教务处 CMS：验证码通过后会给 download.jsp 加上一次性 codeValue。 */
internal fun isCmsOneShotDownload(url: String): Boolean {
    val query = url.substringAfter('?', "").lowercase()
    if ("codevalue=" !in query) return false
    val path = url.substringBefore('?').lowercase()
    return "download.jsp" in path || "downloadattach" in query
}
