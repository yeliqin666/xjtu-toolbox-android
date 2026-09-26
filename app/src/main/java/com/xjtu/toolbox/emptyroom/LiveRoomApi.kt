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

/**
 * 实时状态里的一间教室，来自智慧教室平台 `classroomStatus/classroomStatusList`。
 *
 * 会随屁岱的卡片（[com.xjtu.toolbox.agent.LiveRoomWidget]）存进会话记录，字段名即存盘格式。
 */
@kotlinx.serialization.Serializable
data class LiveRoom(
    /** 教室全名，如 "东1东-303"、"1-2050"，和 CDN / 教务的教室名同一套写法。 */
    val name: String = "",
    /** 楼名，已换成 App 里的叫法（创新港 "1" → "1号巨构"），见 [liveBuildingName]。 */
    val building: String = "",
    /** [LiveRoomStatus] 之一。 */
    val status: Int = 0,
    /** 当前人数。使用中 = 平台统计的在场人数；上课中 = 这门课的人数；空闲为 0。 */
    val people: Int = 0,
    val seats: Int = 0,
    val course: String? = null,
    val teacher: String? = null,
) {
    val isFree: Boolean get() = status == LiveRoomStatus.FREE
    val isInUse: Boolean get() = status == LiveRoomStatus.IN_USE
    val isInClass: Boolean get() = status == LiveRoomStatus.IN_CLASS
}

/**
 * 平台的 status 取值（前端 chunk 里写死的）：
 * - 1 使用中：没排课，但有人（平台给出人数，实测大多 1–2 人；平台不区分自习、社团借用还是活动，界面上叫「其它使用」）
 * - 2 空闲
 * - 3 上课中：有课程名、教师、这门课的人数
 * - 0 前端有对应样式但抓包里没出现过，按"未知"处理，不当成空闲。
 */
object LiveRoomStatus {
    const val UNKNOWN = 0
    const val IN_USE = 1
    const val FREE = 2
    const val IN_CLASS = 3
}

/** 一个校区某一刻的整体快照。平台一次就把整个校区全给了，楼的筛选在本地做。 */
data class LiveSnapshot(
    val campus: String,
    /** 平台给的楼顺序（已换成 App 叫法）。 */
    val buildings: List<String>,
    val rooms: List<LiveRoom>,
    /** 这份数据是什么时候从服务器拿到的（毫秒）。读缓存时是当初的时间，不是读盘时间。 */
    val fetchedAt: Long,
) {
    val freeCount: Int get() = rooms.count { it.isFree }
    val inUseCount: Int get() = rooms.count { it.isInUse }
    val inClassCount: Int get() = rooms.count { it.isInClass }
}

/**
 * App 校区名 → 平台校区名。平台只有这三个校区（getBuildingByType 返回的就是这三个），
 * 曲江、苏州没有实时数据。
 */
val LIVE_CAMPUSES: Map<String, String> = linkedMapOf(
    "兴庆校区" to "兴庆校区",
    "雁塔校区" to "雁塔校区",
    "创新港校区" to "创新港",
)

/**
 * 平台楼名 → App 楼名。
 *
 * 2026-09-22 同一天对照 CDN 数据的结果（写在这里免得以后再比一遍）：
 * - 教室名两边一致（"东1东-303"、"1-2050"），可以直接按名字对上课表；
 * - 兴庆、雁塔楼名一致；创新港平台叫 "1"、"2"、"18"，App 叫 "1号巨构"……；
 * - 平台比 CDN 少：兴庆 328/453 间、创新港 311/341、雁塔 108/129。仲英楼、中1、主楼E座、
 *   计教中心、田家炳、工程坊，创新港的 9/21 号巨构、图书馆、绿楔和运动场，雁塔的附院教学楼、
 *   卫法楼都不在平台上；曲江、苏州整个校区没有。都是特殊场地，接受；
 * - 平台多出来的：国防中心、西1楼、音乐教室，以及 CDN 里没有的十几间（如 1-2054、3-2026）；
 * - 两边都有的教室里座位数不同的：兴庆 6 间、创新港 14 间，雁塔一致——以平台为准；
 * - 状态对得上：平台"上课中"和 CDN 当节占用吻合（兴庆 100/100、创新港 30/32），
 *   平台多出来的信息是"使用中"——课表上是空的，但实际有人（当时兴庆 92 间）。
 */
fun liveBuildingName(campus: String, raw: String): String {
    val trimmed = raw.trim()
    if (campus == "创新港校区" && trimmed.isNotEmpty() && trimmed.all { it.isDigit() }) return "${trimmed}号巨构"
    return trimmed
}

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
