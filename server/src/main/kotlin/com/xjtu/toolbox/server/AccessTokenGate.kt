package com.xjtu.toolbox.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.path
import io.ktor.server.response.respond

/**
 * `/api/…` 的**令牌闸门**：除 `/api/status` 外，所有 `/api/…` 一律要令牌
 *（`docs/api-contract.md` §3.4：「`/api/status` 是**唯一免令牌**端点」）。
 *
 * ## 为什么挂在路由上，而不是每个 handler 自己查
 *
 * 「每个 handler 首行写一句 `if (!hasToken()) return 401`」这种写法漏一个是**静默**的，
 * 而且新端点天然会漏（没人写业务时想起鉴权）。这里是**路由作用域插件**，装在 `route("/api")` 上：
 *
 * - 它跑在 Ktor 的 `Validators` 阶段（专门留给「鉴权 / 限流 / CORS 这类守卫」的那个阶段），
 *   早于 `Call` 阶段的路由 handler —— 响应一旦提交，路由 handler 就被跳过（`isHandled`）；
 * - 父路由的拦截器会并进子路由的管线 ⇒ 以后把端点挂在 `route("/api") { … }` 里就**自动**被闸住。
 *
 * ## 为什么豁免写在闸门里，而不是「把 /api/status 挂到闸门外面」
 *
 * 后者**做不到**：Ktor 的 `createChild` 会把**相同前缀复用成同一个节点**，所以 `/api/status`
 * 与 `route("/api")` 那块是同一棵子树。第一版按「挂在外面」写的，实测 `/api/status` 照样被闸成
 * 401（`createApplicationPlugin` 式的直觉在这里不成立）—— 挂在 `/api` 上的守卫分不出
 * 「挂在自己身上」与「挂在子节点上」。
 *
 * 于是 §3.4 的「唯一免令牌端点」只能是闸门里那一条**显式豁免**（[EXEMPT_PATHS]），
 * 由 `:server:test` 钉住（`/api/status` 不带令牌 200、带**错**令牌也 200）。
 *
 * ## 认哪两种形态
 *
 * 契约 §3.2：`Authorization: Bearer <token>`，或**令牌换 cookie 之后**随请求带的 cookie
 *（名字见 [AccessToken.COOKIE_NAME]）。换 cookie 的那一步（`/api/session`）还没做，
 * 但「认哪种形态」现在就得定下来，免得两边各起一个名字。
 *
 * @param expected 期望的令牌（[AccessToken.loadOrCreate] 读回来的那一枚）
 */
internal fun accessTokenGate(expected: String): RouteScopedPlugin<Unit> =
    createRouteScopedPlugin(GATE_NAME) {
        onCallValidators { call ->
            val path = call.request.path()
            if (path in EXEMPT_PATHS) return@onCallValidators
            if (!call.request.presentsAccessToken(expected)) {
                call.respond(HttpStatusCode.Unauthorized, ApiErrors.unauthorized())
            }
        }
    }

private const val GATE_NAME = "ServeAccessTokenGate"

/** 闸门豁免的路径：契约 §3.4 的**唯一**免令牌端点。改它等于改安全口径。 */
private val EXEMPT_PATHS = setOf(API_STATUS_PATH)

/** 请求里带的令牌是不是 [expected]。两种形态任一命中即可。 */
private fun ApplicationRequest.presentsAccessToken(expected: String): Boolean =
    AccessToken.matches(expected, bearerToken() ?: cookies[AccessToken.COOKIE_NAME])

/**
 * `Authorization: Bearer <token>` 里的 `<token>`。
 *
 * 方案名按 HTTP 的规矩**大小写不敏感**（`bearer` / `BEARER` 都算），值两侧的空格去掉。
 * 认不出的写法（比如没带方案名就直接放令牌）返回 null —— 那不算带令牌，走 401。
 */
private fun ApplicationRequest.bearerToken(): String? =
    headers[HttpHeaders.Authorization]
        ?.takeIf { it.length > BEARER_PREFIX.length && it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private const val BEARER_PREFIX = "Bearer "
