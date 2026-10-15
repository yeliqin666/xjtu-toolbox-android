package com.xjtu.toolbox.emptyroom

import com.xjtu.toolbox.auth.JsSession
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `:core` 的 [EmptyRoomSource] 在 `:data` 里的实现（Android 与桌面侧共用） */
class AppEmptyRoomSource(
    private val sessionManager: SessionManager?,
    /** 本端的宿主存储；`null` = 不缓存（桌面端）。见 [EmptyRoomStore]。 */
    private val store: EmptyRoomStore? = null,
) : EmptyRoomSource {


    /** 默认取数：带磁盘缓存。 */
    private val api = EmptyRoomApi(store)
    private val cache = store

    /** 实时状态：要智慧教室站点会话；没有会话就是 null。 */
    private val liveApi: LiveRoomApi? =
        sessionManager?.getSiteOrNull(JsSession.SITE_KEY)?.let { LiveRoomApi(it, cache) }

    /** Android 与桌面（`store = null`）三档都能提供。 */
    override val availableSources: List<RoomSource> =
        listOf(RoomSource.LIVE, RoomSource.CDN, RoomSource.DIRECT)

    override fun availableDates(): List<String> = api.getAvailableDates()

    override suspend fun liveSnapshot(campus: String, force: Boolean): LiveSnapshot {
        // 报哪句取决于没登录还是没凭据
        val live = liveApi ?: throw RuntimeException("实时状态暂不可用，可在右上角切换到课表数据")
        val manager = sessionManager ?: throw RuntimeException("实时状态暂不可用")
        if (manager.credentials == null) throw RuntimeException("实时状态需要先登录统一身份认证，未登录可在右上角切换到 CDN 课表")
        return withContext(Dispatchers.IO) {
            manager.ensureSite(JsSession.SITE_KEY, userInitiated = true)
            live.fetchCampus(campus, if (force) 0L else LiveRoomApi.FRESH_MS)
        }
    }

    override fun readStaleLive(campus: String): LiveSnapshot? = liveApi?.readStale(campus)

    override suspend fun rooms(
        campus: String,
        buildings: Set<String>,
        date: String,
        direct: Boolean,
        force: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<RoomInfo> {
        if (buildings.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            if (direct) {
                // 入口不先登教务（默认数据源是实时状态），切到直查时才登
                val manager = sessionManager ?: throw RuntimeException("直查教务暂不可用，可切换到 CDN 缓存")
                if (manager.credentials == null) throw RuntimeException("直查教务需要先登录，未登录可切换到 CDN 缓存")
                val client = manager.ensureSite(LoginType.JWXT, userInitiated = true).client
                val query = if (force) EmptyRoomDirectQuery(client) else EmptyRoomDirectQuery(client, cache)
                // 直查是逐楼逐节查的：VM 逐楼调用以便报进度、楼间互不影响，所以这里通常只有一个楼。
                // [onProgress] 报的是**当前这个楼**的 (已查节数, 总节数)，由调用方折算成全局进度。
                val merged = mutableListOf<RoomInfo>()
                buildings.forEach { building ->
                    merged.addAll(query.queryDay(campus, building, date) { period, total -> onProgress(period, total) })
                }
                merged.sortedBy { it.name }
            } else {
                val cdn = if (force) EmptyRoomApi() else api
                cdn.getEmptyRoomsMulti(campus, buildings, date)
            }
        }
    }

    /** 磁盘兜底：把几个楼各自的缓存合并成一份，时间戳取最新的那个。 */
    override fun readStaleRooms(
        campus: String,
        buildings: Set<String>,
        date: String,
        direct: Boolean,
    ): Pair<List<RoomInfo>, Long>? {
        val held = store ?: return null
        val sourceKey = if (direct) "direct" else "cdn"
        val merged = mutableListOf<RoomInfo>()
        var newest = 0L
        for (building in buildings) {
            val key = "$sourceKey|$campus|$building|$date"
            held.readRoomListStale(key)?.let { stale ->
                merged.addAll(stale)
                newest = maxOf(newest, held.savedAt(key))
            }
        }
        if (merged.isEmpty()) return null
        return merged.sortedBy { it.name } to newest
    }

    /** 教室名 → 座位数：`EmptyRoomApi.getRoomSeatCount` 原样包一层（它自己也带一年的座位缓存）。 */
    override suspend fun seatCount(roomName: String): Int? = withContext(Dispatchers.IO) {
        api.getRoomSeatCount(roomName)
    }
}
