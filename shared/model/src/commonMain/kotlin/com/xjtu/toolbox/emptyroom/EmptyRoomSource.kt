package com.xjtu.toolbox.emptyroom

/** 空闲教室的取数端口：屏与 ViewModel 都在 `:core`，三档数据源由各端自己实现。 */
interface EmptyRoomSource {

    /** 本端能提供的档位，也决定屏上「数据源」菜单列出哪几项。 */
    val availableSources: List<RoomSource>

    /** 可查的日期档（今明两天）。 */
    fun availableDates(): List<String>

    /** 实时状态（智慧教室）一个校区的快照。 */
    suspend fun liveSnapshot(campus: String, force: Boolean): LiveSnapshot

    /** 联网失败时的兜底：读磁盘上那份实时状态快照（可能很旧），没有就 null。 */
    fun readStaleLive(campus: String): LiveSnapshot?

    /** 某校区 + 某几个楼 + 某天的教室。 */
    suspend fun rooms(
        campus: String,
        buildings: Set<String>,
        date: String,
        direct: Boolean,
        force: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<RoomInfo>

    /** 联网失败时的磁盘兜底：返回 (教室, 缓存时间戳)；没有缓存返回 null。 */
    fun readStaleRooms(campus: String, buildings: Set<String>, date: String, direct: Boolean): Pair<List<RoomInfo>, Long>?

    /** 教室名 → 座位数（课表页的课程详情要显示"XX座"）。 */
    suspend fun seatCount(roomName: String): Int?
}
