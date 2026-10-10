package com.xjtu.toolbox.core.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

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
) {
    suspend fun status(): SessionStatus = client.get("$baseUrl/api/status").body()

    suspend fun term(): String {
        val env: Envelope<TermData> = client.get("$baseUrl/api/jwxt/term").body()
        return env.data?.term
            ?: error("term 缺失：code=${env.code} message=${env.message}")
    }

    /**
     * 教务课表。返回的是**上游原始 47 列**（`data.rows`），周次看 [ScheduleRow.weeksText]。
     *
     * Android 端现在走自己的 okhttp 版 ScheduleApi；这个方法是为共享层与 Web 端存在的，
     * 也是将来 Android 端切过来的落点（探针已证明 Ktor 能替代 okhttp）。
     *
     * ⚠️ **serve 模式下这个方法拿不到数据**：`docs/api-contract.md` §5 的端点清单里
     * `/api/jwxt/schedule` 不在已落地的那 26 条 P0 取数端点里（`:server` 会答 `404` +
     * `message:"接口不存在"`，本方法会抛出那句），`/api/jwxt/term-start` 同理。
     * 所以 serve 模式的 Web 端**课表屏（含默认落地页）**现在会显示这句错误 —— 这是如实降级，
     * 不是本地解析错了（TODO：:server 补这两个端点后，这一条就该删掉）。
     */
    suspend fun schedule(term: String): ScheduleData {
        val env: Envelope<ScheduleData> = client
            .get("$baseUrl/api/jwxt/schedule") { parameter("term", term) }
            .body()
        return env.data ?: error("schedule 缺失：code=${env.code} message=${env.message}")
    }

    /**
     * 学期起始：`startDate` 是**第 1 周周一**。课表本身不含日期，周次换算全靠它。
     *
     * ⚠️ serve 模式下 `:server` 尚未实现这个端点（见 [schedule] 的 KDoc，同一条 TODO）。
     */
    suspend fun termStart(): TermStartData {
        val env: Envelope<TermStartData> = client.get("$baseUrl/api/jwxt/term-start").body()
        return env.data ?: error("term-start 缺失：code=${env.code} message=${env.message}")
    }
}
