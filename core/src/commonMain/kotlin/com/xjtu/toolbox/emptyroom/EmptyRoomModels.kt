package com.xjtu.toolbox.emptyroom

/**
 * 空闲教室的**模型与常量**：从 `:app` 的 `EmptyRoomApi.kt` / `LiveRoomApi.kt` / `EmptyRoomScreen.kt`
 * 原样搬进 `:core`，一行业务逻辑都没改，只是换了住址 —— 因为两端（Android / Web）现在共用同一份
 * 屏与 ViewModel，它们必须共用同一份模型。
 *
 * **为什么取数没跟着搬**：`:app` 那几份取数是 okhttp + `Context` + SharedPreferences
 * （CDN 第三方源、直查教务、智慧教室实时状态、磁盘缓存），在 commonMain 里编不过。
 * 取数改成端口（见 [EmptyRoomSource]）：Android 侧 `AppEmptyRoomSource` 包住原来那几个类，
 * Web 侧见 `com.xjtu.toolbox.core.net.CampusEmptyRoomApi`。
 *
 * ⚠️ [RoomInfo] 是 `@Serializable`，**形状不能动**：Android 的磁盘缓存（`EmptyRoomCache` 写出的
 * JSON 数组）是照这个形状存的，改字段名/类型会让老缓存读不出来。
 */

/** 空闲教室的数据源。[key] 存进偏好，改名别动它。 */
enum class RoomSource(val key: String) {
    /** 智慧教室平台的此刻状态：含上课、没排课但有人（带人数）。默认。 */
    LIVE("live"),
    /** 预生成的课表数据，免登录，可看今天/明天。 */
    CDN("cdn"),
    /** 登录教务实时查课表，结果和 CDN 同源。 */
    DIRECT("direct"),
}

/**
 * 记住"上次选的是哪一档"的偏好键。
 *
 * 新键：旧的 empty_room_use_direct_query 是 CDN/直查二选一时代的，实时状态上线后默认改回实时，不沿用。
 *
 * ⚠️ `:app` 的 `AgentTool` 也直接读这个键（同一个 SharedPreferences 文件、同一个键名），
 * 挪动它等于让屏和屁岱对"现在用哪档"的看法不一致。
 */
internal const val SOURCE_PREF_KEY = "empty_room_source"

/**
 * 教室信息（来自 CDN 缓存）
 */
@kotlinx.serialization.Serializable
data class RoomInfo(
    val name: String = "",      // 教室名称，如 "主楼A-101"
    val size: Int = 0,          // 座位数
    val status: List<Int> = emptyList(),  // 11 个元素，对应 1-11 节课的占用情况：0=空闲, 1=占用
)

/**
 * 校区-教学楼映射（来自 XJTUToolBox）
 */
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

/**
 * 无数据异常（CDN 上没有该天的数据）。
 *
 * 必须住在 `:core`：ViewModel（现在是共享的）要 `catch (e: NoDataException)` 把「这一天没数据」
 * 与「请求失败」分开处理（前者直接报错不兜底缓存，后者才回退磁盘缓存），而抛它的是各端的取数实现。
 */
class NoDataException(message: String) : Exception(message)

/**
 * 实时状态里的一间教室，来自智慧教室平台 `classroomStatus/classroomStatusList`。
 *
 * 会随屁岱的卡片（`:app` 的 `agent/LiveRoomWidget`）存进会话记录，字段名即存盘格式。
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
