package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.util.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

/** campus-api 的 `{code,data}` 信封。 */
@Serializable
data class Envelope<T>(
    val code: Int = 0,
    val data: T? = null,
    val message: String? = null,
)

@Serializable
data class TermData(val term: String)

@Serializable
data class SessionStatus(
    val authenticated: Boolean = false,
    val username: String? = null,
    val loggedAt: Long = 0,
    val uptimeSeconds: Long = 0,
    val lastSessionLifetime: Long = 0,
)

class CampusApi(
    private val client: HttpClient,
    private val baseUrl: String,
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.3 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) {
    suspend fun status(): SessionStatus = client.get("$baseUrl/api/status").body()

    suspend fun term(): String {
        val env: Envelope<TermData> = client.get("$baseUrl/api/jwxt/term").body()
        return env.data?.term
            ?: error("term 缺失：code=${env.code} message=${env.message}")
    }

    /** 教务课表。 */
    suspend fun schedule(term: String): ScheduleData {
        if (mode == ApiMode.SERVE) {
            return AppJson.decodeFromJsonElement<ScheduleData>(
                client.serveData("加载课表", baseUrl, "/api/jwxt/schedule", listOf("term" to term)),
            )
        }
        val env: Envelope<ScheduleData> = client
            .get("$baseUrl/api/jwxt/schedule") { parameter("term", term) }
            .body()
        return env.data ?: error("schedule 缺失：code=${env.code} message=${env.message}")
    }

    /** 学期起始：`startDate` 是第 1 周周一。 */
    suspend fun termStart(term: String? = null): TermStartData {
        if (mode == ApiMode.SERVE) {
            return AppJson.decodeFromJsonElement<TermStartData>(
                client.serveData("加载学期起点", baseUrl, "/api/jwxt/term-start", listOf("term" to term.orEmpty())),
            )
        }
        val env: Envelope<TermStartData> = client
            .get("$baseUrl/api/jwxt/term-start") { term?.takeIf { it.isNotBlank() }?.let { parameter("term", it) } }
            .body()
        return env.data ?: error("term-start 缺失：code=${env.code} message=${env.message}")
    }
}
