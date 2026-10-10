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

/**
 * **唯一免令牌**的端点（`docs/api-contract.md` §3.4）：探活 + 有没有会话，裸对象，不含身份。
 *
 * 它的「免令牌」是**闸门里**那一条显式豁免（见 [accessTokenGate] 的 KDoc：两者是路由树里的同一棵子树，
 * 靠把这条路由挂到闸门外面是做不到的）。
 */
internal const val API_STATUS_PATH = "$API_PREFIX/status"

/**
 * serve 模式的 HTTP 外壳：静态托管 `:web` 的产物 + 实现 `/api/…`。
 *
 * 三块内容：
 *  1. `GET /api/status` —— 唯一免令牌（闸门里的豁免），**裸对象**（[ServeStatus]）；
 *  2. `/api/…` —— 令牌闸门罩着的一切：[sessionRoutes] 那四个 `/api/session*` 端点挂在这块里面，
 *     从而**自动**被闸门罩住；其余没实现的端点如实 404；
 *  3. 其余路径 —— 静态托管（[staticSite]）。
 *
 * `/api/…` 与静态的优先级不是靠登记顺序，而是靠路由选择器：`/api` 是**常数段**，
 * 比静态那条 `{path...}`（尾卡）更具体，所以 `/api/x` 永远先落在闸门那块
 *（`:server:test` 里「产物目录里放一个 api/secret.txt 也取不到」把这一条钉住）。
 *
 * ⚠️ **会话对象必须由调用方传进来**（不是这里 `ServeSession()`）：它一构造就会读落盘凭据 / cookie 快照，
 * 而「数据根在哪儿」是宿主的决定（`Main` 用真实数据目录，测试用 `dataRootOverride` 指到临时目录）。
 * 默认参数在这里等于给测试埋一个「悄悄碰用户真实凭据」的陷。
 *
 * @param session serve 进程的会话装配（[ServeSession]）：`/api/session*` 与 `/api/status` 的会话源。
 * @param startedAtMillis [ServeStatus.uptimeSeconds] 的计时起点（默认进程启动那一刻）
 */
fun Application.serveModule(
    config: ServeConfig,
    accessToken: String,
    session: ServeSession,
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

            // `/api/session*` 四个端点（契约 §5 的 P0）—— 挂在这里就是「自动被闸门罩住」
            sessionRoutes(session, accessToken)

            // 过了闸门、但还没实现这个端点 ⇒ 404（信封，中文短句）。
            // 尾卡选择器 + handle（不带方法）：GET/POST/… 全收 —— 没实现的端点不该在方法上给差别待遇。
            route("{path...}") {
                handle { call.respond(HttpStatusCode.NotFound, ApiErrors.notFound()) }
            }
        }

        staticSite(config.distDir)
    }
}

/**
 * 建好（但**还没启动**）一个 serve 实例。
 *
 * 单独留一层是为了测试：`:server:test` 要先 `start(wait = false)` 再用
 * `engine.resolvedConnectors()` 问出**真正绑定**的地址（`--port 0` 时那是系统挑的端口），
 * 然后才发请求。
 */
fun serveServer(
    config: ServeConfig,
    accessToken: String,
    session: ServeSession,
    startedAtMillis: Long = System.currentTimeMillis(),
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
    embeddedServer(CIO, port = config.port, host = config.host) {
        serveModule(config, accessToken, session, startedAtMillis)
    }
