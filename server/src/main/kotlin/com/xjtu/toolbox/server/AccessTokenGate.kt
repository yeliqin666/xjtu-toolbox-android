package com.xjtu.toolbox.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.path
import io.ktor.server.response.respond

/** `/api/…` 的令牌闸门。 */
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

/** 闸门豁免的路径：契约 §3.4 的唯一免令牌端点。 */
private val EXEMPT_PATHS = setOf(API_STATUS_PATH)

/** 请求里带的令牌是不是 [expected]。两种形态任一命中即可。 */
private fun ApplicationRequest.presentsAccessToken(expected: String): Boolean =
    AccessToken.matches(expected, bearerToken() ?: cookies[AccessToken.COOKIE_NAME])

/** `Authorization: Bearer <token>` 里的 `<token>`。 */
private fun ApplicationRequest.bearerToken(): String? =
    headers[HttpHeaders.Authorization]
        ?.takeIf { it.length > BEARER_PREFIX.length && it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private const val BEARER_PREFIX = "Bearer "
