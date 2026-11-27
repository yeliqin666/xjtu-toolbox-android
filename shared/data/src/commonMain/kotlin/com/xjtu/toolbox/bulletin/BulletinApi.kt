package com.xjtu.toolbox.bulletin

import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * announce 分支上的文件（公告 `bulletin.json`、大家在问 `feedback-qa.json`）的取数。
 *
 * 先问阿里云 OSS（国内快），拿不到再问 GitHub 上的 announce 分支。
 * 分支由 announce.yml 从讨论区「通知公告」生成，生成后同步到 OSS。
 */
object BulletinApi {
    private const val TAG = "BulletinApi"
    private const val OSS_BASE = "https://dzhz.zyxf.top/xjtu-toolbox/"
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
        val json = fetchFileNow(BULLETIN_FILE, uaVersion)
        json?.let { FetchResult(BulletinRules.parsePayload(it), it) }
    }

    /** announce 分支上的一个 JSON 文件（OSS 优先，GitHub 兜底）；两边都拿不到返回 null。 */
    suspend fun fetchFile(name: String, uaVersion: String): String? = withContext(Dispatchers.IO) {
        fetchFileNow(name, uaVersion)
    }

    private fun fetchFileNow(name: String, uaVersion: String): String? =
        fetchUrl(OSS_BASE + name, uaVersion) ?: fetchUrl(GITHUB_BASE + name, uaVersion)

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
