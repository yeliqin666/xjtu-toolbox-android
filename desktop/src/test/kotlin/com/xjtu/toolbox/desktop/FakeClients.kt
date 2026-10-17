package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.faculty.FacultyFixture
import com.xjtu.toolbox.yellowpage.YellowPageFixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** 桌面端 test 源集里的假上游客户端（`:desktop:test` 与 `renderScreens` 共用一份）。 */

/** 黄页：`YellowPageApi` 收一个 Ktor `HttpClient` ⇒ 用 `MockEngine` 把两条响应喂进去。 */
internal fun mockYellowPageClient(): HttpClient = createToolboxClient(
    engine = MockEngine { request ->
        respond(
            content = YellowPageFixture.bodyFor(request.url.toString()),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    },
)

/** 教师检索：`FacultyApi` 收一个 `OkHttpClient` ⇒ 一条拦截器按路径分派夹具。 */
internal fun mockFacultyClient(): OkHttpClient {
    // 进程级前置：理由见 [installFakeUpstreamTls]。必须先于下面这个客户端建出来。
    installFakeUpstreamTls()
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

/** 进程级前置（幂等）：把假上游那枚自签 https 证书装进信任库。 */
private fun installFakeUpstreamTls() {
    FakeCampusProxy.installFakeUpstreams()
}
