package com.xjtu.toolbox.bulletin

import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 校园公告（`announce/bulletin.json`，OSS 主源 + Gitee/GitHub raw 兜底）的取数。 */
object BulletinApi {
    private const val TAG = "BulletinApi"
    // 公告优先走阿里云 OSS/CDN；Gitee/GitHub raw 作为兜底。
    // Gitee 的 api/v5 raw 接口现在对未登录请求一律 401（实测 2026-09-18），不再使用；
    // 网页 raw 地址会 302 到 raw.giteeusercontent.com，仍可匿名访问，国内优先走它。
    const val OSS_URL =
        "https://dzhz.zyxf.top/xjtu-toolbox/bulletin.json"
    const val GITEE_PAGE_URL =
        "https://gitee.com/yeliqin666/xjtu-toolbox-android/raw/announce/bulletin.json"
    const val GITHUB_URL =
        "https://raw.githubusercontent.com/yeliqin666/xjtu-toolbox-android/announce/bulletin.json"

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
        fetchUrl(OSS_URL, uaVersion)
            ?: fetchUrl(GITEE_PAGE_URL, uaVersion)
            ?: fetchUrl(GITHUB_URL, uaVersion)
    }

    private fun fetchUrl(url: String, uaVersion: String): FetchResult? {
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
            FetchResult(BulletinRules.parsePayload(trimmed), trimmed)
        } catch (e: Exception) {
            Log.w(TAG, "bulletin fetch $url failed", e)
            null
        }
    }
}
