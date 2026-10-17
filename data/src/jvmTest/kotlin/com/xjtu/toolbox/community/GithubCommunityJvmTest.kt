package com.xjtu.toolbox.community

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.secureKeyValueStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 社区（GitHub）那条链的字段级口径。 */
class GithubCommunityJvmTest {

    /** 按老存储名 `github_community` 直接开一份：刻意写字符串而不引用常量，存储名被改时这条断言会拦住。 */
    private val store = secureKeyValueStore("github_community")

    private lateinit var fake: FakeCampusProxy

    @BeforeTest
    fun setUp() {
        dataRootOverride = Files.createTempDirectory("xjtu-community-test").toFile()
        // 单例与存储都是进程级的：先清干净，再装那两件进程级前置（必须在碰 HttpClients 之前）
        store.remove("token")
        store.remove("login")
        FakeCampusProxy.installFakeUpstreams()
        fake = FakeCampusProxy().start()
    }

    @AfterTest
    fun tearDown() {
        fake.close()
        SecureGithubSession.get().signOut()
        dataRootOverride = null
    }

    @Test
    fun `设备码：start 拿码，poll 先 pending 后拿令牌（报文校验按实现里的判据）`() {
        val repo = GithubOAuthDeviceAuthRepository(
            client = GithubNetwork.client,
            clientId = "Iv23liFixtureClientId",
        )
        assertTrue(repo.isConfigured, "夹具的 client_id 形状应当被判为已配置")

        val authorization = runBlocking { repo.start() }.getOrThrow()
        assertEquals(CommunityFakeUpstream.DEVICE_CODE, authorization.deviceCode)
        assertEquals(CommunityFakeUpstream.USER_CODE, authorization.userCode)
        assertEquals(CommunityFakeUpstream.VERIFICATION_URI, authorization.verificationUri)
        assertEquals(CommunityFakeUpstream.INTERVAL, authorization.intervalSeconds)
        assertEquals("Iv23liFixtureClientId", fake.community.lastDeviceCodeClientId)

        // 第一次轮询：夹具回 authorization_pending ⇒ 还没授权（不是失败，也不是已授权）
        val first = runBlocking { repo.poll(authorization.deviceCode) }.getOrThrow()
        assertTrue(first is GithubDevicePollResult.Pending, "第一次应当是 Pending，实际 $first")

        val second = runBlocking { repo.poll(authorization.deviceCode) }.getOrThrow()
        val authorized = second as? GithubDevicePollResult.Authorized
            ?: error("第二次应当是 Authorized，实际 $second")
        assertEquals(CommunityFakeUpstream.ACCESS_TOKEN, authorized.token.accessToken)
        assertEquals("bearer", authorized.token.tokenType)
        assertEquals(2, fake.community.tokenPolls.get())
    }

    @Test
    fun `设备码：畸形报文（expires_in 越界）与 access_denied 都如实失败`() {
        val repo = GithubOAuthDeviceAuthRepository(GithubNetwork.client, "Iv23liFixtureClientId")

        fake.community.malformedDeviceResponse = true
        val malformed = runBlocking { repo.start() }
        assertTrue(malformed.isFailure, "越界的 expires_in 应当被判成畸形报文")

        fake.community.malformedDeviceResponse = false
        fake.community.denied = true
        val code = runBlocking { repo.start() }.getOrThrow().deviceCode
        val denied = runBlocking { repo.poll(code) }.getOrThrow()
        assertTrue(denied is GithubDevicePollResult.Denied, "access_denied ⇒ Denied，实际 $denied")
    }

    @Test
    fun `登录态：signIn 先查用户名再落盘（同一个存储名与键），signOut 只清本机`() {
        val session = SecureGithubSession.get()
        session.signOut()
        assertNull(session.login.value, "开头应当是未登录")

        val token = GithubDeviceAccessToken(
            accessToken = CommunityFakeUpstream.ACCESS_TOKEN,
            tokenType = "bearer",
            scopes = emptySet(),
        )
        val name = runBlocking { session.signIn(token) }.getOrThrow()
        assertEquals(CommunityFakeUpstream.VIEWER_LOGIN, name)
        assertEquals(name, session.login.value)

        assertEquals(CommunityFakeUpstream.ACCESS_TOKEN, store.getString("token"))
        assertEquals(CommunityFakeUpstream.VIEWER_LOGIN, store.getString("login"))

        session.signOut()
        assertNull(session.login.value)
        assertNull(store.getString("token"), "退出登录要清本机 token")
        assertNull(store.getString("login"))
    }

    @Test
    fun `取数：GraphQL 带 Bearer 令牌，viewer 与 Discussions 列表按夹具字段读`() {
        val repo = HttpGithubDiscussionsRepository(token = { CommunityFakeUpstream.ACCESS_TOKEN })

        val login = runBlocking { repo.viewerLogin() }.getOrThrow()
        assertEquals(CommunityFakeUpstream.VIEWER_LOGIN, login)
        assertEquals(
            "Bearer ${CommunityFakeUpstream.ACCESS_TOKEN}",
            fake.community.lastGraphQlAuthorization,
            "每次 GraphQL 都要带本机令牌",
        )

        val page = runBlocking { repo.list(CommunityRepo.OWNER, CommunityRepo.NAME, cursor = null) }.getOrThrow()
        val titles = page.items.map { it.title }
        assertTrue(CommunityFakeUpstream.DISCUSSION_TITLE in titles, "列表里应当有夹具那条帖子")
        val pinned = page.items.first { it.title == CommunityFakeUpstream.PINNED_TITLE }
        assertTrue(pinned.pinned, "置顶那条要标出来")
        val normal = page.items.first { it.title == CommunityFakeUpstream.DISCUSSION_TITLE }
        assertEquals(CommunityFakeUpstream.CATEGORY_NAME, normal.category.name)
        assertEquals(CommunityFakeUpstream.DISCUSSION_COMMENTS, normal.comments)
        assertEquals(CommunityFakeUpstream.DISCUSSION_AUTHOR, normal.author)
        assertNull(page.nextCursor, "夹具只有一页")
    }
}
