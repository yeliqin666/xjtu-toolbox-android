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

/**
 * 社区（GitHub）那条链的**字段级口径**：设备码登录（`GithubOAuthDeviceAuthRepository`）、
 * 登录态落盘（`SecureGithubSession`）与 GraphQL 取数（`HttpGithubDiscussionsRepository`）——
 * 都对着 `:testkit` 的 [CommunityFakeUpstream] 逐行读。
 *
 * ## 为什么在 `:data` 而不是 `:app`
 *
 * 第 21 条真数据路由把社区那三件（设备码 okhttp、GraphQL 传输、登录态存储）从 `:app` 搬进了
 * `:data`。搬之前先用夹具把**搬之前**的口径钉住：
 *
 *  - `PrefsGithubSession` 的两个键名与存储名（`github_community` / `token` / `login`）**逐字不变**
 *    ⇒ 老登录态无缝沿用（Android 的 actual 仍是同一个 `SecurePrefs.open`）；
 *  - `signIn` 先用 token 查一次用户名、**查得到才落盘**（查不到就落盘只会让界面以为已登录、
 *    随后每个请求都 401）；`signOut` 只清本机（GitHub 那边的授权要用户自己去撤销）；
 *  - 设备码报文那套长度/主机/区间校验、以及「未授权完不算登录」的 `authorization_pending` 分支。
 *
 * ## 进程级的那个坑
 *
 * `secureKeyValueStore` 是**进程级**缓存的（Android 上正是靠这一点让前台/后台看到同一份登录态），
 * 而 `SecureGithubSession.get()` 是进程级单例 ⇒ 每个用例前后都要把那两个键清掉，
 * 否则上一条用例的「已登录」会漏进下一条。
 */
class GithubCommunityJvmTest {

    /**
     * 直接照**搬迁前那个存储名**开一份（`PrefsGithubSession` 的 `SecurePrefs.open(context,
     * "github_community")`）—— 刻意写字符串而不是引用实现里的常量：`SecureGithubSession`
     * 若把存储名改掉，老登录态就读不出来了，这条断言就是拦它的。
     */
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

        // 落盘的键与搬迁前逐字相同（`github_community` 那份存储里的 `token` / `login`）
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
