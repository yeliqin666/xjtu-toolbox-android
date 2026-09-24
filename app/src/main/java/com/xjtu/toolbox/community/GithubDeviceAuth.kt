package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/domain/GithubDeviceAuth.kt、
// github/data/GithubOAuthDeviceAuthRepository.kt、github/data/GithubTokenExpiry.kt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

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
    val authorizationSource: String = "oauth"
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
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
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

class GithubOAuthDeviceAuthRepository(
    private val client: OkHttpClient,
    clientId: String,
    scopes: Set<String> = DEFAULT_SCOPES,
    private val json: Json = Json { ignoreUnknownKeys = true },
    baseUrl: String = "https://github.com/login/",
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() }
) : GithubDeviceAuthRepository {
    private val normalizedClientId = clientId.trim()
    private val normalizedScopes = scopes.map(String::trim).filter(String::isNotEmpty).toSortedSet()
    private val loginBaseUrl = baseUrl.toHttpUrl()

    init {
        require(loginBaseUrl.isHttps || loginBaseUrl.host in LOCAL_TEST_HOSTS)
    }

    override val isConfigured: Boolean = isValidClientId(normalizedClientId)

    override suspend fun start(): Result<GithubDeviceAuthorization> = withContext(Dispatchers.IO) {
        githubRunCatching {
            if (!isConfigured) throw GithubDeviceFlowNotConfiguredException()
            val body = FormBody.Builder()
                .add("client_id", normalizedClientId)
                .apply {
                    if (normalizedScopes.isNotEmpty()) add("scope", normalizedScopes.joinToString(" "))
                }
                .build()
            val request = oauthRequest("device/code").post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw GithubApiException.of(response)
                val payload = json.decodeFromString(
                    DeviceCodeResponse.serializer(),
                    response.body?.string().orEmpty()
                )
                payload.toDomain(nowEpochMillis(), loginBaseUrl.host)
            }
        }
    }

    override suspend fun poll(deviceCode: String): Result<GithubDevicePollResult> = withContext(Dispatchers.IO) {
        githubRunCatching {
            if (!isConfigured) throw GithubDeviceFlowNotConfiguredException()
            require(deviceCode.length in 20..255 && deviceCode.none(Char::isWhitespace))
            val body = FormBody.Builder()
                .add("client_id", normalizedClientId)
                .add("device_code", deviceCode)
                .add("grant_type", DEVICE_GRANT_TYPE)
                .build()
            val request = oauthRequest("oauth/access_token").post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw GithubApiException.of(response)
                json.decodeFromString(
                    AccessTokenResponse.serializer(),
                    response.body?.string().orEmpty()
                ).toDomain(nowEpochMillis())
            }
        }
    }

    private fun oauthRequest(path: String): Request.Builder = Request.Builder()
        .url(loginBaseUrl.resolve(path) ?: throw IllegalArgumentException("Invalid GitHub OAuth endpoint"))
        .header("Accept", "application/json")
        .header("User-Agent", "XJTUToolbox-Community")

    @Serializable
    private data class DeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        @SerialName("expires_in") val expiresIn: Int,
        val interval: Int = MINIMUM_INTERVAL_SECONDS
    ) {
        fun toDomain(nowEpochMillis: Long, expectedHost: String): GithubDeviceAuthorization {
            val verificationUrl = verificationUri.toHttpUrlOrNull()
            if (
                deviceCode.length !in 20..255 ||
                deviceCode.any(Char::isWhitespace) ||
                userCode.length !in 4..32 ||
                userCode.any(Char::isWhitespace) ||
                verificationUrl == null ||
                !verificationUrl.isHttps ||
                !isTrustedVerificationHost(verificationUrl.host, expectedHost) ||
                expiresIn !in 60..86_400 ||
                interval !in 1..300
            ) {
                throw GithubDeviceFlowProtocolException("invalid_device_response")
            }
            return GithubDeviceAuthorization(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verificationUrl.toString(),
                expiresAtEpochMillis = nowEpochMillis + expiresIn * 1_000L,
                intervalSeconds = interval.coerceAtLeast(MINIMUM_INTERVAL_SECONDS)
            )
        }
    }

    @Serializable
    private data class AccessTokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("token_type") val tokenType: String? = null,
        val scope: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("refresh_token_expires_in") val refreshExpiresIn: Long? = null,
        val error: String? = null
    ) {
        fun toDomain(now: Long): GithubDevicePollResult {
            if (!accessToken.isNullOrBlank()) {
                val normalizedTokenType = tokenType
                    ?.takeIf { it.equals("bearer", ignoreCase = true) }
                    ?: throw GithubDeviceFlowProtocolException("invalid_token_type")
                return GithubDevicePollResult.Authorized(
                    GithubDeviceAccessToken(
                        accessToken = accessToken,
                        tokenType = normalizedTokenType,
                        refreshToken = validateGithubRefreshToken(refreshToken),
                        expiresAtEpochMillis = githubTokenExpiry(now, expiresIn),
                        refreshExpiresAtEpochMillis = githubTokenExpiry(now, refreshExpiresIn),
                        scopes = scope.orEmpty()
                            .split(',', ' ')
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .toSet()
                    )
                )
            }
            return when (error) {
                "authorization_pending" -> GithubDevicePollResult.Pending
                "slow_down" -> GithubDevicePollResult.SlowDown
                "expired_token" -> GithubDevicePollResult.Expired
                "access_denied" -> GithubDevicePollResult.Denied
                else -> throw GithubDeviceFlowProtocolException(error ?: "invalid_token_response")
            }
        }
    }

    private companion object {
        const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        const val MINIMUM_INTERVAL_SECONDS = 5
        // GitHub App 不看 scope，权限由 App 设置决定
        val DEFAULT_SCOPES = emptySet<String>()
        val LOCAL_TEST_HOSTS = setOf("localhost", "127.0.0.1")

        /**
         * MockWebServer may expose either loopback spelling depending on the
         * JDK/network stack. Treat those two spellings as the same host only
         * for local test endpoints; production OAuth hosts still require an
         * exact match with the configured GitHub login host.
         */
        fun isTrustedVerificationHost(actual: String, expected: String): Boolean =
            actual == expected || (actual in LOCAL_TEST_HOSTS && expected in LOCAL_TEST_HOSTS)

        fun isValidClientId(value: String): Boolean =
            value.length in 10..255 && value.none { it.isWhitespace() || it.isISOControl() }
    }
}

/** Missing expiry denotes a non-expiring OAuth token, never an already expired token. */
internal fun githubTokenExpiry(now: Long, seconds: Long?): Long? {
    if (seconds == null) return null
    if (now < 0 || seconds <= 0 || seconds > (Long.MAX_VALUE - now) / 1000) {
        throw GithubDeviceFlowProtocolException("invalid_token_expiry")
    }
    return now + seconds * 1000
}

internal fun validateGithubRefreshToken(token: String?): String? {
    if (token != null && (token.length !in 20..255 || token.any { it.isWhitespace() || it.isISOControl() })) {
        throw GithubDeviceFlowProtocolException("invalid_refresh_token")
    }
    return token
}
