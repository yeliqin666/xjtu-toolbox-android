package com.xjtu.toolbox.core.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
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
}
