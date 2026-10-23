package com.xjtu.toolbox.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** `/api` 前缀。它出现在三个地方（闸门、兜底 404、静态托管的拒绝判据），所以是一个常量。 */
internal const val API_PREFIX = "/api"

/** 唯一免令牌的端点（`docs/api-contract.md` §3.4）：探活 + 有没有会话，裸对象，不含身份。 */
internal const val API_STATUS_PATH = "$API_PREFIX/status"

/** serve 模式的 HTTP 外壳：静态托管 `:web` 的产物 + 实现 `/api/…`。 */
fun Application.serveModule(
    config: ServeConfig,
    accessToken: String,
    session: ServeSession,
    agent: ServeAgent = ServeAgent.forServe(session),
    startedAtMillis: Long = System.currentTimeMillis(),
) {
    // 出网 JSON 用 ApiJson（AppJson + explicitNulls，理由见那里的 KDoc）
    install(ContentNegotiation) { json(ApiJson) }

    routing {
        get(API_STATUS_PATH) {
            call.respond(
                ServeStatus(
                    // 真实会话：`:data` 的凭据在手（登录过，或冷启动从落盘凭据静默恢复过）
                    authenticated = session.authenticated,
                    uptimeSeconds = (System.currentTimeMillis() - startedAtMillis) / 1000,
                ),
            )
        }

        route(API_PREFIX) {
            install(accessTokenGate(accessToken))

            sessionRoutes(session, accessToken, secureCookie = config.tls != null)

            serveEndpointRoutes(session)
            jwxtRoutes(session)
            libraryRoutes(session)
            cardRoutes(session)
            venueRoutes(session)

            dzpzRoutes(session)
            attendanceRoutes(session)
            couponRoutes(session)

            // 第二批**只读域**（思源学堂 / 教材中心 / 移动教务成绩 / 人脸识别签到，契约 §5.6）：
            // 与上一批同一条做法 —— `:data` 里早有取数实现（桌面端直连在用），这里只是端出去。
            lmsRoutes(session)
            jiaocaiRoutes(session)
            jwappRoutes(session)
            iclassfaceRoutes(session)

            // 教材全文库（`jiaocai1.lib`，契约 §5.14）：会话借的是上面那个 `jiaocai` 站点
            // （全文库的会话由 `jiaocai.lib` 那一侧桥接过来），**一个新站点都没注册**。
            // 页图与封面那两条是 §4 的第四条例外（成功回 `image/jpeg`，失败仍回信封）。
            jiaocai1Routes(session)
            iclassfaceRoutes(session)

            // 第三批（付款码拿码 / 反馈读 / 匹配交友的消费统计 / WebVPN 纯转换，契约 §5.7）：
            // 前两族各自有 `:data` 的实现、第三族落在校园卡流水上、第四族是纯函数。
            payRoutes(session)
            feedbackRoutes()
            matchRoutes(session)
            webVpnRoutes()

            // 写侧只在 HTTPS 下服务（会议 #10 = 丙）：没开 HTTPS 时四条写端点一律 403 + 怎么开的指引。
            accountRoutes(session, httpsEnabled = config.tls != null)


            // 下载（契约 §5.13，会议 #9 = 丙「做法①」）：serve 里装真的下载引擎，浏览器只当界面。
            // 与桌面窗口同一套引擎（`:feature:lms` + `:data` 的 jvm 实现），落盘在那台机器的 ~/Downloads。
            downloadRoutes(session)
            // 屁岱（契约 §5.11）：`POST /api/agent/chat` 是 SSE；不要求已登录，没配 key 是 409。
            agentRoutes(agent)

            // 过了闸门、但还没实现这个端点 ⇒ 404（信封，中文短句）。
            // 尾卡选择器 + handle（不带方法）：GET/POST/… 全收 —— 没实现的端点不该在方法上给差别待遇。
            route("{path...}") {
                handle { call.respond(HttpStatusCode.NotFound, ApiErrors.notFound()) }
            }
        }

        staticSite(config.distDir)
    }
}

/** 建好（但还没启动）一个 serve 实例。 */
fun serveServer(
    config: ServeConfig,
    accessToken: String,
    session: ServeSession,
    agent: ServeAgent = ServeAgent.forServe(session),
    startedAtMillis: Long = System.currentTimeMillis(),
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
    embeddedServer(CIO, port = config.port, host = config.host) {
        serveModule(config, accessToken, session, agent, startedAtMillis)
    }
