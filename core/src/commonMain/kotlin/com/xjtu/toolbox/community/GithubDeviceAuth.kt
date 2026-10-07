package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/domain/GithubDeviceAuth.kt、
// github/data/GithubOAuthDeviceAuthRepository.kt、github/data/GithubTokenExpiry.kt

import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * 社区登录（GitHub App 设备码流程）里**与 HTTP 无关**的那一半：模型、轮询结果、端口接口，
 * 以及那个「按 interval 轮询直到授权/过期」的用例。
 *
 * 从 :app 的 `community/GithubDeviceAuth.kt` 切出来 —— 同一个文件里还坐着 okhttp 版实现
 * `GithubOAuthDeviceAuthRepository`（现在在 :app 的 `community/GithubOAuthDeviceAuthRepository.kt`）。
 *
 * 这一半之所以值得切，是因为它**本来就是可测的**：用例的 `nowEpochMillis` / `delayMillis`
 * 都是注入的，搬进 commonMain 后可以拿假时钟跑「慢下来 / 过期 / 被拒」三条路径，
 * 不必等真的轮询（:core 里已经有这套测试）。
 *
 * 只有一处替换：默认时钟从 `System.currentTimeMillis()` 换成 `Clock.System.now().toEpochMilliseconds()`
 * （同一件事：UTC 墙上时钟的毫秒数），因为 `System` 在 commonMain 不存在。
 */

data class GithubDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtEpochMillis: Long,
    val intervalSeconds: Int
) {
    override fun toString(): String =
        "GithubDeviceAuthorization(deviceCode=<redacted>, userCode=$userCode, verificationUri=$verificationUri, expiresAtEpochMillis=$expiresAtEpochMillis, intervalSeconds=$intervalSeconds)"
}

data class GithubDeviceAccessToken(
    val accessToken: String,
    val tokenType: String,
    val scopes: Set<String>,
    val refreshToken: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val refreshExpiresAtEpochMillis: Long? = null,
) {
    override fun toString(): String =
        "GithubDeviceAccessToken(accessToken=<redacted>, tokenType=$tokenType, scopes=$scopes)"
}

sealed interface GithubDevicePollResult {
    data object Pending : GithubDevicePollResult
    data object SlowDown : GithubDevicePollResult
    data class Authorized(val token: GithubDeviceAccessToken) : GithubDevicePollResult
    data object Expired : GithubDevicePollResult
    data object Denied : GithubDevicePollResult
}

/** 设备码登录的端口：`start()` 拿码，`poll()` 轮询是否已授权。实现留在 :app（okhttp）。 */
interface GithubDeviceAuthRepository {
    val isConfigured: Boolean
    suspend fun start(): Result<GithubDeviceAuthorization>
    suspend fun poll(deviceCode: String): Result<GithubDevicePollResult>
}

class GithubDeviceFlowNotConfiguredException : IllegalStateException("GitHub OAuth device flow is not configured")
class GithubDeviceAuthorizationDeniedException : IllegalStateException("GitHub device authorization was denied")
class GithubDeviceAuthorizationExpiredException : IllegalStateException("GitHub device authorization expired")
class GithubDeviceFlowProtocolException(val errorCode: String) :
    IllegalStateException("GitHub device flow failed")

class AwaitGithubDeviceAuthorizationUseCase(
    private val repository: GithubDeviceAuthRepository,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) }
) {
    suspend operator fun invoke(
        authorization: GithubDeviceAuthorization
    ): Result<GithubDeviceAccessToken> {
        var intervalSeconds = authorization.intervalSeconds.coerceAtLeast(MINIMUM_INTERVAL_SECONDS)
        while (nowEpochMillis() < authorization.expiresAtEpochMillis) {
            delayMillis(intervalSeconds * 1_000L)
            if (nowEpochMillis() >= authorization.expiresAtEpochMillis) {
                return Result.failure(GithubDeviceAuthorizationExpiredException())
            }
            val result = repository.poll(authorization.deviceCode).getOrElse {
                return Result.failure(it)
            }
            when (result) {
                GithubDevicePollResult.Pending -> Unit
                GithubDevicePollResult.SlowDown -> {
                    intervalSeconds = (intervalSeconds + SLOW_DOWN_INCREMENT_SECONDS)
                        .coerceAtMost(MAXIMUM_INTERVAL_SECONDS)
                }
                is GithubDevicePollResult.Authorized -> return Result.success(result.token)
                GithubDevicePollResult.Expired -> {
                    return Result.failure(GithubDeviceAuthorizationExpiredException())
                }
                GithubDevicePollResult.Denied -> {
                    return Result.failure(GithubDeviceAuthorizationDeniedException())
                }
            }
        }
        return Result.failure(GithubDeviceAuthorizationExpiredException())
    }

    private companion object {
        const val MINIMUM_INTERVAL_SECONDS = 5
        const val SLOW_DOWN_INCREMENT_SECONDS = 5
        const val MAXIMUM_INTERVAL_SECONDS = 300
    }
}

/** Missing expiry denotes a non-expiring OAuth token, never an already expired token. */
fun githubTokenExpiry(now: Long, seconds: Long?): Long? {
    if (seconds == null) return null
    if (now < 0 || seconds <= 0 || seconds > (Long.MAX_VALUE - now) / 1000) {
        throw GithubDeviceFlowProtocolException("invalid_token_expiry")
    }
    return now + seconds * 1000
}

fun validateGithubRefreshToken(token: String?): String? {
    if (token != null && (token.length !in 20..255 || token.any { it.isWhitespace() || it.isISOControl() })) {
        throw GithubDeviceFlowProtocolException("invalid_refresh_token")
    }
    return token
}
