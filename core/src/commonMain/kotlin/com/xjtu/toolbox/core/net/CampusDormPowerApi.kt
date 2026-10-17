package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.dormpower.DormPowerSource
import com.xjtu.toolbox.dormpower.DormRoom
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject

/** 宿舍电费（`ssn.xjtu.edu.cn/cems`）的 serve 版取数：给 `:web` 用，实现 [DormPowerSource]。 */
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
