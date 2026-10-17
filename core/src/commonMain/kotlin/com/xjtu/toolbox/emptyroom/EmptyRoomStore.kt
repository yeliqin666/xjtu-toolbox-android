package com.xjtu.toolbox.emptyroom

/** 空闲教室的宿主存储缝 —— 把「写到哪里」从取数实现里切出来。 */
interface EmptyRoomStore {

    /** 读原文；超过 [maxAgeDays] 就当没有（返回 null）。 */
    fun readJson(key: String, maxAgeDays: Int): String?

    /** 写原文，并记下写入时刻。 */
    fun writeJson(key: String, json: String)

    /** 不看天数 TTL 读原文：实时状态按分钟算新鲜度，由调用方拿 [savedAt] 自己判断。 */
    fun readJsonAnyAge(key: String): String?

    /** 取出对应缓存键的「写入时间戳」（没写过就是 0），供 UI 标注新鲜度。 */
    fun savedAt(key: String): Long

    /** 读教室列表；超过 [maxAgeDays] 就当没有。 */
    fun readRoomList(key: String, maxAgeDays: Int): List<RoomInfo>?

    /** 忽略 TTL 读教室列表：联网失败时的兜底。 */
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
