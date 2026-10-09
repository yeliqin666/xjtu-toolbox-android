package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/data/GithubOAuthDeviceAuthRepository.kt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * [GithubDeviceAuthRepository] 的 okhttp 实现 —— **留在 :app**。
 *
 * 原来它和模型 / 用例同在一个 `GithubDeviceAuth.kt` 里；现在那一半（模型、结果、接口、用例、
 * 两个 token 校验函数）在 :core 的 `community/GithubDeviceAuth.kt`。这里保存的是真正碰
 * okhttp 的部分：两个 OAuth 端点、以及 GitHub 那两个报文的形状校验。
 *
 * 校验逻辑（`toDomain` 里的长度/主机/区间检查）**一行未动** —— 它挡的是畸形的 OAuth 报文，
 * 换库也不该放松。
 */
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
                if (!response.isSuccessful) throw githubApiExceptionOf(response)
                val payload = json.decodeFromString(
                    DeviceCodeResponse.serializer(),
                    response.body.string()
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
                if (!response.isSuccessful) throw githubApiExceptionOf(response)
                json.decodeFromString(
                    AccessTokenResponse.serializer(),
                    response.body.string()
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
