package com.xjtu.toolbox.web

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeIntOrNull
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `:web` 在 serve 模式下对 `:server` 的会话（`docs/api-contract.md` §5.1 的四个端点）。 */
class WebServeSession(private val client: HttpClient, private val holder: ServeTokenHolder) {

    /** 贴上的令牌（空串 = 清掉）。设一次就够 —— 所有客户端都读同一个 [holder]。 */
    fun setToken(raw: String) {
        holder.token = raw.trim().takeIf { it.isNotEmpty() }
    }

    /** 手里有没有令牌（= 能不能打第一枪换 cookie）。 */
    val hasToken: Boolean get() = holder.token != null

    /** `GET /api/session`：有没有会话。 */
    suspend fun sessionState(): Result<Boolean> =
        call { method = HttpMethod.Get; url("$API_BASE/api/session") }
            .map { it["authenticated"].safeBoolean() }

    /** `POST /api/session/login`。 */
    suspend fun login(username: String, password: String): Result<Unit> =
        call {
            method = HttpMethod.Post
            url("$API_BASE/api/session/login")
            contentType(ContentType.Application.Json)
            setBody(LoginBody(username, password))
        }.map { }

    /** `POST /api/session/logout`：凭据 / cookie / 站点快照一起删。幂等（本来没登录也是 200）。 */
    suspend fun logout(): Result<Unit> =
        call { method = HttpMethod.Post; url("$API_BASE/api/session/logout") }.map { }

    /** `GET /api/session/mfa`：挂起的那条询问（**轮询**，不是 SSE，见 §5.1 第 3 条）。 */
    suspend fun mfa(): Result<MfaState> =
        call { method = HttpMethod.Get; url("$API_BASE/api/session/mfa") }.map { mfaStateOf(it) }

    /** `POST /api/session/mfa {"code":"…"}`：交验证码（结论从下一次轮询或那一发登录的响应读）。 */
    suspend fun submitMfa(code: String): Result<MfaState> = call {
        method = HttpMethod.Post
        url("$API_BASE/api/session/mfa")
        contentType(ContentType.Application.Json)
        setBody(MfaSubmitBody(code = code))
    }.map { mfaStateOf(it) }

    /** `POST /api/session/mfa {"cancel":true}`：取消（那一发登录随后以 4xx 收尾，这就是 `cancel` 的语义）。 */
    suspend fun cancelMfa(): Result<MfaState> = call {
        method = HttpMethod.Post
        url("$API_BASE/api/session/mfa")
        contentType(ContentType.Application.Json)
        setBody(MfaSubmitBody(cancel = true))
    }.map { mfaStateOf(it) }

    private fun mfaStateOf(data: JsonObject) = MfaState(
        pending = data["pending"].safeBoolean(),
        siteName = data["siteName"].safeString().trim().takeIf { it.isNotEmpty() },
        rejections = data["rejections"].safeIntOrNull(),
        attemptsLeft = data["attemptsLeft"].safeIntOrNull(),
    )

    /** 一次 `/api/session*` 调用：读 §4 的信封。 */
    private suspend fun call(block: HttpRequestBuilder.() -> Unit): Result<JsonObject> = try {
        val response = client.request(block)
        val text = response.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error(":server 返回的不是 JSON 对象（HTTP ${response.status.value}）")
        if (envelope["code"].safeString().trim().toIntOrNull() != 0) {
            error(
                envelope["message"].safeString().trim()
                    .ifBlank { ":server 返回 HTTP ${response.status.value}" },
            )
        }
        Result.success(envelope["data"] as? JsonObject ?: JsonObject(emptyMap()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

/** `/api/session/mfa` 的 `data`（§5.1 第 3 条） */
data class MfaState(
    val pending: Boolean,
    val siteName: String? = null,
    /** 服务端拒绝了几次（涨了就是刚才那一次验证码不对）。 */
    val rejections: Int? = null,
    /** `= 3 - rejections`（`SessionManager.MFA_MAX_ATTEMPTS` 是 3）。 */
    val attemptsLeft: Int? = null,
)

/** `POST /api/session/login` 的请求体：学号两侧的空白由 `:server` 去掉（§5.1）。 */
@Serializable
private data class LoginBody(val username: String, val password: String)

/** `POST /api/session/mfa` 的请求体：`code` 与 `cancel` 二选一。 */
@Serializable
private data class MfaSubmitBody(val code: String? = null, val cancel: Boolean = false)

/** 这一份会话用的访问令牌。 */
class ServeTokenHolder {
    /** 当前令牌（`null` = 还没贴）。**只在内存里**，见 [WebServeSession] 的 KDoc。 */
    var token: String? = null
}

/** 全页唯一的那一份令牌（见 [ServeTokenHolder] 的 KDoc）。 */
val serveToken: ServeTokenHolder = ServeTokenHolder()

/** 探测这一份 `:web` 在对谁说话（见 [ApiMode]）。 */
suspend fun detectApiMode(client: HttpClient): ApiMode {
    when (browserSearchParam("backend")?.lowercase()) {
        "serve" -> return ApiMode.SERVE
        "api", "campus", "campus-api" -> return ApiMode.CAMPUS_API
        else -> Unit
    }
    return try {
        val text = client.get("$API_BASE/api/status").bodyAsText()
        val status = AppJson.parseToJsonElement(text) as? JsonObject
        if (status != null && "authenticated" in status && "username" !in status) {
            ApiMode.SERVE
        } else {
            ApiMode.CAMPUS_API
        }
    } catch (e: Throwable) {
        ApiMode.CAMPUS_API
    }
}
