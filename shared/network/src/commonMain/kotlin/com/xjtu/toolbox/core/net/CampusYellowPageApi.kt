package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.yellowpage.YellowPageCategory
import com.xjtu.toolbox.yellowpage.YellowPageData
import com.xjtu.toolbox.yellowpage.YellowPageDepartment
import com.xjtu.toolbox.yellowpage.YellowPageSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Instant
import com.xjtu.toolbox.util.CampusTimeZone

/** 黄页的 campus-api 版取数：给 Web 端用（Android 端仍直连学校，见 `YellowPageApi`）。 */
class CampusYellowPageApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.2 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : YellowPageSource {

    override suspend fun getData(forceRefresh: Boolean): YellowPageData {
        // serve 契约（§5.2）的形状与 campus-api **不是同一份投影**（见 serveYellowPage 的 KDoc）
        if (mode == ApiMode.SERVE) return serveYellowPage()
        val text = client.get("$baseUrl/api/info/yellowpage").bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 黄页返回不是 JSON 对象")
        val data = envelope["data"] as? JsonObject
            ?: error("campus-api 黄页返回缺少 data：${text.take(120)}")
        val categories = decodeList<YellowPageCategory>(data["categories"])
        val departments = decodeList<YellowPageDepartment>(data["departments"])
        return YellowPageData(
            // 与 :app 的 YellowPageApi.parseListBody 同序（status==1，按 (sort,id) 升序）
            categories = categories.filter { it.status == 1 }.sortedWith(compareBy({ it.sort }, { it.id })),
            departments = departments.filter { it.status == 1 }.sortedWith(compareBy({ it.sort }, { it.id })),
            updateTime = formatFetchedAt(data["fetchedAt"].safeString()),
        )
    }

    /** serve 模式（契约 §5.2） */
    private suspend fun serveYellowPage(): YellowPageData {
        val data = client.serveData("加载黄页", baseUrl, "/api/info/yellowpage")
        return YellowPageData(
            categories = decodeList(data["categories"]),
            departments = decodeList(data["departments"]),
            updateTime = data["updateTime"].safeString(),
        )
    }

    private inline fun <reified T> decodeList(element: JsonElement?): List<T> =
        if (element == null) emptyList()
        else AppJson.decodeFromJsonElement<List<T>>(element)

    /** `2026-10-07T15:32:54.121Z` → `2026年10月07日`（与学校那侧的 `page_update_time` 同一种展示）。 */
    private fun formatFetchedAt(raw: String): String = runCatching {
        val local = Instant.parse(raw).toLocalDateTime(CampusTimeZone)
        "${local.year}年${pad2(local.month.ordinal + 1)}月${pad2(local.day)}日"
    }.getOrDefault("")

    private fun pad2(value: Int): String = value.toString().padStart(2, '0')
}
