package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.emptyroom.EmptyRoomSource
import com.xjtu.toolbox.emptyroom.LiveRoom
import com.xjtu.toolbox.emptyroom.LiveSnapshot
import com.xjtu.toolbox.emptyroom.NoDataException
import com.xjtu.toolbox.emptyroom.RoomInfo
import com.xjtu.toolbox.emptyroom.RoomSource
import com.xjtu.toolbox.util.todayOnCampus
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.requireArr
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeInt
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Instant

/** 空闲教室的 campus-api 版取数：给 Web 端用（Android 端走 `:app` 的 `AppEmptyRoomSource`）。 */
class CampusEmptyRoomApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.2 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : EmptyRoomSource {

    /** 本端能提供哪几档，屏据此画「数据源」菜单（见 [EmptyRoomSource.availableSources]）。 */
    override val availableSources: List<RoomSource>
        get() = if (mode == ApiMode.SERVE) serveSources else listOf(RoomSource.CDN)

    /** serve 模式最后一次响应报的档位；默认是最保守的那一档（见 [availableSources]）。 */
    private var serveSources: List<RoomSource> = listOf(RoomSource.CDN)

    /** CDN 是预生成的按天快照，只有今天/明天两档。 */
    override fun availableDates(): List<String> {
        val today = todayOnCampus()
        return listOf(today.toString(), today.plus(1, DateTimeUnit.DAY).toString())
    }

    override suspend fun liveSnapshot(campus: String, force: Boolean): LiveSnapshot {
        if (mode != ApiMode.SERVE) {
            throw RuntimeException("本端只有 CDN 课表可用（campus-api 没有智慧教室实时端点）")
        }
        val data = getData(
            "/api/emptyroom/rooms",
            "source" to "live",
            "campus" to campus,
            "force" to if (force) "1" else "",
        )
        refreshServeSources(data)
        return LiveSnapshot(
            campus = campus,
            buildings = data.arr("buildings").orEmpty().map { it.safeString() },
            rooms = data.arr("rooms").orEmpty().mapNotNull { element ->
                val room = element as? JsonObject ?: return@mapNotNull null
                LiveRoom(
                    name = room["name"].safeString(),
                    building = room["building"].safeString(),
                    status = room["status"].safeInt(),
                    people = room["people"].safeInt(),
                    seats = room["seats"].safeInt(),
                    course = room["course"].safeString().takeIf { it.isNotBlank() },
                    teacher = room["teacher"].safeString().takeIf { it.isNotBlank() },
                )
            },
            // 服务端给了抓取时刻就用它；没给时用「本端此刻拿到」—— 我们确实是刚拿到这一份
            //（模型 KDoc 区分的是「读落盘缓存」那种情形，这里没有缓存可读）
            fetchedAt = parseIsoMillis(data["fetchedAt"].safeString()) ?: Clock.System.now().toEpochMilliseconds(),
        )
    }

    override fun readStaleLive(campus: String): LiveSnapshot? = null

    override suspend fun rooms(
        campus: String,
        buildings: Set<String>,
        date: String,
        direct: Boolean,
        force: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<RoomInfo> {
        // serve 的 CDN / 直查两档都能真取数（见 serveRooms 的 KDoc）
        if (mode == ApiMode.SERVE) return serveRooms(campus, buildings, date, direct, force, onProgress)
        requireCdn(direct)
        if (buildings.isEmpty()) return emptyList()
        // 直查那种"逐楼逐节、报进度"的形态这里不存在：一次请求就拿到整天的数据，不调 onProgress。
        return dayRooms(date, force)
            .filter { it.campus == campus && it.building in buildings }
            .map { RoomInfo(name = it.room, size = it.seats, status = it.status) }
            .sortedBy { it.name }
    }

    /** 联网失败时的兜底：本次会话已经拉过的那一份（带当初拿到的时刻）。 */
    override fun readStaleRooms(
        campus: String,
        buildings: Set<String>,
        date: String,
        direct: Boolean,
    ): Pair<List<RoomInfo>, Long>? {
        if (direct) return null
        if (date != cachedDate || cachedAt <= 0L) return null
        val rooms = cachedRooms
            .filter { it.campus == campus && it.building in buildings }
            .map { RoomInfo(name = it.room, size = it.seats, status = it.status) }
            .sortedBy { it.name }
        // 空的不算兜底：屏要靠"有没有数据"决定是显示缓存还是整页报错（与 :app 的 fallbackToStale 同一判据）
        return if (rooms.isEmpty()) null else rooms to cachedAt
    }

    /** 教室名 → 座位数（课表详情里「N座」那一格）。 */
    override suspend fun seatCount(roomName: String): Int? {
        if (roomName.isBlank()) return null
        if (mode == ApiMode.SERVE) {
            val room = roomName.trim()
            val data = getData("/api/emptyroom/seat-count", "room" to room)
            val seats = data["seats"] ?: error("serve 座位数响应缺少 seats（$room）")
            return if (seats.isNull) null else seats.intValue
        }
        return cachedRooms.firstOrNull { it.room == roomName }?.seats?.takeIf { it > 0 }
    }

    private fun requireCdn(direct: Boolean) {
        if (direct) throw RuntimeException("本端只有 CDN 课表可用，没有直查教务这一档")
    }

    /** 某天全校区全楼的教室行（[cachedDate] 命中就不重复请求，[force] 绕过）。 */
    private suspend fun dayRooms(date: String, force: Boolean): List<RoomRow> {
        if (!force && date == cachedDate && cachedAt > 0L) return cachedRooms
        val data = getData("/api/emptyroom/cdn", "date" to date)
        if (data["noData"].safeBoolean()) {
            // campus-api 的 note 就是它自己的话（"该日期暂无数据（CDN 404）"），原样往上报
            throw NoDataException(data["note"].safeString().ifBlank { "当天暂无空闲教室数据，请稍后再试" })
        }
        val rows = data.arr("rooms").orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val room = obj["room"].safeString().trim()
            if (room.isEmpty()) return@mapNotNull null
            val status = runCatching { obj.requireArr("status").map { it.intValue } }.getOrNull()
                ?: return@mapNotNull null
            RoomRow(
                campus = obj["campus"].safeString(),
                building = obj["building"].safeString(),
                room = room,
                seats = obj["seats"]?.let { if (it.isNull) 0 else it.intValue } ?: 0,
                status = status,
            )
        }
        cachedDate = date
        cachedAt = Clock.System.now().toEpochMilliseconds()
        cachedRooms = rows
        return rows
    }

