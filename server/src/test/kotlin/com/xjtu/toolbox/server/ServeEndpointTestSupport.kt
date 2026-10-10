package com.xjtu.toolbox.server

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.faculty.FacultyFixture
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.yellowpage.YellowPageFixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * P0 取数端点组一的**契约测试脚手架**（`:server:test`）：起真 serve（端口 0）+ 令牌闸门 +
 * 各测试把要验的 Route 扩展挂进 `route("/api")`，用 JDK 的 `java.net.http` 打真请求。
 *
 * 与 [ServeServerTest] / [ServeSessionTest] 同一条口径：
 * - **真 socket**（CIO + JDK 客户端），不做内存引擎；
 * - 端口 0（系统挑），绝不抢 8123（那是用户在用的 serve-same-origin.py）；
 * - 数据根指到临时目录（`dataRootOverride`），绝不碰 `~/.local/share/xjtu-toolbox/` 里那份真的；
 * - 会话/客户端是**进程级懒加载**的（`HttpClients.base` 建出来的那一刻把当时的 ProxySelector
 *   与信任库抄走）⇒ [withFakeProxy] 必须在**建任何源 / 会话之前**先把假上游装好。
 *
 * 取数实现怎么注入：[ServeEndpointRoutes] 的每个域都有"源 = 参数 + 默认值"这一层，
 * 测试把假客户端从参数塞进来 —— 黄页用 `MockEngine`（[mockYellowPageClient]）、
 * 教师检索用 okhttp 拦截器（[mockFacultyClient]）、其余走假上游代理（[FakeCampusProxy]）。
 */

/** 装好进程级假上游（selector + 信任库）并起假代理，跑 [block]。收尾关代理、复位数据根。 */
internal fun withFakeProxy(casEnabled: Boolean = true, block: (FakeCampusProxy) -> Unit) {
    // 两件进程级前置必须在碰 HttpClients / 建任何源之前（见 [FakeCampusProxy.installFakeUpstreams]）
    FakeCampusProxy.installFakeUpstreams()
    dataRootOverride = Files.createTempDirectory("xjtu-serve-endpoint").toFile()
    // 进程级态清一遍（与 :server 的 ServeSessionTest 同套卫生）：账号命名空间、凭据、cookie / 站点快照
    // 的文件级缓存都跨测试共享 —— 不清理的话同 JVM 里先跑的测试类会把「登录态」带进这一轮。
    wipeSharedServeState()
    val fake = FakeCampusProxy(casEnabled = casEnabled)
    fake.start()
    try {
        block(fake)
    } finally {
        fake.close()
        dataRootOverride = null
    }
}

/** 清一遍进程级的会话态（照 [ServeSessionTest.wipeStoredSession] 的口径 + 收纳的内存存储）。 */
private fun wipeSharedServeState() {
    val suffix = AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
    AccountContext.activeAccountId = null
    AccountContext.activeAccountId = null
    for (base in listOf("cookies_normal", "cookies_webvpn", "sites_normal", "sites_webvpn")) {
        wipeSecureStore("${base}_default")
        wipeSecureStore("$base$suffix")
    }
    wipeSecureStore(JvmCredentialStore.FILE_NAME)
    for (name in listOf(
        "cookies_normal_default",
        "cookies_webvpn_default",
        "cookies_normal$suffix",
        "cookies_webvpn$suffix",
    )) {
        PersistentCookieJar(name).clear()
    }
    // 收纳按账号分的内存 keyValueStore（JVM 上是进程级内存实现），文件根换了也要清内存表。
    for (name in listOf("inbox_default", "inbox$suffix")) {
        runCatching { keyValueStore(name).clear() }
    }
}

/**
 * 起一个真 serve 实例的 HTTP 外壳（内容协商 + `route("/api")` + 令牌闸门 + [routes]），
 * 端口 0（系统挑）。返回 (起好的 server, 真 HTTP 客户端)。
 */
internal fun startEndpointServer(
    token: String,
    routes: Route.() -> Unit,
): Pair<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>, EndpointHarness> {
    val server = embeddedServer(io.ktor.server.cio.CIO, port = 0, host = "127.0.0.1") {
        install(ContentNegotiation) { json(ApiJson) }
        routing {
            route(API_PREFIX) {
                install(accessTokenGate(token))
                routes()
            }
        }
    }
    server.start(wait = false)
    val connector = runBlocking { server.engine.resolvedConnectors().single() }
    return server to EndpointHarness(connector.host, connector.port, token)
}

