package com.xjtu.toolbox.zyxf

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 仲英学辅资料站（zyxf.top）的只读接口。
 *
 * 后端开源在 [Guochaoo/zyxf](https://github.com/Guochaoo/zyxf)，`/api` 下检索、目录树、
 * 目录内容、取文件直链这几条都是**匿名可读**的（写操作才要管理员），所以这里不带任何
 * 凭据，也不碰 [ZyxfDownloader] 那条要 WebView cookie 的路径。
 *
 * 只做"查"和"读"两件事：
 * - 查：[search] / [listFolder]，把资料站当成一棵可检索的目录树；
 * - 读：[readText] 只认纯文本（txt/csv/md）。PDF、Office 走服务端的 WebOffice 预览，
 *   在 App 里解析它们要拖进一整套文档解析库，投入和收益不成比例——
 *   这类文件就给出直链，让用户去资料页看或下载。
 */
object ZyxfApi {

    private const val TAG = "ZyxfApi"
    const val SITE = "https://zyxf.top"
    private const val API = "$SITE/api"

    /** 能直接读成文字的扩展名。其余一律只给链接。 */
    private val TEXT_EXTS = setOf("txt", "csv", "md", "markdown", "tex", "json", "log")

    /** 读文本的字节上限。资料站单个 txt 可以很大，整份灌进对话只会把上下文撑爆。 */
    private const val MAX_TEXT_BYTES = 256 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class Entry(
        val id: Int,
        val name: String,
        /** 目录路径，如 `高等数学/历年卷`；检索结果才有，浏览目录时为空。 */
        val path: String = "",
        val isFolder: Boolean,
        val sizeBytes: Long = 0,
        val ext: String = "",
    ) {
        val extNormalized: String get() = ext.lowercase().removePrefix(".")
        val readable: Boolean get() = !isFolder && extNormalized in TEXT_EXTS

        /** 人读的大小。目录没有 size，返回空串。 */
        val sizeText: String
            get() = when {
                isFolder || sizeBytes <= 0 -> ""
                sizeBytes < 1024 -> "${sizeBytes}B"
                sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024}KB"
                else -> String.format("%.1fMB", sizeBytes / 1024.0 / 1024.0)
            }
    }

    data class SearchResult(
        val entries: List<Entry>,
        /** 服务端截断了结果。命中太多时要提示用户把关键词写细一点。 */
        val truncated: Boolean,
    )

    data class FileLink(
        val url: String,
        val name: String,
        val ext: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Referer", "$SITE/")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 服务端错误体是 {"error": "..."}，把它原样带出去比"HTTP 400"有用得多。
                val msg = runCatching { JSONObject(body).optString("error") }.getOrNull()
                throw RuntimeException(msg?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}")
            }
            return body
        }
    }

    private fun folderOf(o: JSONObject) = Entry(
        id = o.optInt("id"),
        name = o.optString("name"),
        path = o.optString("folder_path", ""),
        isFolder = true,
    )

    private fun fileOf(o: JSONObject) = Entry(
        id = o.optInt("id"),
        name = o.optString("name"),
        path = o.optString("folder_path", ""),
        isFolder = false,
        sizeBytes = o.optLong("size"),
        ext = o.optString("ext", ""),
    )

    private fun JSONArray?.mapObjects(block: (JSONObject) -> Entry): List<Entry> {
        val arr = this ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(block) }
    }

    /** 全站检索。目录排在文件前，与网页端一致。 */
    fun search(keyword: String): SearchResult {
        val q = java.net.URLEncoder.encode(keyword.trim(), "UTF-8")
        val json = JSONObject(get("$API/search?q=$q"))
        return SearchResult(
            entries = json.optJSONArray("folders").mapObjects(::folderOf) +
                json.optJSONArray("files").mapObjects(::fileOf),
            truncated = json.optBoolean("truncated", false),
        )
    }

    /**
     * 列出一个目录。
     *
     * @param folderId 0 表示根目录（后端就是这么约定的，不是"缺省值"）
     */
    fun listFolder(folderId: Int = 0): List<Entry> {
        val json = JSONObject(get("$API/folders/$folderId/contents"))
        return json.optJSONArray("folders").mapObjects(::folderOf) +
            json.optJSONArray("files").mapObjects(::fileOf)
    }

    /** 面包屑，用来告诉用户这份资料在哪个目录下。 */
    fun breadcrumb(folderId: Int): String = runCatching {
        val arr = JSONObject(get("$API/folders/$folderId/contents")).optJSONArray("breadcrumb")
        (0 until (arr?.length() ?: 0))
            .mapNotNull { arr?.optJSONObject(it)?.optString("name") }
            .filter { it.isNotBlank() }
            .joinToString("/")
    }.getOrDefault("")

    /**
     * 取文件的临时直链。
     *
     * 服务端签的是 30 分钟有效的 OSS 链接，并且**按 IP 限流**（每分钟 60 次、每小时 240 次），
     * 所以别拿它当稳定地址缓存起来，也别在循环里连着要。
     */
    fun fileLink(fileId: Int, forDownload: Boolean = false): FileLink {
        val json = JSONObject(get("$API/files/$fileId/url" + if (forDownload) "?download=1" else ""))
        return FileLink(
            url = json.optString("url"),
            name = json.optString("name"),
            ext = json.optString("ext"),
            mimeType = json.optString("mime_type", "application/octet-stream"),
            sizeBytes = json.optLong("size"),
        )
    }

    /**
     * 下载一份资料到公共下载目录，返回落盘的文件名；失败返回 null。
     *
     * 走 `download=1` 取链：服务端会给 OSS 加 `attachment` 头并记一次下载量，
     * 和网页端点下载按钮是同一条路径——不绕开站点的统计。
     *
     * 复用 [ZyxfDownloader]，所以文件会和成绩单、思源课件落在同一个目录、同一份下载记录里。
     * 签名链自带鉴权，不需要 UA 和 cookie。
     */
    fun download(context: android.content.Context, fileId: Int): String? {
        val link = runCatching { fileLink(fileId, forDownload = true) }.getOrNull() ?: return null
        if (link.url.isBlank()) return null
        return ZyxfDownloader.download(
            context = context,
            url = link.url,
            fallbackName = link.name,
            userAgent = null,
            cookie = null,
            referer = null,
        )
    }

    /**
     * 把纯文本资料读成字符串，最多 [MAX_TEXT_BYTES]。
     *
     * 非文本格式返回 null——调用方该给链接，而不是把二进制硬解成乱码丢给模型。
     */
    fun readText(entry: Entry): String? {
        if (!entry.readable) return null
        val link = runCatching { fileLink(entry.id) }.getOrNull() ?: return null
        val req = Request.Builder().url(link.url).get().build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.source()?.let { source ->
                    source.readByteArray(
                        minOf(MAX_TEXT_BYTES.toLong(), resp.body?.contentLength()?.takeIf { it > 0 } ?: MAX_TEXT_BYTES.toLong())
                    )
                } ?: return null
                bytes.toString(Charsets.UTF_8)
            }
        }.onFailure { Log.w(TAG, "readText failed: ${entry.name}", it) }.getOrNull()
    }
}
