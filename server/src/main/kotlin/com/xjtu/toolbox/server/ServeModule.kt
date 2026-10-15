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
 * @param agent 屁岱（Agent）那一族的驱动与三份落盘（契约 §5.11）。
 *   默认值就是**生产装配**（[ServeAgent.forServe]）；只有本族的契约测试会传自己那一份（临时数据根）。
 *   它与 [session] **不是同一种东西**：session 一构造就读落盘凭据，而 agent 的三个 store 都是**懒读**
 *   —— 不碰 `/api/agent*` 就一个字节都不读写，所以这里可以给默认值。
 * @param startedAtMillis [ServeStatus.uptimeSeconds] 的计时起点（默认进程启动那一刻）
 */
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

            // `/api/session*` 四个端点（契约 §5 的 P0）—— 挂在这里就是「自动被闸门罩住」。
            // `secureCookie`：HTTPS 形态（config.tls 非 null ⇒ 这一次是 [ServeHttps] 起的）下
            // 令牌换来的 cookie 带 `Secure`；明文那一档是 false，**与今天逐字相同**。
            sessionRoutes(session, accessToken, secureCookie = config.tls != null)

            // 契约 §5 的 P0 取数端点：组一（免登录/轻登录六域）+ 组二（需登录六域）
            // 两组各自在独立文件里实现（`serveEndpointRoutes` / `jwxtRoutes` / `libraryRoutes` /
            // `cardRoutes` / `venueRoutes`），这里只是把它们一行一行接进来；顺序无关，
            // Ktor 按选择器具体度选路（常数段优先于 {path...} 尾卡）。
            serveEndpointRoutes(session)
            jwxtRoutes(session)
            libraryRoutes(session)
            cardRoutes(session)
            venueRoutes(session)

            // 四组**只读域**（成绩单 / 考勤 / 加餐券 / 宿舍电费，契约 §5.5）：
            // `:data` 里早就有取数实现（桌面端直连在用），本批只是把它们端出去。
            // 四族各自一个文件（与上面那几族同一条规矩）。
            dzpzRoutes(session)
            attendanceRoutes(session)
            couponRoutes(session)
            dormRoutes(session)

            // 第二批**只读域**（思源学堂 / 教材中心 / 移动教务成绩 / 人脸识别签到，契约 §5.6）：
            // 与上一批同一条做法 —— `:data` 里早有取数实现（桌面端直连在用），这里只是端出去。
            lmsRoutes(session)
            jiaocaiRoutes(session)
            jwappRoutes(session)
            iclassfaceRoutes(session)

            // 第三批（付款码拿码 / 反馈读 / 匹配交友的消费统计 / WebVPN 纯转换，契约 §5.7）：
            // 前两族各自有 `:data` 的实现、第三族落在校园卡流水上、第四族是纯函数。
            payRoutes(session)
            feedbackRoutes()
            matchRoutes(session)
            webVpnRoutes()

            // 多账号页那一族（只读，契约 §5.10 / D9）：账号表（与桌面共用 `xjtu_accounts`）
            // + serve 这一侧当前会话那一条。**没有写端点** —— 切换/新增/删除/改密仍要去 App 或桌面端
            // 做，`readOnly:true` 就是这件事的能力开关（见 `AccountRoutes.kt` 的文件头）。
            accountRoutes(session)

            // 屁岱（Agent）那一族（契约 §5.11 / D10）：14 个端点，其中 `POST /api/agent/chat`
            // 是 **SSE**（`text/event-stream`，与本族其余条的信封不同，见那个文件的 KDoc）。
            // 驱动与三份落盘都在 [ServeAgent] 里；它**不要求已登录** —— key 与循环都在 serve，
            // 而「没配 key」是 409 + 中文短句（不静默失败）。
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
    agent: ServeAgent = ServeAgent.forServe(session),
    startedAtMillis: Long = System.currentTimeMillis(),
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
    embeddedServer(CIO, port = config.port, host = config.host) {
        serveModule(config, accessToken, session, agent, startedAtMillis)
    }
