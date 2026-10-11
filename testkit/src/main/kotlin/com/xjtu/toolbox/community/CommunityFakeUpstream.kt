package com.xjtu.toolbox.community

import com.sun.net.httpserver.HttpExchange
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的 **GitHub**（第 21 条真数据路由：社区三屏的取数与登录都在 `api.github.com` +
 * `github.com/login` 上）。
 *
 * ## 它扮的是哪条链
 *
 * 与 [`GithubOAuthDeviceAuthRepository`](com.xjtu.toolbox.community.GithubOAuthDeviceAuthRepository) /
 * [`HttpGithubDiscussionsRepository`](com.xjtu.toolbox.community.HttpGithubDiscussionsRepository) /
 * [`SecureGithubSession`](com.xjtu.toolbox.community.SecureGithubSession) 一起，把**搬进 `:data`
 * 之后**的设备码登录与取数原样演一遍：
 *
 * ```
 * POST https://github.com/login/device/code        （form: client_id=…）
 *      → {"device_code":"…","user_code":"…","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}
 * POST https://github.com/login/oauth/access_token （form: client_id/device_code/grant_type）
 *      → 前 [pendingPolls] 次回 {"error":"authorization_pending"}，之后回 {"access_token":"…","token_type":"bearer","scope":"…"}
 * POST https://api.github.com/graphql              （body: {"query":"query{viewer{login}}",…}）
 *      → {"data":{"viewer":{"login":"…"}}}
 * POST https://api.github.com/graphql              （body 里是 `repository(owner:…,name:…){…discussions…}`）
 *      → {"data":{"repository":{"id":…,"pinnedDiscussions":{…},"discussions":{…}}}}
 * ```
 *
 * 真站点那两个 host 都是 https，所以两个都在 `FakeCampusProxy.HTTPS_HOSTS` 里（要隧道）；
 * `verification_uri` 必须是 https 且 host 与配置的登录 host 一致 —— `toDomain` 就是这么校验的，
 * 所以这里给的是 `https://github.com/login/device`（**不会被请求**：用户是自己在浏览器里打开的）。
 *
 * ## 报文校验照实现里的判据
 *
 * `GithubOAuthDeviceAuthRepository.toDomain` 对两个报文有一整套长度/主机/区间检查（设备码 20..255、
 * 用户码 4..32、`expires_in` 60..86400、`interval` 1..300、`token_type` 必须能被认成 bearer）。
 * 夹具给的样本**刻意卡在正中间**，任何一条判据收紧都会在这里响。
 *
 * ## 夹具值全是编造的
 *
 * 用户名 `fixture-user`、帖子标题「示例讨论：桌面端怎么用」（假）、分类「Ideas」—— 不是真账号真帖子。
 */
class CommunityFakeUpstream {

