package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.community.CommunityFakeUpstream
import com.xjtu.toolbox.community.CommunityRepo
import com.xjtu.toolbox.community.GithubDevicePollResult
import com.xjtu.toolbox.community.GithubNetwork
import com.xjtu.toolbox.community.GithubOAuthDeviceAuthRepository
import com.xjtu.toolbox.community.HttpGithubDiscussionsRepository
import com.xjtu.toolbox.community.SecureGithubSession
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.secureKeyValueStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 社区（GitHub）在桌面端的装配验收：桌面用的是同一份 `:data` 实现。 */
class DesktopAuthCommunityJvmTest {

    private lateinit var fake: FakeCampusProxy

    private fun withFakeCampus(block: () -> Unit) {
        fake = FakeCampusProxy(casEnabled = false)

        dataRootOverride = Files.createTempDirectory("xjtu-desktop-community-test").toFile()
        // ⚠️ 只清键，不 `wipeSecureStore`：`SecureGithubSession` 单例记着那份存储实例。
        val store = secureKeyValueStore("github_community")
        store.remove("token")
        store.remove("login")

        FakeCampusProxy.installFakeUpstreams()
        fake.start()
        try {
            block()
        } finally {
            fake.close()
            SecureGithubSession.get().signOut()
            dataRootOverride = null
        }
    }

    @Test
    fun `设备码流程与取数都走本机令牌，登录态落在同一份存储里`() = withFakeCampus {
        // 这一行就是 `AppRoute.Community` 里那三件注入中的一件（client 与 client_id 都同一份）
        val deviceAuth = GithubOAuthDeviceAuthRepository(GithubNetwork.client, CommunityRepo.CLIENT_ID)
        assertTrue(deviceAuth.isConfigured, "仓库里那个 client_id 形状应当被判为已配置")

        val authorization = runBlocking { deviceAuth.start() }.getOrThrow()
        assertEquals(CommunityFakeUpstream.USER_CODE, authorization.userCode)
        assertEquals(CommunityRepo.CLIENT_ID, fake.community.lastDeviceCodeClientId, "打出去的 client_id 就是仓库里那个")

        // 夹具第一次回 authorization_pending、第二次给令牌（桌面这条链也走同一条轮询）
        assertTrue(runBlocking { deviceAuth.poll(authorization.deviceCode) }.getOrThrow() is GithubDevicePollResult.Pending)
        val token = (runBlocking { deviceAuth.poll(authorization.deviceCode) }.getOrThrow()
            as GithubDevicePollResult.Authorized).token
        assertEquals(CommunityFakeUpstream.ACCESS_TOKEN, token.accessToken)

        // 登录态：同一份 `SecureGithubSession`（存储名 `github_community`，键 `token` / `login`）
        val session = SecureGithubSession.get()
        assertEquals(CommunityFakeUpstream.VIEWER_LOGIN, runBlocking { session.signIn(token) }.getOrThrow())
        assertEquals(CommunityFakeUpstream.VIEWER_LOGIN, session.login.value)

        // 取数：屏用的是 `session.repository`（带本机令牌的那一份）
        val page = runBlocking {
            session.repository.list(CommunityRepo.OWNER, CommunityRepo.NAME, cursor = null)
        }.getOrThrow()
        // 列表 = 置顶那条 + 普通那条（`list` 把 pinned 并进同一页里，屏上分组显示）
        assertEquals(2, page.items.size, "置顶一条 + 普通一条")
        val normal = page.items.first { it.title == CommunityFakeUpstream.DISCUSSION_TITLE }
        assertTrue(page.items.first { it.title == CommunityFakeUpstream.PINNED_TITLE }.pinned, "置顶那条要标出来")
        assertEquals(CommunityFakeUpstream.CATEGORY_NAME, normal.category.name)
        assertEquals(
            "Bearer ${CommunityFakeUpstream.ACCESS_TOKEN}",
            fake.community.lastGraphQlAuthorization,
            "GraphQL 要带本机令牌",
        )

        session.signOut()
        assertNull(session.login.value)
        assertTrue(secureKeyValueStore("github_community").getString("token") == null)
    }

    @Test
    fun `没有令牌时取数如实失败，不会静默给空列表`() = withFakeCampus {
        SecureGithubSession.get().signOut()
        val repo = HttpGithubDiscussionsRepository(token = { null })
        val result = runBlocking { repo.viewerLogin() }
        assertTrue(result.isFailure, "没令牌时应当失败（不是空列表）")
        // 连请求都不该发出去：共享层在拿不到令牌时先抛 `GithubSignedOutException`
        assertEquals(0, fake.community.graphQlPosts.get())
    }
}
