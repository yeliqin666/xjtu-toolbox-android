package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.yellowpage.YellowPageFixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * 桌面端 test 源集里的**假 Ktor 客户端**（`:desktop:test` 与 `renderScreens` 共用一份）。
 *
 * ## 为什么黄页不用「起服务器」那一套
 *
 * 图书馆与校历那两条面对的是**会话内核**（cookie、302 跳转、CAS），所以必须有一个真的 HTTP
 * 服务器 + 代理（见 `:testkit` 的 `FakeCampusProxy`）。黄页不是：`:core` 的 `YellowPageApi`
 * 收一个 `HttpClient` —— 端口在**调用方**一侧，所以这里用 Ktor 自己的 `MockEngine` 把假响应
 * 喂进去就够了，少一层服务器、少一个端口、也不受「https 走不了 CONNECT」那条限制
 * （真机的黄页地址是 https，见交接文档 §2.8）。
 *
 * 样本在 `:testkit` 的 `YellowPageFixture`（`:testkit` 刻意只存「响应体长什么样」与
 * 「哪条路径对应哪个体」，**不引 ktor** —— 引擎的搭建留给消费者）。
 *
 * 放在单独一个文件里而不是塞进测试类：`renderScreens`（离屏证据）也要用它，两个文件在同一个
 * test 源集里，共用这一份就不会漂。
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
