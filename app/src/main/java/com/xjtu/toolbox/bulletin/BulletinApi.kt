package com.xjtu.toolbox.bulletin

import android.util.Log
import com.xjtu.toolbox.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object BulletinApi {
    private const val TAG = "BulletinApi"
    const val GITEE_URL =
        "https://gitee.com/api/v5/repos/yeliqin666/xjtu-toolbox-android/raw/bulletin.json?ref=announce"
    const val GITEE_PAGE_URL =
        "https://gitee.com/yeliqin666/xjtu-toolbox-android/raw/announce/bulletin.json"
    const val GITHUB_URL =
        "https://raw.githubusercontent.com/yeliqin666/xjtu-toolbox-android/announce/bulletin.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class FetchResult(
        val items: List<Bulletin>,
        val rawJson: String,
    )

    suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        fetchUrl(GITEE_URL) ?: fetchUrl(GITEE_PAGE_URL) ?: fetchUrl(GITHUB_URL)
    }

    private fun fetchUrl(url: String): FetchResult? {
        return try {
            val body = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "XJTUToolBox/${BuildConfig.VERSION_NAME}")
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
