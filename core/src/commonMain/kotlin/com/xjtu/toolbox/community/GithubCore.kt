package com.xjtu.toolbox.community

import kotlinx.coroutines.CancellationException

// 改编自 JoyinJoester/Etoile（GPL-3.0）的 github/data/GithubNetwork.kt、GithubAuthenticatedRequests.kt

/**
 * 社区（本仓库的 GitHub Discussions）里**与 HTTP 无关**的那一半：站点常量、异常类型、
 * 以及「不把协程取消吞成普通失败」的那个 runCatching。
 *
 * 从 :app 的 `community/GithubCore.kt` 里切出来的 —— 那是因为同一个文件里还坐着 `GithubNetwork`
 * （okhttp 的 `OkHttpClient` 与 `Request.Builder`）。这一半留着 :app 就永远搬不动
 * `DiscussionDetailViewModel`（它 `is GithubApiException` 决定文案）、也搬不动任何一屏社区界面。
 * HTTP 那一半现在在 :app 的 `community/GithubNetwork.kt`。
 */

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

class GithubSignedOutException : IllegalStateException("GitHub session is not available")

/**
 * GitHub 限流时 403 和 429 都会出现，光看状态码分不清「没权限」和「等会再试」。
 *
 * ⚠️ 「从 okhttp 的 `Response` 造出这个异常」那一步留在 :app（`community/GithubNetwork.kt` 的
 * `githubApiExceptionOf`）：这里只留数据，才不带 okhttp 类型。
 */
class GithubApiException(
    val statusCode: Int,
    val rateLimited: Boolean = false
) : IllegalStateException("GitHub request failed")

/** 同 [runCatching]，但不把协程取消吞成普通失败。 */
suspend fun <T> githubRunCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
