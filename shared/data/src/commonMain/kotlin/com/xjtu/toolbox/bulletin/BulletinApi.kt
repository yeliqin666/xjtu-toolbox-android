package com.xjtu.toolbox.bulletin

import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * announce 分支上的文件（公告 `bulletin.json`、大家在问 `feedback-qa.json`）的取数。
 *
 * 公告同时问阿里云 OSS 和 Gitee，取 `updatedAt` 较新的那份：OSS 国内快，但边缘缓存可能还停在旧版本，
 * 不能让它盖掉 Gitee 上的新公告。两边都拿不到再问 GitHub。其余文件只在 Gitee / GitHub 上。
 * 分支由 announce.yml 从讨论区「通知公告」生成，生成后同步到 Gitee 和 OSS。
 */
object BulletinApi {
    private const val TAG = "BulletinApi"
    private const val OSS_BASE = "https://dzhz.zyxf.top/xjtu-toolbox/"
    // Gitee 的 api/v5 raw 接口对未登录请求一律 401（实测 2026-09-18）；
    // 网页 raw 地址会 302 到 raw.giteeusercontent.com，可匿名访问，国内优先走它。
    private const val GITEE_BASE = "https://gitee.com/yeliqin666/xjtu-toolbox-android/raw/announce/"
    private const val GITHUB_BASE = "https://raw.githubusercontent.com/xjtu-dzhz/dzhz/announce/"

    private val client = HttpClients.base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class FetchResult(
        val items: List<Bulletin>,
        val rawJson: String,
    )

    suspend fun fetch(uaVersion: String): FetchResult? = withContext(Dispatchers.IO) {
        val json = coroutineScope {
            val oss = async { fetchUrl(OSS_BASE + BULLETIN_FILE, uaVersion) }
            val gitee = async { fetchUrl(GITEE_BASE + BULLETIN_FILE, uaVersion) }
            newer(gitee.await(), oss.await())
        } ?: fetchUrl(GITHUB_BASE + BULLETIN_FILE, uaVersion)
        json?.let { FetchResult(BulletinRules.parsePayload(it), it) }
    }

    /** announce 分支上的一个 JSON 文件（Gitee 优先，GitHub 兜底）；两边都拿不到返回 null。 */
    suspend fun fetchFile(name: String, uaVersion: String): String? = withContext(Dispatchers.IO) {
        fetchUrl(GITEE_BASE + name, uaVersion) ?: fetchUrl(GITHUB_BASE + name, uaVersion)
    }

    /**
     * 两份公告取较新的：比 `updatedAt`（announce.py 写的 ISO 时间，同一格式可直接按字符串比）。
     * 没有这个字段的是旧文件，算最旧；一样新时用 [primary]（Gitee 与分支同步，最可信）。
     */
    internal fun newer(primary: String?, secondary: String?): String? {
        if (primary == null || secondary == null) return primary ?: secondary
        fun stamp(json: String) = json.safeParseJsonObject()["updatedAt"].safeString()
        return if (stamp(secondary) > stamp(primary)) secondary else primary
    }

    private fun fetchUrl(url: String, uaVersion: String): String? {
        return try {
            val body = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "XJTUToolBox/$uaVersion")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "bulletin fetch $url -> ${response.code}")
                    return null
                }
                response.body?.string().orEmpty()
            }
            val trimmed = body.trim()
            if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) {
                Log.w(TAG, "bulletin fetch $url returned non-JSON")
                return null
            }
            trimmed
        } catch (e: Exception) {
            Log.w(TAG, "bulletin fetch $url failed", e)
            null
        }
    }

    private const val BULLETIN_FILE = "bulletin.json"
}
