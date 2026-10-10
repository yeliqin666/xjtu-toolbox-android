package com.xjtu.toolbox.emptyroom

import com.xjtu.toolbox.auth.JsSession
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `:core` 的 [EmptyRoomSource] 在 `:data` 里的实现（Android 与桌面侧共用）—— 包住原来的
 * `EmptyRoomApi`（CDN + 直查教务）、`LiveRoomApi`（智慧教室实时状态）与宿主存储 [store]。
 *
 * 它从 `:app` 搬进 `:data`（桌面端第 10 条真数据路由：桌面要自己登智慧教室、自己取数），
 * 两大类各动一半：
 *
 * 1. **取数**（`EmptyRoomApi` / `EmptyRoomDirectQuery` / `LiveRoomApi`）的类名与包路径一字未改，
 *    只是从 `:app/emptyroom/` 挪到同包的 `:data/emptyroom/`，替掉的只有 `android.util.Log`；
 * 2. **落盘**原来直接调 `EmptyRoomCache`（SharedPreferences，所以要一个 `Context`），现在改成
 *    构造参数 [store] —— `:app` 传 `EmptyRoomCache(context)`（**一份文件、一个键都没动**），
 *    桌面端传 `null`（不缓存 —— 语义与「缓存里什么都没有」一致）。`Context` 因此不再跟着取数走，
 *    `:data` 里一行 `android.content` 都没有。
 *
 * 本类自己做三件原本写在别处的事：
 *  1. 说出这一端有哪些档（[availableSources]：三档都有）；
 *  2. 会话准备 —— 实时状态要智慧教室站点会话、直查要先登教务 —— 原来写在 ViewModel 的
 *     `queryLive`/`queryRooms` 里，现在跟着实现走（那本来就是"各端能力"，不是屏的逻辑）；
 *  3. 阻塞式 okhttp 调用放进 `Dispatchers.IO`。原调用点在 VM 里包着 `withContext(Dispatchers.IO)`，
 *     现在 VM 不再替实现挑调度器（见 [EmptyRoomSource] 的 KDoc），所以由本类自己包 ——
 *     **同一层、同一个调度器，行为不变**。
 *
 * ## 会话由本类自己 ensure（所以不需要 Gate）
 *
 * [EmptyRoomSource] 的三档登的站点不同（实时状态→智慧教室 `js`、直查→教务、CDN→不登），
 * 而 `AppRoute.EmptyRoom.loginType` 因此是 **null**（导航层不替它建会话）—— 这就是“由页面自己登”
 * 的原本含义，与 `GraduateJudgeSource` 那一条同型。两处 ensure 就在下面：
 * [liveSnapshot] 里 `ensureSite(js)`、[rooms]（`direct`）里 `ensureSite(LoginType.JWXT)`，
 * 与搬进 `:core` 之前 VM 里那两句逐字相同。桌面端因此 **不用** `DesktopSiteGate`：
 * 它只要自己的 `SessionManager` 里注册过这两个站点（`DesktopAuth` 里各一行），
 * 进屏即用，失败也只是这一屏报错 + 重试。
 *
 * 两处刻意照抄原样而非“顺手改好”：
 */
class AppEmptyRoomSource(
    private val sessionManager: SessionManager?,
    /** 本端的宿主存储；`null` = 不缓存（桌面端）。见 [EmptyRoomStore]。 */
    private val store: EmptyRoomStore? = null,
) : EmptyRoomSource {


    /** 默认取数：带磁盘缓存。 */
    private val api = EmptyRoomApi(store)
    private val cache = store

    /** 实时状态：要智慧教室站点会话；没有会话就是 null（与原来 VM 里那份 `val` 同一时机、同一条件）。 */
    private val liveApi: LiveRoomApi? =
        sessionManager?.getSiteOrNull(JsSession.SITE_KEY)?.let { LiveRoomApi(it, cache) }

    /**
     * Android 与桌面（`store = null`）三档都能提供（与搬进 `:core` 之前一模一样）。**"研究生不提供直查教务"不在这里**：
     * 那是账号规则、不是本端能力，留在 ViewModel 与屏里（与原来同一处，行为不变）。
     */
    override val availableSources: List<RoomSource> =
        listOf(RoomSource.LIVE, RoomSource.CDN, RoomSource.DIRECT)

    override fun availableDates(): List<String> = api.getAvailableDates()

    override suspend fun liveSnapshot(campus: String, force: Boolean): LiveSnapshot {
        // 三句报错与原来 VM 里的一字不差（哪句出现取决于没登录/没凭据）
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
                // 入口不先登教务（默认数据源是实时状态），切到直查时才登 —— 原样搬运
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

    /**
     * 磁盘兜底：把几个楼各自的缓存合并成一份，时间戳取最新的那个（原来写在 VM 的 `fallbackToStale` 里，
     * 逐字搬过来；用的是 `readRoomListStale` —— 忽略 TTL，"过期了也别空白"）。
     *
     * `store == null`（桌面端）就是「缓存里什么都没有」：没有兜底可给，返回 null，
     * 调用方（VM）据此画错误页 —— 与搬迁前那份空缓存走的同一条分支。
     */
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
