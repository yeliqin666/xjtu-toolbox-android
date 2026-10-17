package com.xjtu.toolbox.emptyroom

/** 空闲教室的模型与常量。 */

/** 空闲教室的数据源。[key] 存进偏好，改名别动它。 */
enum class RoomSource(val key: String) {
    /** 智慧教室平台的此刻状态：含上课、没排课但有人（带人数）。默认。 */
    LIVE("live"),
    /** 预生成的课表数据，免登录，可看今天/明天。 */
    CDN("cdn"),
    /** 登录教务实时查课表，结果和 CDN 同源。 */
    DIRECT("direct"),
}

/** 记住"上次选的是哪一档"的偏好键。 */
internal const val SOURCE_PREF_KEY = "empty_room_source"

/** 教室信息（来自 CDN 缓存） */
@kotlinx.serialization.Serializable
data class RoomInfo(
    val name: String = "",      // 教室名称，如 "主楼A-101"
    val size: Int = 0,          // 座位数
    val status: List<Int> = emptyList(),  // 11 个元素，对应 1-11 节课的占用情况：0=空闲, 1=占用
)

/** 校区-教学楼映射（来自 XJTUToolBox） */
val CAMPUS_BUILDINGS = mapOf(
    "兴庆校区" to listOf(
        "主楼A", "主楼B", "主楼C", "主楼D", "中2", "中3",
        "西2东", "西2西", "外文楼A", "外文楼B", "东1东", "东2",
        "仲英楼", "东1西", "教2楼", "中1", "主楼E座",
        "工程馆", "工程坊A区", "文管", "计教中心", "田家炳"
    ),
    "雁塔校区" to listOf(
        "东配楼", "微免楼", "综合楼", "教学楼", "药学楼", "解剖楼",
        "生化楼", "病理楼", "西配楼", "一附院科教楼", "二院教学楼",
        "护理楼", "卫法楼"
    ),
    "曲江校区" to listOf("西一楼", "西五楼", "西四楼", "西六楼"),
    "创新港校区" to listOf(
        "1号巨构", "2号巨构", "3号巨构", "4号巨构", "5号巨构",
        "9号巨构", "18号巨构", "19号巨构", "20号巨构", "21号巨构",
        "图书馆", "2号绿楔", "3号绿楔", "主楼运动场", "工程博物馆-创新港"
    ),
    "苏州校区" to listOf("公共学院5号楼")
)

/** 无数据异常（CDN 上没有该天的数据）。 */
class NoDataException(message: String) : Exception(message)

/** 实时状态里的一间教室，来自智慧教室平台 `classroomStatus/classroomStatusList`。 */
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

/** 平台的 status 取值（前端 chunk 里写死的）。 */
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

/** App 校区名 → 平台校区名。 */
val LIVE_CAMPUSES: Map<String, String> = linkedMapOf(
    "兴庆校区" to "兴庆校区",
    "雁塔校区" to "雁塔校区",
    "创新港校区" to "创新港",
)

/** 平台楼名 → App 楼名。 */
fun liveBuildingName(campus: String, raw: String): String {
    val trimmed = raw.trim()
    if (campus == "创新港校区" && trimmed.isNotEmpty() && trimmed.all { it.isDigit() }) return "${trimmed}号巨构"
    return trimmed
}
