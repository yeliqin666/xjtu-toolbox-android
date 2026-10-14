package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.dormpower.DormPowerSource
import com.xjtu.toolbox.dormpower.DormRoom
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject

/**
 * 宿舍电费（`ssn.xjtu.edu.cn/cems`）的 **serve 版取数**：给 `:web` 用，实现 [DormPowerSource]。
 *
 * | 端点 | 本类实现的方法 |
 * |---|---|
 * | `GET /api/dorm/rooms` | [rooms]（已绑定的宿舍列表） |
 * | `GET /api/dorm/reading` | [kwh]（`?roomId=`） |
 *
 * 契约 §5.5；`:server` 那一侧是 `DormPowerRoutes.kt`（薄包装 `:data` 的 `DormPowerApi`）。
 *
 * ## 「缴费页入口」没有端点（这一屏最大的缺口）
 *
 * [freshPayUrl] **不端**：它每次都要重走一遍 OAuth、拦下带 code 的那一跳，而且顺手记下
 *「用户进过这一页」（`DormPowerStore.markUsed`）—— 那是宿主侧状态，serve 这一层不替浏览器记。
 * 所以浏览器里那个「去缴费」跳不过去（[freshPayUrl] 抛错，`:web` 的读数页也不画那一跳）。
 * 绑定 / 解绑 / 充值同理：那几件事发生在缴费页里，是浏览器端的事。
 *
 * ## `kwh` 的 `null` 与「字段缺失」是两件事（§4）
 *
 * - `kwh: null` = **上游明确说这间房没有表**（不是 `0`，也不是「查不到」）⇒ 原样给 `null`；
 * - `kwh` **字段缺失** = 这个端点没按契约答 ⇒ 抛错（不拿 `null` 冒充「没有表」，那是把形状错误
 *   说成一个业务事实）。
 *
 * ## 没有时间戳
 *
 * 响应里**没有**「这张读数是什么时候的」：`:core` 的 `DormReading.at` 是 `DormPowerStore` 在
 * 本机记的 ⇒ 本层不编一个（§4：时间一律 ISO-8601，编一个反而是第二个口径）。要那一格由消费方
 * 在**自己拿到响应的那一刻**记（`:web` 的读数页就是这么做的）。
 *
 * ## 默认形态（campus-api）
 *
 * 宿舍电费在 campus-api 里有**另一个**端点（`GET /api/dorm/kwh?roomId=`，一个端点把列表与读数
 * 混在一处；见契约 §5.5），但本类不是它的客户端 —— 非 serve 分支一律 [serveOnly]（抛错，
 * 不返回一份空数据）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
class CampusDormPowerApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.5）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : DormPowerSource {

    /** 见类 KDoc：缴费页入口是宿主的活（OAuth + 「进过这一页」那个标记），契约 §5.5 不端。 */
    override suspend fun freshPayUrl(): String = throw UnsupportedOperationException(
        "serve 契约没有「缴费页入口」（freshPayUrl）的端点：它每次要重走一遍 OAuth、拦下带 code 的那一跳，" +
            "而且顺手记「用户进过这一页」（DormPowerStore.markUsed）—— 那是宿主侧状态，serve 这一层不替" +
            "浏览器记（docs/api-contract.md §5.5）⇒ 浏览器里那个「去缴费」跳不过去。绑定 / 解绑 / 充值同理。",
    )

    override suspend fun rooms(): List<DormRoom> {
        requireServe()
        val data = client.serveData("加载宿舍列表", baseUrl, ROOMS_PATH)
        return data.arr("rooms").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = row["id"].safeString().trim()
            // 上游缺 `roomId` 的行由 `:data` 就丢掉了（不是投影成空 id）—— 这里同样不列空 id 的行
            if (id.isEmpty()) return@mapNotNull null
            DormRoom(id = id, name = row["name"].safeString())
        }
    }

    override suspend fun kwh(roomId: String): Double? {
        requireServe()
        val data = client.serveData("加载宿舍电量", baseUrl, READING_PATH, listOf("roomId" to roomId))
        // 见类 KDoc：`kwh` 缺失 = 形状没对上（抛错），`kwh: null` = 这间房没有表（原样给 null）
        if (!data.containsKey("kwh")) {
            error("加载宿舍电量：服务端返回缺少 kwh（契约 §5.5）")
        }
        return data["kwh"].safeDoubleOrNull()
    }

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("宿舍电费取数", "$ROOMS_PATH + $READING_PATH")
    }
}

/** 已绑定的宿舍列表（契约 §5.5）。 */
private const val ROOMS_PATH = "/api/dorm/rooms"

/** 某间宿舍的剩余电量（`?roomId=` 必填，取自 [rooms] 的 `id`）。 */
private const val READING_PATH = "/api/dorm/reading"
