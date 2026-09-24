package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）的 github/data/GithubNetwork.kt、GithubAuthenticatedRequests.kt

import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 社区只对着本仓库的 Discussions。 */
object CommunityRepo {
    const val OWNER = "yeliqin666"
    const val NAME = "xjtu-toolbox-android"
    const val WEB_URL = "https://github.com/$OWNER/$NAME/discussions"

    /**
     * GitHub App 的 Client ID（不是密钥，写进 APK 没关系）。
     * App 设置里要勾选 Enable Device Flow，并关掉 user token 过期；权限只给本仓库 Discussions 读写。
     * 留空时登录页提示「尚未配置」。
     */
    const val CLIENT_ID = "Iv23liS3eGewqK6Ruged"
}

object GithubNetwork {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun builder(url: String, token: String? = null): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "XJTUToolbox-Community")
        .apply { if (token != null) header("Authorization", "Bearer $token") }
}

class GithubSignedOutException : IllegalStateException("GitHub session is not available")

/**
 * GitHub 限流时 403 和 429 都会出现，光看状态码分不清「没权限」和「等会再试」。
 */
class GithubApiException(
    val statusCode: Int,
    val rateLimited: Boolean = false
) : IllegalStateException("GitHub request failed") {
    companion object {
        fun of(response: okhttp3.Response): GithubApiException {
            val throttled = response.code == 429 ||
                (response.code == 403 && (response.header("Retry-After") != null ||
                    response.header("X-RateLimit-Remaining")?.trim() == "0"))
            return GithubApiException(statusCode = response.code, rateLimited = throttled)
        }
    }
}

/** 同 [runCatching]，但不把协程取消吞成普通失败。 */
suspend fun <T> githubRunCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
