package com.xjtu.toolbox.emptyroom

/**
 * 空闲教室的**取数端口**：屏与 ViewModel 都在 `:core`，三档数据源由各端自己实现。
 *
 * 为什么是「能力端口」而不是三套接口：这个屏的可观测行为是「数据源 × 校区 × 楼 × 日期，任一变化
 * 就取消旧查询重查」，其中**数据源是唯一按端不同的那一维**。端口把这一维交出去，
 * 屏/VM 一行逻辑都不用分叉。
 *
 * 本端到底能提供哪几档由 [availableSources] 声明（见它的 KDoc）；**取数实现的 IO 调度由实现方
 * 自己负责**（`:app` 那几份是阻塞式 okhttp，实现里自己 `withContext(Dispatchers.IO)`；
 * Web 走 ktor 的挂起接口，不需要）。VM 只在自己的可取消作用域里调用，不再替实现挑调度器。
 *
 * 各端：
 * - `AppEmptyRoomSource`（:app）= 原来的 `EmptyRoomApi`（CDN + 直查教务）、`LiveRoomApi`（实时状态）、
 *   `EmptyRoomCache`（磁盘缓存），**实现一行未改**，只是被包进这个端口；
 * - `CampusEmptyRoomApi`（:core/core/net，Web 端用）= campus-api 的 `/api/emptyroom/cdn`，只有 CDN 那一档。
 */
interface EmptyRoomSource {

    /**
     * 本端能提供的档位，也决定屏上「数据源」菜单列出哪几项。
     *
     * 为什么要暴露它（而不是让屏自己判断平台）：**屏不该知道"本端有没有智慧教室会话""教务能不能直连"**
     * 这类平台事实 —— 那是取数实现的知识。屏只画 `availableSources` 里的那几档，
     * 于是 Web 上根本不会出现「实时状态」「直查教务」两个点了会失败的选项。
     *
     * 顺序有意义：VM 找不到"上次记住的档"时落到本表第一档。
     * Android 第一档是 [RoomSource.LIVE]（与搬进 :core 之前同一个默认值）。
     */
    val availableSources: List<RoomSource>

    /** 可查的日期档（今明两天那套），与 `EmptyRoomApi.getAvailableDates()` 同一个口径。 */
    fun availableDates(): List<String>

    /**
     * 实时状态（智慧教室）一个校区的快照。[force] = 下拉刷新，绕过实现方自己的新鲜度缓存。
     *
     * 本端没有这一档时抛 [RuntimeException]（屏不会走到：它只在数据源为 [RoomSource.LIVE] 时调，
     * 而那一档不会出现在 [availableSources] 里）。
     */
    suspend fun liveSnapshot(campus: String, force: Boolean): LiveSnapshot

    /** 联网失败时的兜底：读磁盘上那份实时状态快照（可能很旧），没有就 null。 */
    fun readStaleLive(campus: String): LiveSnapshot?

    /**
     * 某校区 + 某几个楼 + 某天的教室。[direct] = 直查教务那一档。
     *
     * [force] = 下拉刷新：绕过实现方自己的磁盘/内存缓存重新取数（原来的写法是"重新 new 一个不带
     * cache 的 `EmptyRoomApi` / `EmptyRoomDirectQuery`"，语义相同）。少了它，下拉刷新会一直读磁盘缓存。
     *
     * [onProgress] 只给直查那种逐楼逐节查询用（(已查节数, 总节数)），其余档位不会被调用。
     */
    suspend fun rooms(
        campus: String,
        buildings: Set<String>,
        date: String,
        direct: Boolean,
        force: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<RoomInfo>

    /**
     * 联网失败时的磁盘兜底：返回 (教室, 缓存时间戳)；没有缓存返回 null。
     *
     * 实现方把「几个楼各自缓存」合并成一份（原来的 [EmptyRoomViewModel] 就是这么合并的，
     * 时间戳取最新的那个），并自己负责只在数据源与日期都对得上时才给。
     */
    fun readStaleRooms(campus: String, buildings: Set<String>, date: String, direct: Boolean): Pair<List<RoomInfo>, Long>?

    /**
     * 教室名 → 座位数（课表页的课程详情要显示"XX座"）。查不到返回 null。
     *
     * 不在 [EmptyRoomSource] 里塞进"一定查得到"的语义：容量来自 CDN 的当天数据，
     * 拿不到就是拿不到 —— 调用点按 null 处理（App 的课表详情本来就不画那一块）。
     */
    suspend fun seatCount(roomName: String): Int?
}
