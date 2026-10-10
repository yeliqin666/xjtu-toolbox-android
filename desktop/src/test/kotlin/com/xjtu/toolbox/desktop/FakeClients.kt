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

/**
 * 桌面端 test 源集里的**假上游客户端**（`:desktop:test` 与 `renderScreens` 共用一份）。
 *
 * ## 为什么这里有两种，而不是一种
 *
 * 取决于**取数端口收什么**（这条判据是黄页那一轮总结出来的，见交接文档 §2.11）：
 *
 * | 取数长什么样 | 假上游怎么搭 | 谁 |
 * |---|---|---|
 * | 吃**会话内核**（cookie / 302 / CAS） | 真 HTTP 服务器 + 代理（`:testkit` 的 `FakeCampusProxy`） | 图书馆、校历 |
 * | 收一个 **Ktor `HttpClient`** | Ktor 自己的 `MockEngine` | 黄页 |
 * | 收一个 **`OkHttpClient`** | 一条 okhttp **拦截器**（不起服务器、不起端口） | 教师检索 |
 *
 * 后两种都不走网络（端口在调用方一侧），压根用不上「真机地址」那条链路；真代理那条现在也支持
 * https 了：假上游会给 https 站点自签一枚证书、走 CONNECT 隧道（见 `:testkit` 的 `FakeUpstreamFront`）。
 *
 * 样本都在 `:testkit`（那边刻意只存「上游长什么样」，不引 ktor / okhttp 的引擎）。
 */

/**
 * 黄页：`YellowPageApi` 收一个 Ktor `HttpClient` ⇒ 用 `MockEngine` 把两条响应喂进去。
 * 认不出的地址由夹具 `error(...)` 抛出来（URL 漂了要响亮地红，而不是静默通过）。
 */
internal fun mockYellowPageClient(): HttpClient = createToolboxClient(
    engine = MockEngine { request ->
        respond(
            content = YellowPageFixture.bodyFor(request.url.toString()),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    },
)

/**
 * 教师检索：`FacultyApi` 收一个 `OkHttpClient` ⇒ 一条拦截器按路径分派夹具。
 *
 * 三处与真站点一致的细节（照 `:data:jvmTest` 的 `FacultyApiParsingTest` 那份分派写）：
 * 1. 响应头是 `text/html` —— 真站点给检索 JSON 打的就是这个头，而 `FacultyApi` 只按**响应体首字符**
 *    判是不是 JSON（`looksLikeJson`）；
 * 2. `showlang=en` 那条给英文补地址的样本（中文接口给部分老师的主页地址留空）；
 * 3. 认不出的路径（含 `gr.xjtu.edu.cn` 的教师主页）回一个**写着 URL 的错误页** ——
 *    夹具要是把地址搞错了，报错里能一眼看回是哪条 URL，而不是长得像解析器坏了。
 *    主页取不到会让详情页走 `HomepageResult.External` 降级（「在浏览器中打开」），
 *    这正是 :app 遇到非标准主页时的同一条退路 —— 列表页不受影响。
 */
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

/**
 * 进程级前置（幂等）：把假上游那枚自签 https 证书装进信任库。
 *
 * 为什么摆在**这个文件**里：`OkHttpClient.Builder.build()` 那一刻就把平台默认的 trust manager
 * 抄进客户端，而这里是 test 源集里唯一建 `OkHttpClient` 的地方 —— 放在这里，顺序就不再取决于
 * JUnit 先跑哪一条用例（教师检索那条用例不经过 `withFakeCampus`，但它照样会“抄”一份 trust manager；
 * JUnit 先跑哪一条用例（教师检索那条用例不经过 `withFakeCampus`，但它照样会「抄」一份 trust manager；
 * 机制详见 `:testkit` 的 `FakeUpstreamFront` 类 KDoc。
 */
private fun installFakeUpstreamTls() {
    FakeCampusProxy.installFakeUpstreams()
}
