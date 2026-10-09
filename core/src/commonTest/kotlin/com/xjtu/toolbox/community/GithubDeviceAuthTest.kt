package com.xjtu.toolbox.community

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设备码登录里**与 HTTP 无关**的那一半（随 `GithubDeviceAuth` 搬进 :core）的测试。
 *
 * 这些断言以前不存在：那份实现（改编自 Etoile）本来就是可注入的 —— `nowEpochMillis` 与
 * `delayMillis` 都是构造参数，所以「轮询 → 慢下来 → 授权」「超时」「被拒」这几条路径可以
 * 拿假时钟一次跑完，不必真的等。搬迁时把默认时钟从 `System.currentTimeMillis()` 换成
 * `Clock.System.now().toEpochMilliseconds()`，这几条断言正是「换时钟没换语义」的凭据。
 */
class GithubDeviceAuthTest {

    private class FakeRepo(
        private val results: List<GithubDevicePollResult>,
    ) : GithubDeviceAuthRepository {
        override val isConfigured = true
        var polls = 0
            private set
        var lastDeviceCode: String? = null
            private set

        override suspend fun start(): Result<GithubDeviceAuthorization> =
            Result.failure(IllegalStateException("not used in these tests"))

        override suspend fun poll(deviceCode: String): Result<GithubDevicePollResult> {
            lastDeviceCode = deviceCode
            val result = results[minOf(polls, results.lastIndex)]
            polls++
            return Result.success(result)
        }
    }

    private class FailingRepo : GithubDeviceAuthRepository {
        override val isConfigured = true
        override suspend fun start(): Result<GithubDeviceAuthorization> =
            Result.failure(IllegalStateException("boom"))
        override suspend fun poll(deviceCode: String): Result<GithubDevicePollResult> =
            Result.failure(IllegalStateException("boom"))
    }

    private val token = GithubDeviceAccessToken(
        accessToken = "ghu_".padEnd(40, 'x'),
        tokenType = "bearer",
        scopes = setOf(""),
    )

    private fun authorization(now: Long, expiresInMillis: Long = 60_000, intervalSeconds: Int = 5) =
        GithubDeviceAuthorization(
            deviceCode = "d".repeat(24),
            userCode = "ABCD-1234",
            verificationUri = "https://github.com/login/device",
            expiresAtEpochMillis = now + expiresInMillis,
            intervalSeconds = intervalSeconds,
        )

    /** 每次 delay 都把假时钟推着走，并记下间隔；返回用例与记录器。 */
    private fun harness(results: List<GithubDevicePollResult>, start: Long = 1_000L): Triple<AwaitGithubDeviceAuthorizationUseCase, FakeRepo, MutableList<Long>> {
        var now = start
        val delays = mutableListOf<Long>()
        val repo = FakeRepo(results)
        val useCase = AwaitGithubDeviceAuthorizationUseCase(
            repository = repo,
            nowEpochMillis = { now },
            delayMillis = { delays += it; now += it },
        )
        return Triple(useCase, repo, delays)
    }

    @Test
    fun `先 Pending 再 Authorized：按 interval 轮询，授权即返回 token`() = runTest {
        val (useCase, repo, delays) = harness(listOf(GithubDevicePollResult.Pending, GithubDevicePollResult.Authorized(token)))
        val result = useCase(authorization(1_000L))
        assertTrue(result.isSuccess)
        assertEquals(token, result.getOrNull())
        assertEquals(listOf(5_000L, 5_000L), delays)
        assertEquals(2, repo.polls)
        assertEquals("d".repeat(24), repo.lastDeviceCode)
    }

    @Test
    fun `SlowDown 把间隔加 5 秒，下一次 delay 用新间隔`() = runTest {
        val (useCase, _, delays) = harness(
            listOf(GithubDevicePollResult.Pending, GithubDevicePollResult.SlowDown, GithubDevicePollResult.Authorized(token)),
        )
        assertTrue(useCase(authorization(1_000L)).isSuccess)
        assertEquals(listOf(5_000L, 5_000L, 10_000L), delays)
    }

    @Test
    fun `interval 低于下限时按 5 秒起算`() = runTest {
        val (useCase, _, delays) = harness(listOf(GithubDevicePollResult.Authorized(token)))
        assertTrue(useCase(authorization(1_000L, intervalSeconds = 1)).isSuccess)
        assertEquals(listOf(5_000L), delays)
    }

    @Test
    fun `码过期：时钟越过有效期就不再轮询`() = runTest {
        val (useCase, repo, delays) = harness(listOf(GithubDevicePollResult.Pending))
        val result = useCase(authorization(1_000L, expiresInMillis = 3_000))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GithubDeviceAuthorizationExpiredException)
        assertEquals(listOf(5_000L), delays)
        assertEquals(0, repo.polls)
    }

    @Test
    fun `GitHub 说 expired 或 denied 时返回对应的失败`() = runTest {
        val (expired, _, _) = harness(listOf(GithubDevicePollResult.Expired))
        assertTrue(expired(authorization(1_000L)).exceptionOrNull() is GithubDeviceAuthorizationExpiredException)
        val (denied, _, _) = harness(listOf(GithubDevicePollResult.Denied))
        assertTrue(denied(authorization(1_000L)).exceptionOrNull() is GithubDeviceAuthorizationDeniedException)
    }

    @Test
    fun `轮询本身失败时把失败原样返回，不再重试`() = runTest {
        val useCase = AwaitGithubDeviceAuthorizationUseCase(
            repository = FailingRepo(),
            nowEpochMillis = { 1_000L },
            delayMillis = { },
        )
        val result = useCase(authorization(1_000L))
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    // ── 两个 token 校验函数（:app 的 okhttp 实现也用它们）──

    @Test
    fun `没有过期时间就是永不过期，非法秒数一律拒绝`() = runTest {
        assertNull(githubTokenExpiry(1_000L, null))
        assertEquals(61_000L, githubTokenExpiry(1_000L, 60))
        assertFailsWith<GithubDeviceFlowProtocolException> { githubTokenExpiry(1_000L, 0) }
        assertFailsWith<GithubDeviceFlowProtocolException> { githubTokenExpiry(-1L, 60) }
        // 溢出保护：秒数大到会溢出 Long 时必须抛，而不是回绕成过去的时刻
        assertFailsWith<GithubDeviceFlowProtocolException> { githubTokenExpiry(Long.MAX_VALUE - 1, 60) }
    }

    @Test
    fun `刷新令牌的长度与字符要合法`() = runTest {
        assertNull(validateGithubRefreshToken(null))
        val good = "r".repeat(40)
        assertEquals(good, validateGithubRefreshToken(good))
        assertFailsWith<GithubDeviceFlowProtocolException> { validateGithubRefreshToken("short") }
        assertFailsWith<GithubDeviceFlowProtocolException> { validateGithubRefreshToken("r".repeat(20) + " ") }
    }

    /** 安全相关：这两个数据类的 `toString` 反复被日志/崩溃上报碰，绝不能带出 token。 */
    @Test
    fun `toString 不泄露 token`() = runTest {
        val access = token
        assertFalse(access.toString().contains(access.accessToken))
        assertTrue(access.toString().contains("<redacted>"))
        val auth = authorization(0L)
        assertFalse(auth.toString().contains(auth.deviceCode))
        assertTrue(auth.toString().contains("<redacted>"))
    }
}
