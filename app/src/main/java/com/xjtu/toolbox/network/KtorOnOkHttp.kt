package com.xjtu.toolbox.network

import com.xjtu.toolbox.core.net.toolboxJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import okhttp3.OkHttpClient

/**
 * **「用 Ktor 的 API，跑同一套 okhttp 底座」** —— 把 okhttp → Ktor 这件事从「换传输层」降级成「换写法」。
 *
 * ## 为什么不换引擎
 *
 * 交接文档 §4 把第 1 步-B（okhttp → Ktor，约 78 个文件）标成「需要单独一轮」，理由是
 * `SiteSession.executeWithReAuth` + `HttpClients` + `PersistentCookieJar` + WebVPN 是整条会话内核，
 * 动它直接碰「Android 行为零变化」这条硬约束。
 *
 * 但实测下来 **不需要换引擎**：`ktor-client-okhttp` 的 OkHttp 引擎允许预置一个已建好的
 * `OkHttpClient`（`engine { preconfigured = … }`）。Ktor 只是在这上面构造 `Request`、把
 * `Response` 递回来 —— 于是下面这些东西**一个字节都没变**：
 *
 * - **cookie**：还是那个 `PersistentCookieJar`（okhttp 的 `CookieJar`），因为请求确实由那个 client 发出；
 * - **[HttpClients.base] 的拦截器**：没有自写 UA 的请求仍然被补上 [APP_UA]（统一认证的 TGC 绑定 UA，
 *   这条最要命，也由拦截器统一兜住，Ktor 侧不必重复实现）；
 * - **brotli / 超时 / 跟随跳转 / TLS / 连接池 / 调度线程**：都在那个 client 的配置里；
 * - **代理与 WebVPN**：同理，走的是同一个 client。
 *
 * 换句话说：**Android 上的网络栈一行没改**，改的只是「构造请求和读响应」的写法 ——
 * 而这才是有价值的那一半，因为 `okhttp3.Request/Response` 这些类型在 wasm/iOS 上不存在，
 * 换掉它们，业务代码才能搬进 `:core`（Web 端用 Ktor 的 JS 引擎，同一份业务代码）。
 *
 * ## 用法
 *
 * ```kotlin
 * val ktor = site.client.asToolboxKtorClient()      // 一个站点会话一个，别每请求 new
 * val resp = ktor.get("$BASE_URL/list") { header("Referer", …) }
 * if (resp.status.value == 200) resp.bodyAsText()
 * ```
 *
 * ⚠️ **不要**给它装 `HttpCookies` 插件：cookie 归 okhttp 的 jar 管，装第二层会出现
 * 「两套 cookie 各自记账」——那是排查起来最费劲的一类 bug。`expectSuccess = false`
 * 与 App 现有习惯一致（校园系统爱把错误包在 200 里，不能变成异常路径）。
 *
 * ## 两个实测出来的坑（差分测试 `KtorOnOkHttpParityTest` 拓的，别删它）
 *
 * 1. **Ktor 会自己塞一个 `User-Agent: ktor-client`**（引擎层，`HttpClientConfig` 里没有关它的开关）。
 *    而 [HttpClients.base] 的拦截器只在**没有** UA 时补 [APP_UA] —— 于是 Ktor 发出去的请求
 *    全都带着 `ktor-client`，**统一认证的 TGC 绑定 UA 那条约束就破了**，表现在线用户身上是
 *    「登录态莫名失效」。所以这里用 [DefaultRequest] 把 UA 默认值改回 [APP_UA]；
 *    调用方要特殊 UA（扫码那套 `APP_UA + SuperApp 后缀`）时，在自己的请求里写 `header("User-Agent", …)` 覆盖即可。
 * 2. **这个客户端不能 `close()`**。Ktor 的 OkHttp 引擎会从 `preconfigured` **派生**出一个客户端，
 *    而 okhttp 的 `newBuilder()` **共用同一个 `Dispatcher`** ⇒ `close()` 会把那个 Dispatcher 关掉，
 *    连带把 [HttpClients.base] 以及全进程所有派生客户端一起打死（症状：
 *    `InterruptedIOException: executor rejected`，而且只在“用过一次之后”的下一个请求上出现）。
 *    会话本身是进程级长生命周期的，所以规则很简单：**建了就留着，永远别关**。
 *
 * @param preconfigured 已有的客户端；默认就是进程共享的 [HttpClients.base]。
 */
fun OkHttpClient.asToolboxKtorClient(preconfigured: OkHttpClient = this): HttpClient =
    HttpClient(OkHttp) {
        engine { this.preconfigured = `preconfigured` }
        expectSuccess = false
        install(ContentNegotiation) { json(toolboxJson) }
        // 见 KDoc 坑 1：不补这一行，统一认证绑定的 UA 会被 ktor-client 顶掉。
        // 语义刻意与 HttpClients.base 的拦截器一致：**只在缺少 UA 时补**（不能用 DefaultRequest，
        // 它是 append：调用方写了 SuperApp UA 会变成 “xxx,xxx” 两个值，扫码那套就废了）。
        install(
            createClientPlugin("ToolboxUserAgent") {
                onRequest { request, _ ->
                    if (request.headers[HttpHeaders.UserAgent] == null) {
                        request.headers.append(HttpHeaders.UserAgent, APP_UA)
                    }
                }
            }
        )
    }
