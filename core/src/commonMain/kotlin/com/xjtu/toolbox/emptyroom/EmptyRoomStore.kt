package com.xjtu.toolbox.emptyroom

/**
 * 空闲教室的**宿主存储**缝 —— 把「写到哪里」从取数实现里切出来。
 *
 * 为什么要有这条缝：`AppEmptyRoomSource`（以及它包住的 `EmptyRoomApi` / `EmptyRoomDirectQuery` /
 * `LiveRoomApi`）原来收一个 `android.content.Context`，而那个 `Context` **只**喂给 `EmptyRoomCache`
 * ——按账号分命名空间的 SharedPreferences（当天 CDN 结果 / 直查结果 / 校区与楼代码 / 实时状态原文 /
 * 教室容量）。取数本身（okhttp 打 `gh-release` 的 CDN、直查教务、智慧教室平台）一行 `Context` 都不需要
 * ——桌面端要的正是那一半，于是这几个类从 `:app` 搬进 `:data` 时它成了构造参数。做法与黄页的
 * `YellowPageCache`、成绩的 `ScoreReportCache`、校园卡的 `CampusCardStore` 是同一条。
 *
 * ## 这份缝就是 `EmptyRoomCache` 的那张公开面
 *
 * 九个方法 + 五个常量**逐个对应** `:app` 那份 `EmptyRoomCache`（读原文 / 写原文 / 不看过期时间读原文 /
 * 取写入时刻 / 读教室列表 / 读教室列表（忽略 TTL）/ 写教室列表 / 读代码表 / 写代码表，
 * 以及 CDN、直查、代码表、座位数各自的 TTL 与座位缓存的键），一个不多一个不少：
 * 搬走的四个类只把「原来直接调 `EmptyRoomCache`」换成「调这里」，所以「行为不变」是可核的 ——
 * 比对两边的实现即可，不必追一遍调用链。
 *
 * **不顺手换存储**（校园卡那一轮定下的判据）：这些键名、TTL、`.commit()` 同步写、按
 * `AccountContext.safeSuffix()` 分命名空间，全都是可观测行为 —— 换成 `:core` 的 `keyValueStore`
 * 会让老缓存读不出来、也会改掉写盘时机。这条缝只把**存放地点**交出去，语义一行不动。
 *
 * ## 各端的实现
 *
 * - `:app` 的 `EmptyRoomCache(context)`（`app/emptyroom/EmptyRoomCache.kt`）= 原来那一份，
 *   **一个键、一个 TTL、一次 `.commit()` 都没动**（`AgentTool` 与屏读的还是同一份）；
 * - 桌面端传 `null`：没有按账号分命名空间的宿主存储 ⇒ 不缓存。
 *   语义与「缓存里什么都没有」一致 —— 取数照常，只是少了下拉刷新之外的那一档兜底；
 * - Web 端（`CampusEmptyRoomApi`）没有缓存这一档，压根不用这条缝。
 *
 * ## 线程
 *
 * 实现方是**阻塞式**存储（SharedPreferences 读写），所以这里的方法全是非挂起的
 * ——这与搬迁前的调用形状完全一致：`AppEmptyRoomSource` 在 `withContext(Dispatchers.IO)` 里调，
 * `LiveRoomApi` 自己把写那一发包进 IO，而 `readStaleLive` / `readStaleRooms`（端口的两个非挂起方法）
 * 本来就是同步读。改成 `suspend` 会把这些调用点的线程换个地方，那是行为变化。
 */
interface EmptyRoomStore {

    /** 读原文；超过 [maxAgeDays] 就当没有（返回 null）。 */
    fun readJson(key: String, maxAgeDays: Int): String?

    /**
     * 写原文，并记下写入时刻。
     *
     * 实现方是**同步落盘**（原来的 `EmptyRoomCache` 用 `.commit()` 而不是 `.apply()`：
     * 空教室缓存写盘频次低，异常退出时丢数据的代价比多等一次 fsync 大）。
     */
    fun writeJson(key: String, json: String)

    /** 不看天数 TTL 读原文：实时状态按分钟算新鲜度，由调用方拿 [savedAt] 自己判断。 */
    fun readJsonAnyAge(key: String): String?

    /** 取出对应缓存键的「写入时间戳」（没写过就是 0），供 UI 标注新鲜度。 */
    fun savedAt(key: String): Long

    /** 读教室列表；超过 [maxAgeDays] 就当没有。 */
    fun readRoomList(key: String, maxAgeDays: Int): List<RoomInfo>?

    /**
     * 忽略 TTL 读教室列表：联网失败时的兜底。「过期了也别空白」——比直接报错更友好，
     * 但调用方要在 UI 上标出「这是 X 前的缓存」。
     */
    fun readRoomListStale(key: String): List<RoomInfo>?

    /** 写教室列表（`RoomInfo` 的 JSON 数组形状不能动，见 [RoomInfo] 的 KDoc）。 */
    fun writeRoomList(key: String, rooms: List<RoomInfo>)

    /** 读「名字 → 代码」表（教务的校区代码 / 教学楼代码）。 */
    fun readCodeMap(key: String, maxAgeDays: Int): Map<String, String>?

    /** 写「名字 → 代码」表。 */
    fun writeCodeMap(key: String, data: Map<String, String>)

    companion object {
        /** 教务的校区 / 教学楼代码：命名编号几乎不变，留 7 天。 */
        const val CODE_TTL_DAYS = 7

        /** 直查教务的当天结果：同一栋楼同一天不必反复打教务。 */
        const val DIRECT_RESULT_TTL_DAYS = 7

        /** CDN 是**按天**预生成的快照，跨天就该重新拉。 */
        const val CDN_RESULT_TTL_DAYS = 1

        /** 教室名 → 座位数的缓存键。容量不随日期变，长期留着。 */
        const val SEAT_CACHE_KEY = "room_seat_sizes"

        /** 一年。教室容量只在改造、并班时才变，比一天一失效合理得多。 */
        const val SEAT_TTL_DAYS = 365
    }
}