/** 真 HTTP 客户端（JDK 自带；默认带令牌，`bearer = null` 表示「不带 / 只带 cookie」）。 */
internal class EndpointHarness(private val host: String, private val port: Int, private val token: String) {

    private val client: java.net.http.HttpClient = java.net.http.HttpClient.newBuilder()
        // 明文 HTTP 上不必去试 h2c 升级：CIO 只讲 HTTP/1.1
        .version(java.net.http.HttpClient.Version.HTTP_1_1)
        // ⚠️ 必须显式「不走代理」：假上游装的是进程级 ProxySelector，它把每个连接都指到假代理
        .proxy(ProxySelector.of(null))
        .build()

    fun get(path: String, bearer: String? = token, cookie: String? = null): HttpResponse<String> =
        client.send(request(path, bearer, cookie).GET().build(), HttpResponse.BodyHandlers.ofString())

    fun postJson(path: String, body: String, bearer: String? = token): HttpResponse<String> =
        client.send(
            request(path, bearer, null)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun request(path: String, bearer: String?, cookie: String?): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("http://$host:$port$path")).apply {
            bearer?.let { header("Authorization", "Bearer $it") }
            cookie?.let { header("Cookie", "${AccessToken.COOKIE_NAME}=$it") }
        }
}

/** 黄页：`YellowPageApi` 收 Ktor 客户端 ⇒ `MockEngine` 喂两条夹具响应（与 `:desktop:test` 同款）。 */
internal fun mockYellowPageClient(): HttpClient = createToolboxClient(
    engine = MockEngine { request ->
        respond(
            content = YellowPageFixture.bodyFor(request.url.toString()),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    },
)

/** 教师检索：`FacultyApi` 收 OkHttp 客户端 ⇒ 拦截器按路径分派夹具（与 `:desktop:test` 同款）。 */
internal fun mockFacultyClient(): OkHttpClient {
    FakeCampusProxy.installFakeUpstreams()
    return OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val body = when {
                url.encodedPath == FacultyFixture.SEARCH_PATH && url.queryParameter("showlang") == "en" ->
                    FacultyFixture.searchJsonEn
                url.encodedPath == FacultyFixture.SEARCH_PATH -> FacultyFixture.searchJson
                url.encodedPath == FacultyFixture.FILTER_PAGE_PATH -> FacultyFixture.searchJspHtml
                else -> "<html><head><title>error</title></head><body>假教师检索没有这条路径：$url</body></html>"
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
        .build()
}

// ── 响应体按 JSON 读的小工具（本组测试到处用）──────────────────────────────

internal fun String.parseJsonObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

internal fun HttpResponse<String>.envelope(): JsonObject = body().parseJsonObject()

/** 信封的 `code`（= HTTP 状态码那一套，见 [ApiErrors]）。 */
internal fun envelopeCode(response: HttpResponse<String>): Int =
    response.envelope().getValue("code").jsonPrimitive.content.toInt()

/** 信封的 `data`（有错误时没有 → null）。 */
internal fun envelopeData(response: HttpResponse<String>): JsonObject =
    response.envelope().getValue("data").jsonObject

internal fun envelopeMessage(response: HttpResponse<String>): String? =
    (response.envelope()["message"] as? JsonPrimitive)?.content

/** 见 [JsonNull]：`null` 写成字段值时读出来是 null 而不是缺失。 */
internal fun JsonObject.optString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.arrOf(key: String): JsonArray = getValue(key).jsonArray
internal fun JsonObject.optBoolean(key: String): Boolean? =
    optString(key)?.toBooleanStrictOrNull()

internal fun JsonObject.optInt(key: String): Int? = optString(key)?.toIntOrNull()

internal fun JsonObject.optLong(key: String): Long? = optString(key)?.toLongOrNull()

/**
 * §3.4 那条红线的实现：响应体**不许出现**登录用户本人相关的身份。
 *
 * 允许的例外（新契约明确要求投影本人数据/公开课堂信息的端点）：`/api/fitness/score` 的学号姓名
 * 与 `/api/emptyroom/rooms?source=live` 的教师名 —— 那两条的断言在各自测试里单独写
 * （expected 值来自夹具的编造常量，不是真人）。
 */
internal fun assertNoIdentity(body: String, where: String, forbidden: List<String>) {
    for (needle in forbidden) {
        if (needle.isBlank()) continue
        assert(!body.contains(needle)) { "$where 的响应体里不该出现「$needle」" }
    }
}