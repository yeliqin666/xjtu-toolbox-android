package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.emptyroom.EmptyRoomSource
import com.xjtu.toolbox.emptyroom.LiveSnapshot
import com.xjtu.toolbox.emptyroom.NoDataException
import com.xjtu.toolbox.emptyroom.RoomInfo
import com.xjtu.toolbox.emptyroom.RoomSource
import com.xjtu.toolbox.util.todayInSystemZone
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.requireArr
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/**
 * 空闲教室的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 `AppEmptyRoomSource`）。
 *
 * ## 为什么 Web 只有 CDN 那一档
 * `:app` 有三档：智慧教室实时状态 / CDN 预生成课表 / 直查教务。campus-api 只归一了其中一档：
 * - `/api/emptyroom/cdn` —— 一次给全校区当天的**全部**教室（`{campus, building, room, seats, status[11], free[]}`），
 *   这正是 [RoomInfo]（`name`/`size`/`status`）的形状，一一对应，不用再解析 ⇒ 本类实现它；
 * - 直查教务那一档用的是 `/api/emptyroom/rooms`，可它是「某节次范围内空闲」的**投影**
 *   （`freeSections: "1-2节"`），**拿不到逐节的 `status[11]`**（要 11 次请求/楼）⇒ Web 不做这一档，
 *   屏上那一档也不出现（[availableSources]）；
 * - 智慧教室实时状态 campus-api **没有对应端点** ⇒ 同样不做。
 *
 * 所以这里 [liveSnapshot] / `direct=true` 抛异常、[readStaleLive] 返回 null ——
 * **不是"先占个位"，而是"本端确实没有这条数据"**：屏靠 [availableSources] 只画 CDN 那一档，
 * 根本不会调它们；真被调到（说明屏那边的心智模型坏了）就报一句能看懂的话，而不是给一份编的数据。
 *
 * ## 与 :app 的 CDN 档等价、且更省请求
 * `:app` 是「按楼查询 + 单楼缓存」（每天每楼一次请求）；campus-api 已经把整天全校区归一好了，
 * 所以本类**一次拉全天全校区、按日期内存缓存、楼的筛选在本地做**（[rooms]）—— 结果一样，
 * 请求数是 1 而不是 N。
 *
 * ## 座位数缺失用 0，与 :app 同口径
 * 上游偶有教室没有 `seats`（实测 948 间里 3 间），campus-api 原样投影成 `null`。
 * `:app` 的 CDN 解析对 `size` 缺失/为 null 就是落 0（`obj.get("size")?.let { … } ?: 0`），
 * 这里照抄同一条口径 —— 两端看同一份上游数据时显示的座位数必须一致，而不是一端空着、一端写 0。
 * （[RoomInfo] 是 `@Serializable`、形状不能改，也没有"未知座位"这一档，所以这不是新决定。）
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
class CampusEmptyRoomApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
) : EmptyRoomSource {

    /**
     * 本端只有 CDN 这一档。屏据此把「实时状态」「直查教务」两项整个不画 ——
     * 它们在这端不是"暂时失败"，是"根本没有这条数据"。
     */
    override val availableSources: List<RoomSource> = listOf(RoomSource.CDN)

    /** CDN 是预生成的按天快照，只有今天/明天两档（与 `EmptyRoomApi.getAvailableDates()` 同口径）。 */
    override fun availableDates(): List<String> {
        val today = todayInSystemZone()
        return listOf(today.toString(), today.plus(1, DateTimeUnit.DAY).toString())
    }

    override suspend fun liveSnapshot(campus: String, force: Boolean): LiveSnapshot =
        throw RuntimeException("本端只有 CDN 课表可用（campus-api 没有智慧教室实时端点）")

    override fun readStaleLive(campus: String): LiveSnapshot? = null

    override suspend fun rooms(
        campus: String,
        buildings: Set<String>,
        date: String,
        direct: Boolean,
        force: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<RoomInfo> {
        requireCdn(direct)
        if (buildings.isEmpty()) return emptyList()
        // 直查那种"逐楼逐节、报进度"的形态这里不存在：一次请求就拿到整天的数据，不调 onProgress。
        return dayRooms(date, force)
            .filter { it.campus == campus && it.building in buildings }
            .map { RoomInfo(name = it.room, size = it.seats, status = it.status) }
            .sortedBy { it.name }
    }

    /**
     * 联网失败时的兜底：把**本次会话已经拉过的那一份**当缓存给它（带上当初拿到的时刻，
     * 屏上写"缓存于 HH:mm"）。Web 端没有磁盘缓存（`localStorage` 里不放这种一天一换的大块数据），
     * 所以刷新页面后就没有兜底了 —— 如实降级，而不是造一个"缓存"。
     */
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

    /**
     * 教室名 → 座位数。**只查已经拉过的那一份当天数据**，不为一个座位数再打一次请求
     * （`:app` 那边为它单开了一次全校拉取 + 一年的座位缓存，是因为课表详情页每次点开都要用；
     * Web 的课表是另一条切片，这里按"拿得到就给、拿不到就 null"处理）。
     */
    override suspend fun seatCount(roomName: String): Int? {
        if (roomName.isBlank()) return null
        return cachedRooms.firstOrNull { it.room == roomName }?.seats?.takeIf { it > 0 }
    }

    private fun requireCdn(direct: Boolean) {
        if (direct) throw RuntimeException("本端只有 CDN 课表可用，没有直查教务这一档")
    }

    /**
     * 某天全校区全楼的教室行（[cachedDate] 命中就不重复请求，[force] 绕过）。
     * 越出今天/明天（campus-api 拿不到数据）时按 [NoDataException] 报 —— 与 `:app` 的 CDN 404 同义：
     * "这一天没有数据"（直接报错、不兜底缓存），而不是"请求失败"（那才回退缓存）。
     */
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
                // 见类 KDoc：座位缺失落 0，与 :app 的 CDN 解析同口径
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

}
