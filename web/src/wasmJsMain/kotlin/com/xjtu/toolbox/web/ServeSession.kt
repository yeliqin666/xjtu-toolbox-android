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

/**
 * `:web` 在 serve 模式下对 `:server` 的**会话**（`docs/api-contract.md` §5.1 的四个端点）。
 *
 * ## 访问令牌为什么只在内存里
 *
 * §3.2 的形态是「先用 `Authorization: Bearer <令牌>` 打一枪，换一枚 cookie `serve_token`
 * （`HttpOnly`），之后浏览器只带 cookie」。令牌是这一层的**唯一一道门**（同机任何进程都连得上
 * `127.0.0.1:8123`），把它写进 `localStorage` 就等于交给页面上任何一段脚本 —— 所以这里**不落盘**。
 * 代价是真实的：浏览器关掉之后会话 cookie 也没了，用户要重新贴一次令牌。
 * TODO：要免掉这次粘贴，正确方向是 `:server` 给 cookie 加 `Max-Age`（那是服务端的安全口径决定，
 * 不在这一端自己解决掉）。
 *
 * ## 令牌怎么进请求头
 *
 * [toolboxWebClient] 装了一个 `defaultRequest`，每次请求都读 [ServeTokenHolder.token]
 * —— 于是「贴一次」之后**所有**请求（含 13 个取数）都带着 `Bearer`。为什么不只靠 cookie：
 * 「fetch 会不会自动带 cookie」是**引擎的实现细节**（契约 §3.2 只管闸门认哪两种形态），
 * 而带上 Bearer 是契约里写死的形态 ⇒ 两条路任一成立都能过闸门，不必赌第三条。
 */
class WebServeSession(private val client: HttpClient, private val holder: ServeTokenHolder) {

    /** 贴上的令牌（空串 = 清掉）。设一次就够 —— 所有客户端都读同一个 [holder]。 */
    fun setToken(raw: String) {
        holder.token = raw.trim().takeIf { it.isNotEmpty() }
    }

    /** 手里有没有令牌（= 能不能打第一枪换 cookie）。 */
    val hasToken: Boolean get() = holder.token != null

    /**
     * `GET /api/session`：有没有会话。**顺手把令牌换成 cookie**（§5.1 第 1 条：四个端点里只有它发
     * `Set-Cookie`，客户端启动时必然要问一次「有没有会话」，cookie 顺手就拿到了）。
     *
     * ⚠️ `authenticated` 的含义是「**登录跑完了**」（或冷启动从落盘凭据静默恢复过），不是
     * 「内核里有凭据」：登录挂在短信二验上时它仍是 `false`。
     */
    suspend fun sessionState(): Result<Boolean> =
        call { method = HttpMethod.Get; url("$API_BASE/api/session") }
            .map { it["authenticated"].safeBoolean() }

    /**
     * `POST /api/session/login`。挂起等短信验证码时**这一发一直开着**（§5.1 第 4 条：最长 150 秒）
     * ⇒ 调用方绝不能串行等它：要并发地轮询 [mfa]（见 [WebServeSessionScreen]）。
     *
     * 失败码两种（都由服务端的 `message` 说明）：`401` = 凭据/验证被拒（重发没意义）、
     * `502` = 上游或网络的故障（稍后重试可能就成）；另一次登录还在跑是 `409`。
     */
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

    /**
     * 一次 `/api/session*` 调用：读 §4 的信封。
     *
     * 规矩与 `:core` 的 `serveData` 一字不差（同一个信封、同一条判据）：**成功只看 `code == 0`**，
     * 失败时 `message` 是能直接给用户看的中文短句（不把 HTTP 状态码当业务码解、也不把 `code` 拿去分支）。
     * 这里返回 `Result` 而不是抛：登录页要把那句话显示出来，不是崩掉。
     * `CancellationException` 照旧往外抛（页面走了不该被当成「请求失败」）。
     */
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

/**
 * `/api/session/mfa` 的 `data`（§5.1 第 3 条）：没有挂起时只有 `pending:false`，
 * 其余三格是 `null`（= 本端明确说没有），不是字段缺失。
 *
 * ⚠️ 契约**不投影**绑定手机号（§5.1 的 TODO ①）：弹窗里那句「验证码已发送到 138\*\*\*\*0000」
 * 现在写不出来 —— 如实不写，而不是编一个号码出来。
 */
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

/**
 * `POST /api/session/mfa` 的请求体：`code` 与 `cancel` 二选一。
 *
 * `toolboxJson` 的 `explicitNulls = false` ⇒ `{"code":"123456"}` 与 `{"cancel":true}`，
 * 不会多出 `"cancel":false` 这种字段（§5.1 的形状是「二选一」，多一个字段就是另一个形状了）。
 */
@Serializable
private data class MfaSubmitBody(val code: String? = null, val cancel: Boolean = false)

/**
 * 这一份会话用的访问令牌。
 *
 * 为什么是**页面级单例**而不是每个调用点各传一份：`:web` 里有两处自己 new 客户端
 * （课表屏、评教屏 —— 都是「一屏一个客户端」的老写法），它们也必须带上同一枚令牌；
 * 把持有者做成单例是唯一不会让这两条路分叉的写法（[toolboxWebClient] 的默认参数读的就是它）。
 * Compose 页面本身是「一次加载一个实例」，所以它其实仍然是页面作用域的状态。
 */
class ServeTokenHolder {
    /** 当前令牌（`null` = 还没贴）。**只在内存里**，见 [WebServeSession] 的 KDoc。 */
    var token: String? = null
}

/** 全页唯一的那一份令牌（见 [ServeTokenHolder] 的 KDoc）。 */
val serveToken: ServeTokenHolder = ServeTokenHolder()

/**
 * 探测这一份 `:web` 在对谁说话（见 [ApiMode]）。两档判据，**先看显式开关再看探测**：
 *
 * 1. `?backend=serve` / `?backend=api`：书签、排障时把这一条钉死，省掉一次猜测；
 * 2. 否则探一次 `GET /api/status` —— `/api/…` 里**唯一免令牌**的端点（契约 §3.4）。
 *    同一个路径、同一种「裸对象」形状，两边唯一稳定的差别是**有没有 `username`**：
 *    `:server` 的那一份**不含身份**（§3.4 的红线，只有 `authenticated` / `uptimeSeconds`），
 *    campus-api 的那一份带 `username` / `loggedAt` / `lastSessionLifetime`；
 * 3. 探不通（谁都没起、或跨源被挡）⇒ 保持 [ApiMode.CAMPUS_API]：**默认形态一字不改**。
 */
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
