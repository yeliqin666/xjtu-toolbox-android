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

/**
 * 黄页的**缓存**能力切口。
 *
 * 原本 `YellowPageApi` 直接持有 :app 的 `DataCache`（Context + 文件 + 按账号隔离的目录），
 * 那套是 Android 专属的。搬进 commonMain 后把它收成一个接口：
 * Android 侧仍用 `DataCache`（同一个 key、同一个 TTL，行为逐字一致），
 * 其余端可以传 null（不缓存，直接请求）或将来接自己的实现。
 */
interface YellowPageCache {
    /** 未过期（TTL 内）的缓存，没有返回 null。 */
    fun read(): YellowPageData?

    /** 不看 TTL 的兜底缓存，网络失败时用。 */
    fun readStale(): YellowPageData?

    fun write(data: YellowPageData)
}

/**
 * 黄页（`workflow.xjtu.edu.cn/selectpage` 的机构通讯录）的只读接口。
 *
 * 从 :app 的 `yellowpage/YellowPageApi.kt` 搬进 commonMain，并**只把传输层从 okhttp 换成
 * Ktor**（第 1 步 okhttp→Ktor 的一处）：接口路径、请求头、错误文案、"优先缓存 / 失败回退旧缓存"
 * 的顺序都逐字保留；`Context` 依赖通过 [YellowPageCache] 注入。
 *
 * @param cache null 表示不缓存。
 * @param baseUrl 测试可注入；默认与 :app 一致。
 */
class YellowPageApi(
    private val client: HttpClient,
    private val cache: YellowPageCache? = null,
    private val baseUrl: String = BASE_URL,
) {
    suspend fun getData(forceRefresh: Boolean = false): YellowPageData {
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

    /**
     * 解析 getList 的响应体：取 `d` 下的 categories / departments，各自只留 status==1，
     * 再按 (sort, id) 升序。抽成 internal 纯函数是为了让“换 Ktor 但解析逐字不变”这件事
     * 能被 commonTest 钉住（跨端回归网）。
     */
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

    /**
     * 解析 getUpdateTime 的响应体为「2026年08月01日」。
     *
     * 原本是 `java.time.LocalDateTime.parse(raw).format("yyyy年MM月dd日")`；
     * kotlinx-datetime 的解析口径与 ISO_LOCAL_DATE_TIME 一致，格式化手写补零。
     * 解析不了（拿不到字段、格式不对）时抛出，由调用方 runCatching 成 ""——与搬迁前一致。
     */
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
            // 与 :app 逐字一致：只带状态码，不让上游 HTML 混进用户可见文案
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
