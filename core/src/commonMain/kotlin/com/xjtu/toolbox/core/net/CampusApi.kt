package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.util.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * campus-api 的 `{code,data}` 信封。
 *
 * ⚠️ `/api/status` 是**裸对象**（没有信封）——这是上游契约里最容易踩的一处不一致，
 * 探针里两个端点各用一个模型，就是为了把这个差异显式钉在类型上。
 */
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

/**
 * @param baseUrl 各端不同：Web 端必须走**同源**反代（浏览器有 CORS，直连学校不通），
 *   Android/jvm 可以直连 `http://127.0.0.1:3099`。
 */
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

    /**
     * 教务课表。返回的是**上游原始列**的行（`data.rows`），周次看 [ScheduleRow.weeksText]。
     *
     * Android 端现在走自己的 okhttp 版 ScheduleApi；这个方法是为共享层与 Web 端存在的，
     * 也是将来 Android 端切过来的落点（探针已证明 Ktor 能替代 okhttp）。
     *
     * 两个后端都在这一条路径上答：campus-api 给的是上游 47 列，serve 模式的
     * `/api/jwxt/schedule`（`docs/api-contract.md` §5.3，2026-10-11 落地）给的是 [ScheduleRow]
     * 钉住的那 8 列 —— 两者都由 [ScheduleRow] 解码（多给的列被 `ignoreUnknownKeys` 忽略），
     * 两条路只在「信封怎么读」上有差别：serve 那一支走 [serveData]（成功判据是 `code == 0`、
     * 失败读 `message`）。
     *
     * ⚠️ serve 模式给的这些行是**排课原样**：调停补课没有合进去（`docs/api-contract.md` §5.3
     * 的 TODO），`ScheduleData` 也带不下那份记录。
     */
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

    /**
     * 学期起始：`startDate` 是**第 1 周周一**。课表本身不含日期，周次换算全靠它。
     *
     * 两个后端都在这一条路径上答（`docs/api-contract.md` §5.3，serve 那一支 2026-10-11 落地）；
     * serve 模式下 `?term=` 省略时的「当前学期」是**服务端**判的（与 `/api/jwxt/term` 同一个来源）。
     */
    suspend fun termStart(): TermStartData {
        if (mode == ApiMode.SERVE) {
            return AppJson.decodeFromJsonElement<TermStartData>(
                client.serveData("加载学期起点", baseUrl, "/api/jwxt/term-start"),
            )
        }
        val env: Envelope<TermStartData> = client.get("$baseUrl/api/jwxt/term-start").body()
        return env.data ?: error("term-start 缺失：code=${env.code} message=${env.message}")
    }
}