    /** 整天全校区的一份，按日期缓存（[Clock] 取到的时刻就是屏上"缓存于 HH:mm"的那个时刻）。 */
    private var cachedDate: String? = null
    private var cachedAt: Long = 0L
    private var cachedRooms: List<RoomRow> = emptyList()

    /** campus-api 的 `rooms[]` 一行；字段名与上游一致（`seats` 可能是 null，见类 KDoc）。 */
    private data class RoomRow(
        val campus: String,
        val building: String,
        val room: String,
        val seats: Int,
        val status: List<Int>,
    )


    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        // serve 契约（§4）的信封见 ApiMode / serveData：code == HTTP 状态码、失败文案在 message
        if (mode == ApiMode.SERVE) return client.serveData("加载空闲教室", baseUrl, path, query.toList())
        val text = client.get("$baseUrl$path") {
            query.forEach { (k, v) -> if (v.isNotEmpty()) parameter(k, v) }
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 空闲教室返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            error("campus-api 空闲教室失败：${envelope["msg"].safeString().ifBlank { envelope["error"].safeString() }}")
        }
        return envelope["data"] as? JsonObject
            ?: error("campus-api 空闲教室返回缺少 data：${text.take(120)}")
    }

    // ─── serve 模式（契约 §5.2）────────────────────────────────────────────

    /** CDN / 直查两档（实时那一档见 [liveSnapshot]）：一次请求拿到整天（CDN）或整批楼（直查）。 */
    private suspend fun serveRooms(
        campus: String,
        buildings: Set<String>,
        date: String,
        direct: Boolean,
        force: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<RoomInfo> {
        if (buildings.isEmpty()) return emptyList()
        val requested = buildings.sorted()
        val data = if (direct) {
            getData(
                "/api/emptyroom/rooms",
                "source" to "direct",
                "campus" to campus,
                "date" to date,
                "building" to requested.joinToString(","),
                "force" to if (force) "1" else "",
            )
        } else {
            getData(
                "/api/emptyroom/cdn",
                "campus" to campus,
                "date" to date,
                "force" to if (force) "1" else "",
            )
        }
        refreshServeSources(data)
        if (data["noData"].safeBoolean()) {
            throw NoDataException(data["note"].safeString().ifBlank { "当天暂无空闲教室数据，请稍后再试" })
        }
        onProgress(requested.size, requested.size)
        return parseServeRoomRows(data)
            .filter { it.campus == campus && it.building in buildings }
            .map { RoomInfo(name = it.room, size = it.seats, status = it.status) }
            .sortedBy { it.name }
    }

    /** serve 的 `rooms[]`（`{campus,building,room,seats,status[11]}`）→ 内部的 [RoomRow]。 */
    private fun parseServeRoomRows(data: JsonObject): List<RoomRow> =
        data.arr("rooms").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val room = row["room"].safeString().trim()
            if (room.isEmpty()) return@mapNotNull null
            val status = runCatching { row.requireArr("status").map { it.intValue } }.getOrNull()
                ?: return@mapNotNull null
            RoomRow(
                campus = row["campus"].safeString(),
                building = row["building"].safeString(),
                room = room,
                seats = row["seats"]?.let { if (it.isNull) 0 else it.intValue } ?: 0,
                status = status,
            )
        }

    /** 把响应里的能力开关 `availableSources:[{key,name}]` 刷进 [serveSources]（键 = [RoomSource.key]）。 */
    private fun refreshServeSources(data: JsonObject) {
        val keys = data.arr("availableSources").orEmpty().mapNotNull { element ->
            val key = (element as? JsonObject)?.get("key").safeString()?.trim()
            RoomSource.entries.firstOrNull { it.key == key }
        }
        if (keys.isNotEmpty()) serveSources = keys
    }

    /** 契约 §4 的 ISO-8601 带时区时间 → epoch 毫秒；空串/解不出给 null（兜底交给调用方）。 */
    private fun parseIsoMillis(raw: String): Long? =
        raw.takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }


}
