package com.xjtu.toolbox.emptyroom

import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.isObject
import com.xjtu.toolbox.util.isArray
import com.xjtu.toolbox.util.isPrimitive
import com.xjtu.toolbox.util.arr
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
import com.xjtu.toolbox.auth.JsLogin
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

// ⚠️ 这里的 `LiveRoom` / `LiveRoomStatus` / `LiveSnapshot` / `LIVE_CAMPUSES` / `liveBuildingName`
// 已搬进 `:core` 的 `com.xjtu.toolbox.emptyroom`（`EmptyRoomModels.kt`）—— 屏与 ViewModel 进了
// `:core`，两端必须共用同一份模型。本文件只剩**实时状态的取数实现**，
// 由 :app 的 `AppEmptyRoomSource` 包成 `EmptyRoomSource` 端口；**取数一行未改**。

/**
 * 实时状态查询。只有"此刻"：接口虽然有 dayTime 参数，但网页从不赋值，给的永远是当前快照，
 * 所以今天/明天、指定节次仍走 CDN / 直查教务。
 */
class LiveRoomApi(private val site: SiteSession, private val cache: EmptyRoomCache? = null) {

    /**
     * 取一个校区的快照。[maxAgeMs] 内的缓存直接用：同一校区切楼、下拉之外的重组都不必再打平台。
     * 下拉刷新传 0。
     */
    suspend fun fetchCampus(campus: String, maxAgeMs: Long = FRESH_MS): LiveSnapshot {
        val serverCampus = LIVE_CAMPUSES[campus] ?: throw NoDataException("${campus}暂无实时数据")
        if (maxAgeMs > 0) readCached(campus, maxAgeMs)?.let { return it }

        val url = "${JsLogin.BASE_URL}/server/classroomStatus/classroomStatusList".toHttpUrl()
            .newBuilder()
            .addQueryParameter("campusName", serverCampus)
            .addQueryParameter("dayTime", "")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "${JsLogin.BASE_URL}/roomStatus")
            .build()
        val body = site.executeWithReAuth(request).use { resp ->
            withContext(Dispatchers.IO) {
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw java.io.IOException("实时状态请求失败：HTTP ${resp.code}")
                text
            }
        }
        val now = System.currentTimeMillis()
        val snapshot = parseLiveSnapshot(campus, body, now)
        cache?.let { c ->
            // 存原始响应：解析全靠字符串键，R8 怎么改名都不影响读回来
            withContext(Dispatchers.IO) { c.writeJson(cacheKey(campus), body) }
        }
        return snapshot
    }

    /**
     * 联网失败时的兜底，调用方要把"几点的数据"标出来。
     * 只认 [STALE_MS] 以内的：实时状态过几个小时就是另一回事了，隔天的更是误导。
     */
    fun readStale(campus: String): LiveSnapshot? = readCached(campus, STALE_MS)

    private fun readCached(campus: String, maxAgeMs: Long): LiveSnapshot? {
        val c = cache ?: return null
        val key = cacheKey(campus)
        val savedAt = c.savedAt(key)
        if (savedAt <= 0L) return null
        val age = System.currentTimeMillis() - savedAt
        if (age < 0 || age > maxAgeMs) return null
        val raw = c.readJsonAnyAge(key) ?: return null
        return runCatching { parseLiveSnapshot(campus, raw, savedAt) }.getOrNull()
    }

    companion object {
        /** 平台的数据本身按分钟级刷新，一分钟内重复打没有意义。 */
        const val FRESH_MS = 60_000L

        /** 兜底能接受的最老快照：两小时，大约两节大课。 */
        const val STALE_MS = 2 * 60 * 60_000L

        private fun cacheKey(campus: String) = "live|$campus"
    }
}

/** 解析 classroomStatusList 的响应。code != 0 时抛出，交给调用方报错。 */
internal fun parseLiveSnapshot(campus: String, body: String, fetchedAt: Long): LiveSnapshot {
    val root = body.safeParseJsonObject()
    val code = root.get("code")?.takeIf { !it.isNull }?.runCatching { intValue }?.getOrNull()
    if (code != 0) {
        val msg = root.get("message")?.takeIf { !it.isNull }?.stringValue
        throw java.io.IOException("实时状态查询失败：${msg ?: "code=$code"}")
    }
    val data = root.get("data")?.takeIf { it.isObject }?.jsonObject
        ?: throw NoDataException("实时状态为空")
    val order = data.arr("buildingData")
        ?.mapNotNull { it.takeIf { e -> !e.isNull }?.stringValue }
        .orEmpty()
    val byBuilding = data.get("classroomStatusData")?.takeIf { it.isObject }?.jsonObject
        ?: throw NoDataException("实时状态为空")

    val rooms = mutableListOf<LiveRoom>()
    // 按平台给的楼顺序走，不在 buildingData 里的楼（理论上不会有）排在后面
    val keys = order + byBuilding.keys.filter { it !in order }
    for (rawBuilding in keys) {
        val arr = byBuilding.get(rawBuilding)?.takeIf { it.isArray }?.jsonArray ?: continue
        val building = liveBuildingName(campus, rawBuilding)
        for (el in arr) {
            val o = el.takeIf { it.isObject }?.jsonObject ?: continue
            val name = o.str("classroomName") ?: continue
            rooms.add(
                LiveRoom(
                    name = name,
                    building = building,
                    status = o.str("status")?.toIntOrNull() ?: LiveRoomStatus.UNKNOWN,
                    people = o.str("studentNum")?.toIntOrNull() ?: 0,
                    seats = o.str("seatNum")?.toIntOrNull() ?: 0,
                    course = o.str("course")?.takeIf { it.isNotBlank() },
                    teacher = o.str("teacherName")?.takeIf { it.isNotBlank() },
                )
            )
        }
    }
    if (rooms.isEmpty()) throw NoDataException("${campus}暂无实时数据")
    val buildings = keys.map { liveBuildingName(campus, it) }.distinct()
        .filter { b -> rooms.any { it.building == b } }
    return LiveSnapshot(campus, buildings, rooms, fetchedAt)
}

/** 数字字段有时是数字有时是字符串（seatNum 就是字符串），统一按字符串取。 */
private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { !it.isNull && it.isPrimitive }?.stringValue?.trim()
