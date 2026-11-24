package com.xjtu.toolbox.bulletin

import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * announce 分支上的文件（公告 `bulletin.json`、大家在问 `feedback-qa.json`）的取数：Gitee 优先，GitHub raw 兜底。
 * 分支由 announce.yml 从讨论区「通知公告」生成，GitHub 和 Gitee 两边一致；不走 OSS（OSS 只放小游戏和安装包）。
 */
object BulletinApi {
    private const val TAG = "BulletinApi"
    // Gitee 的 api/v5 raw 接口对未登录请求一律 401（实测 2026-09-18）；
    // 网页 raw 地址会 302 到 raw.giteeusercontent.com，可匿名访问，国内优先走它。
    private val BASES = listOf(
        "https://gitee.com/yeliqin666/xjtu-toolbox-android/raw/announce/",
        "https://raw.githubusercontent.com/yeliqin666/xjtu-toolbox-android/announce/",
    )

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

    suspend fun fetch(uaVersion: String): FetchResult? =
        fetchFile("bulletin.json", uaVersion)?.let { FetchResult(BulletinRules.parsePayload(it), it) }

    /** announce 分支上的一个 JSON 文件；两边都拿不到返回 null。 */
    suspend fun fetchFile(name: String, uaVersion: String): String? = withContext(Dispatchers.IO) {
        BASES.firstNotNullOfOrNull { fetchUrl(it + name, uaVersion) }
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
}
