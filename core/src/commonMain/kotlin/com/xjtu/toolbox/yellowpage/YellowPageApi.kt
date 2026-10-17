package com.xjtu.toolbox.yellowpage

import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.requireArr
import com.xjtu.toolbox.util.safeGet
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** 黄页的缓存能力切口。 */
interface YellowPageCache {
    /** 未过期（TTL 内）的缓存，没有返回 null。 */
    fun read(): YellowPageData?

    /** 不看 TTL 的兜底缓存，网络失败时用。 */
    fun readStale(): YellowPageData?

    fun write(data: YellowPageData)
}

/** 黄页取数端口。 */
interface YellowPageSource {
    suspend fun getData(forceRefresh: Boolean = false): YellowPageData
}

/** 黄页（`workflow.xjtu.edu.cn/selectpage` 的机构通讯录）的只读接口。 */
class YellowPageApi(
    private val client: HttpClient,
    private val cache: YellowPageCache? = null,
    private val baseUrl: String = BASE_URL,
) : YellowPageSource {
    override suspend fun getData(forceRefresh: Boolean): YellowPageData {
        if (!forceRefresh) {
            cache?.read()?.let { return it }
        }

        return try {
            val listJson = getJson("$baseUrl/site/schoolePage/getList")
            val (categories, departments) = parseListBody(listJson)
            val updateTime = runCatching {
                parseUpdateTime(getJson("$baseUrl/site/schoolePage/getUpdateTime"))
            }.getOrDefault("")
            YellowPageData(categories, departments, updateTime).also {
                cache?.write(it)
            }
        } catch (e: Exception) {
            cache?.readStale()?.let { return it }
            throw e
        }
    }

    /** 解析 getList 的响应体。 */
    internal fun parseListBody(root: kotlinx.serialization.json.JsonObject): Pair<List<YellowPageCategory>, List<YellowPageDepartment>> {
        val data = root.obj("d")
            ?: throw RuntimeException("黄页接口缺少数据")
        val categories = AppJson.decodeFromJsonElement<List<YellowPageCategory>>(data.requireArr("categories"))
            .filter { it.status == 1 }
            .sortedWith(compareBy({ it.sort }, { it.id }))
        val departments = AppJson.decodeFromJsonElement<List<YellowPageDepartment>>(data.requireArr("departments"))
            .filter { it.status == 1 }
            .sortedWith(compareBy({ it.sort }, { it.id }))
        return categories to departments
    }

    /** 解析 getUpdateTime 的响应体为「2026年08月01日」。 */
    internal fun parseUpdateTime(root: kotlinx.serialization.json.JsonObject): String {
        val raw = root.obj("d")
            ?.get("page_update_time")
            ?.safeString()
            .orEmpty()
        return LocalDateTime.parse(raw).let { "${it.year}年${pad2(it.month.ordinal + 1)}月${pad2(it.day)}日" }
    }


    private suspend fun getJson(url: String) = client.get(url) {
        header("Accept", "application/json")
        header("Referer", "https://workflow.xjtu.edu.cn/selectpage/page/site/yellowPage")
    }.let { response ->
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // 只带状态码，不让上游 HTML 混进用户可见文案
            throw RuntimeException("黄页接口 HTTP ${response.status.value}")
        }
        AppJson.parseToJsonElement(body).jsonObject.also {
            if (it.safeGet("e").safeInt(0) != 0) {
                throw RuntimeException(it.safeGet("m").safeString("黄页接口返回错误"))
            }
        }
    }

    private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()

    companion object {
        const val BASE_URL = "https://workflow.xjtu.edu.cn/selectpage"

        /** :app 用的缓存键与 TTL，原样保留（见 DataCache 适配器）。 */
        const val CACHE_KEY = "yellow_page"
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
