package com.xjtu.toolbox.bulletin

import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 校园公告（`announce/bulletin.json`，Gitee / GitHub 两份 raw）的**取数** —— 从 `:app` 搬进 `:data`。
 *
 * ## 它为什么是「数据半搬」里的那一半
 *
 * 上游是**公开的**：不需要登录、没有会话、也没有 cookie，只是一份放在仓库 `announce` 分支上的
 * JSON。所以它不归任何校园站点，也不需要 `SiteSession` —— 搬进 `:data` 只是因为那里有 okhttp
 * （`HttpClients`），模型与规则在 `:core`（`Bulletin` / `BulletinRules`），屏还在 `:app`
 * （本批只搬数据，也**没有**为它点亮任何路由）。
 *
 * ## 两处如实的接缝
 *
 * 1. **UA 里的版本号**：真站点那边会看这个头（仓库页/对象存储的匿名抓取），搬迁前写的是
 *    `XJTUToolBox/${BuildConfig.VERSION_NAME}` —— `BuildConfig` 是 `:app` 的构建产物，`:data` 里没有。
 *    所以版本号改成**参数**：宿主把 `BuildConfig.VERSION_NAME` 递进来（`fetch(uaVersion)`）。
 *    不能直接省掉这个头：`HttpClients.base` 那个通用 [com.xjtu.toolbox.network.APP_UA] 是浏览器串，
 *    与「工具箱自报版本」不是同一个东西。
 * 2. **日志**：`android.util.Log` → `:core` 的 `Log`（其余搬迁同一条）。
 *
 * 取数形状一行未改：Gitee 优先、失败（或非 JSON）退 GitHub，两条都拿不到就 null
 * —— 调用方（`:app` 的 `LaunchNotices`）拿 null 时读本地缓存，那一半是宿主的。
 */
object BulletinApi {
    private const val TAG = "BulletinApi"
    // Gitee 的 api/v5 raw 接口现在对未登录请求一律 401（实测 2026-09-18），不再使用；
    // 网页 raw 地址会 302 到 raw.giteeusercontent.com，仍可匿名访问，国内优先走它。
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
        fetchUrl(GITEE_PAGE_URL, uaVersion) ?: fetchUrl(GITHUB_URL, uaVersion)
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