    companion object {
        /** 设备码与令牌端点那个 host。 */
        const val LOGIN_HOST = "github.com"

        /** GraphQL 那个 host。 */
        const val API_HOST = "api.github.com"

        /** `GithubOAuthDeviceAuthRepository` 的默认 `baseUrl`（逐字相同，有断言钉着）。 */
        const val LOGIN_BASE = "https://$LOGIN_HOST/login/"

        const val DEVICE_CODE_PATH = "/login/device/code"
        const val ACCESS_TOKEN_PATH = "/login/oauth/access_token"

        /** GraphQL 端点（`HttpGithubDiscussionsRepository` 的默认 `endpoint`）。 */
        const val GRAPHQL_PATH = "/graphql"
        const val GRAPHQL_URL = "https://$API_HOST$GRAPHQL_PATH"

        /** 用户码（`user_code`）里那个验证页 —— 只出现在报文里，客户端不会请求它。 */
        const val VERIFICATION_URI = "https://$LOGIN_HOST/login/device"

        const val DEVICE_CODE = "fixture-device-code-0123456789abcdef"  // 32 字符，落在 20..255
        const val USER_CODE = "FIXT-1234"
        const val EXPIRES_IN = 900
        const val INTERVAL = 5

        const val ACCESS_TOKEN = "gho_fixture_device_token"
        const val TOKEN_TYPE = "bearer"
        const val SCOPE = ""

        const val VIEWER_LOGIN = "fixture-user"

        // ── Discussions 样本 ──

        const val REPO_ID = "R_fixture_repo"
        const val CATEGORY_ID = "DIC_fixture_ideas"
        const val CATEGORY_NAME = "Ideas"
        const val DISCUSSION_ID = "D_fixture_1"
        const val DISCUSSION_NUMBER = 42
        const val DISCUSSION_TITLE = "示例讨论：桌面端怎么用（假）"
        const val DISCUSSION_BODY = "这是一条夹具帖子，不是真讨论。"
        const val DISCUSSION_AUTHOR = VIEWER_LOGIN
        const val DISCUSSION_COMMENTS = 3
        const val DISCUSSION_URL = "https://github.com/yeliqin666/xjtu-toolbox-android/discussions/42"
        const val DISCUSSION_CREATED_AT = "2026-05-01T09:00:00Z"
        const val DISCUSSION_UPDATED_AT = "2026-05-02T10:00:00Z"
        const val PINNED_NUMBER = 7
        const val PINNED_ID = "D_fixture_pinned"
        const val PINNED_TITLE = "示例置顶：使用前先读这条（假）"
    }

    // ── 断言用：计数与最近参数 ──

    val deviceCodePosts = AtomicInteger(0)
    val tokenPolls = AtomicInteger(0)
    val graphQlPosts = AtomicInteger(0)

    /** 最近一次 GraphQL 请求带的 `Authorization`（钉「带 token 的实现真的加头」）。 */
    var lastGraphQlAuthorization: String? = null
        private set

    /** 最近一次 GraphQL 查询原文（钉「查询与解析只有一份」这件事：两个 host 各打了什么一眼可见）。 */
    var lastGraphQlQuery: String? = null
        private set

    /** 最近一次设备码请求带的 `client_id`。 */
    var lastDeviceCodeClientId: String? = null
        private set

    /** 前几次轮询先回 `authorization_pending`（默认一次），用来钉「没授权完不算登录」。 */
    @Volatile
    var pendingPolls = 1

    /** 打开后 `devices/code` 回一个畸形报文（`expires_in` 越界）—— 协议校验那条路的剧本。 */
    @Volatile
    var malformedDeviceResponse = false

    /** 打开后轮询一律回 `access_denied`。 */
    @Volatile
    var denied = false

    // ── 路由 ─────────────────────────────────────────────────────

    /** `github.com`：设备码两个端点。 */
    fun handleLogin(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val form = exchange.requestBody.readBytes().decodeToString()
        when (path) {
            DEVICE_CODE_PATH -> {
                lastDeviceCodeClientId = param(form, "client_id")
                deviceCodePosts.incrementAndGet()
                val expires = if (malformedDeviceResponse) 5 else EXPIRES_IN
                respondJson(
                    exchange,
                    """{"device_code":"$DEVICE_CODE","user_code":"$USER_CODE",""" +
                        """"verification_uri":"$VERIFICATION_URI","expires_in":$expires,"interval":$INTERVAL}""",
                )
            }
            ACCESS_TOKEN_PATH -> {
                tokenPolls.incrementAndGet()
                val n = tokenPolls.get()
                respondJson(
                    exchange,
                    when {
                        denied -> """{"error":"access_denied"}"""
                        n <= pendingPolls -> """{"error":"authorization_pending"}"""
                        else -> """{"access_token":"$ACCESS_TOKEN","token_type":"$TOKEN_TYPE","scope":"$SCOPE"}"""
                    },
                )
            }
            else -> notFound(exchange, path)
        }
    }

