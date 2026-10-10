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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Instant

/**
 * 黄页的 **campus-api 版取数**：给 Web 端用（Android 端仍直连学校，见 `YellowPageApi`）。
 *
 * 为什么必须有它：浏览器**不能**直连 `workflow.xjtu.edu.cn` —— 学校服务不给
 * `Access-Control-Allow-Origin`，campus-api 也刻意不给零鉴权端点发 CORS 头。所以 Web 端的唯一
 * 数据路径是同源反代（开发=webpack dev-server，生产=nginx）到 campus-api。屏幕（[com.xjtu.toolbox.yellowpage.YellowPageScreen]）
 * 只认识 [YellowPageSource] 这个端口，两个端各自注入实现。
 *
 * 两个形状的差异（照实记，别以为是同一个 JSON）：
 * - 学校：`{"d":{"categories":[…],"departments":[…],"page_update_time":"2026-08-01T00:00:00"}}`；
 * - campus-api：`{"http":200,"url":…,"code":0,"data":{"categories":[…],"departments":[…],"fetchedAt":"2026-10-07T15:32:54.121Z"}}`
 *   —— 字段已归一化（有 `categoryId`/`phone`、无 `sort`），更新时间是**抓取时刻**的 ISO instant。
 * 所以这里做的是「信封拆包 + 过滤/排序口径对齐」，**不做解析重写**：解码仍走同一套
 * [YellowPageCategory]/[YellowPageDepartment] 模型。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
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
            // 与 :app 的 YellowPageApi.parseListBody 同一套口径（status==1，按 (sort,id) 升序），
            // 否则同一个黄页在两端会是不同顺序 —— 「完全一致」包括顺序
            categories = categories.filter { it.status == 1 }.sortedWith(compareBy({ it.sort }, { it.id })),
            departments = departments.filter { it.status == 1 }.sortedWith(compareBy({ it.sort }, { it.id })),
            updateTime = formatFetchedAt(data["fetchedAt"].safeString()),
        )
    }

    /**
     * serve 模式（契约 §5.2）：`{updateTime, categories:[{id,name}], departments:[{id,categoryId,name,phone}]}`。
     *
     * 三处与旧路的差别，都是**契约明写**的：
     *  - 服务端已经滤掉 `status != 1` 的项（也不投影 `status`/`sort`）⇒ 这里不再过滤、不再排序；
     *  - `updateTime` 是 [YellowPageData.updateTime] 那个展示串（「2026年08月01日」，`:data` 解析出的
     *    同一份），不是 ISO instant ⇒ 不做时间格式化；
     *  - 解码仍走同一套 [YellowPageCategory]/[YellowPageDepartment] 模型 —— 少了两个字段时靠模型
     *    的默认值吃下（`status=0`、`sort=0`），所以那条 filter/sort 在这里没有意义，不重复做一遍。
     */
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

    /**
     * `2026-10-07T15:32:54.121Z` → `2026年10月07日`（与学校那侧的 `page_update_time` 同一种展示）。
     * 解析不了就返回空串（屏幕对空串的处理与「接口没给时间」一致）。
     */
    private fun formatFetchedAt(raw: String): String = runCatching {
        val local = Instant.parse(raw).toLocalDateTime(TimeZone.currentSystemDefault())
        "${local.year}年${pad2(local.month.ordinal + 1)}月${pad2(local.day)}日"
    }.getOrDefault("")

    private fun pad2(value: Int): String = value.toString().padStart(2, '0')
}