    /** `api.github.com`：GraphQL（`viewer{login}` 与 discussions 列表两条）。 */
    fun handleApi(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        if (path != GRAPHQL_PATH) {
            notFound(exchange, path)
            return
        }
        val body = exchange.requestBody.readBytes().decodeToString()
        graphQlPosts.incrementAndGet()
        lastGraphQlAuthorization = exchange.requestHeaders.getFirst("Authorization")
        lastGraphQlQuery = queryOf(body)
        val query = lastGraphQlQuery.orEmpty()
        when {
            "viewer{login}" in query -> respondJson(exchange, """{"data":{"viewer":{"login":"$VIEWER_LOGIN"}}}""")
            "discussions(" in query -> respondJson(exchange, discussionsJson(body))
            // 分类列表（列表页那个筛选器 + 发帖时选分类都要它）。
            "discussionCategories(" in query -> respondJson(
                exchange,
                """{"data":{"repository":{"discussionCategories":{"nodes":[""" +
                    """{"id":"$CATEGORY_ID","name":"$CATEGORY_NAME","isAnswerable":true}]}}}}""",
            )
            else -> respondJson(exchange, """{"errors":[{"message":"夹具没有这条查询：$query"}]}""")
        }
    }

    // ── 报文 ─────────────────────────────────────────────────────

    /**
     * 列表查询的响应。`pinned` 那一支只在第一页出现 —— 与查询里的 `@include(if:$top)` 对应；
     * 夹具按「**变量**里带 `"top":true` 才给置顶」来答（查询文本里只有 `@include(if:$top)`,
     * 真正决定要不要置顶的是 variables），免得客户端漏传 `top` 也看起来正常。
     */
    private fun discussionsJson(body: String): String {
        val withPinned = """"top":true""" in body || """"top": true""" in body
        val pinned = if (withPinned) {
            """"pinnedDiscussions":{"nodes":[{"discussion":${discussionJson(PINNED_ID, PINNED_NUMBER, PINNED_TITLE, pinned = true)}}]},"""
        } else {
            """"pinnedDiscussions":{"nodes":[]},"""
        }
        return """{"data":{"repository":{"id":"$REPO_ID",$pinned""" +
            """"discussions":{"nodes":[${discussionJson(DISCUSSION_ID, DISCUSSION_NUMBER, DISCUSSION_TITLE, pinned = false)}],""" +
            """"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}"""
    }

    private fun discussionJson(id: String, number: Int, title: String, pinned: Boolean): String = """
        {"id":"$id","number":$number,"title":"$title","body":"$DISCUSSION_BODY","url":"$DISCUSSION_URL",
         "createdAt":"$DISCUSSION_CREATED_AT","updatedAt":"$DISCUSSION_UPDATED_AT","closed":false,"stateReason":null,
         "locked":false,"viewerCanUpdate":false,"viewerCanDelete":false,"viewerCanClose":false,"viewerCanReopen":false,
         "authorAssociation":"MEMBER","repository":{"viewerPermission":"READ"},
         "author":{"login":"$DISCUSSION_AUTHOR","avatarUrl":null},
         "reactionGroups":[{"content":"THUMBS_UP","viewerHasReacted":false,"reactors":{"totalCount":${if (pinned) 2 else 0}}}],
         "poll":null,
         "category":{"id":"$CATEGORY_ID","name":"$CATEGORY_NAME","isAnswerable":true},
         "comments":{"totalCount":$DISCUSSION_COMMENTS},
         "answer":null}
    """.trimIndent()

    // ── 小工具 ───────────────────────────────────────────────────

    /** 从 GraphQL 报文里抠出 `query` 字段的原文（夹具按它分流；不做真正的 JSON 解析）。 */
    private fun queryOf(body: String): String? =
        Regex(""""query"\s*:\s*"(.*?)"\s*,\s*"variables"""").find(body)?.groupValues?.get(1)
            ?: Regex(""""query"\s*:\s*"(.*?)"\s*}""").find(body)?.groupValues?.get(1)

    private fun param(raw: String, name: String): String? = raw.split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    private fun respondJson(exchange: HttpExchange, json: String) {
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
        exchange.responseBody.write(json.toByteArray())
        exchange.close()
    }

    private fun notFound(exchange: HttpExchange, path: String) {
        val body = "夹具没有这条路径：$path".toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(404, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }
}
