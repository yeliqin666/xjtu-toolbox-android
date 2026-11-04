package com.xjtu.toolbox.agent

import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.card.CardInfo
import com.xjtu.toolbox.emptyroom.RoomInfo
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable

/**
 * Agent 回复可携带的富控件。工具执行时由 [AgentToolRegistry] 产出结构化数据，
 * UI 端 [AgentWidgetView] 用项目内已有视觉语言渲染，**不依赖大模型吐标记**。
 *
 * 设计：全部是只读展示卡片，数据直接复用各功能模块的公开 data class，避免重复建模。
 */
sealed interface AgentWidget

/** 标题与链接只来自通知查询；why/level 是对这些真实条目的推荐说明。 */
@Serializable
data class NoticeWidgetItem(
    val title: String, val source: String, val date: String, val link: String,
    val why: String = "", val level: Int = 1, val excerpt: String = "",
)

@Serializable
data class NoticeWidget(
    val items: List<NoticeWidgetItem> = emptyList(), val query: String = "",
    val caption: String = "", val display: WidgetDisplay = WidgetDisplay(limit = 6),
) : AgentWidget

/** 已验证的展示配置，随历史卡片保存；业务数据仍来自查询结果。 */
@Serializable
data class WidgetDisplay(
    val view: String = "auto",
    val fields: List<String> = emptyList(),
    val sections: List<Int> = emptyList(),
    val summary: String = "all",
    val matchedCount: Int = 0,
    val limit: Int = 12,
) {
    fun shows(field: String): Boolean = fields.isEmpty() || field in fields
}

/** 课表：courses 已按目标日期/本周过滤好，控件内部按星期分组展示。 */
@Serializable
data class ScheduleWidget(val title: String = "", val courses: List<CourseItem> = emptyList(), val display: WidgetDisplay = WidgetDisplay()) : AgentWidget

/** 考试安排。 */
@Serializable
data class ExamWidget(val exams: List<ExamItem> = emptyList(), val display: WidgetDisplay = WidgetDisplay()) : AgentWidget

/** 空闲教室。currentPeriod 为 0 基节次索引，-1 表示无"当前节次"语境。 */
@Serializable
data class RoomWidget(
    val condition: String = "", val rooms: List<RoomInfo> = emptyList(), val currentPeriod: Int = -1,
    val display: WidgetDisplay = WidgetDisplay(),
    val liveRooms: List<com.xjtu.toolbox.emptyroom.LiveRoom> = emptyList(),
    val fetchedAt: Long = 0L,
) : AgentWidget

/** 空闲教室的实时状态（智慧教室平台）：只放空闲和"没排课但有人"的教室。 */
@Serializable
data class LiveRoomWidget(
    val condition: String = "",
    val rooms: List<com.xjtu.toolbox.emptyroom.LiveRoom> = emptyList(),
    val fetchedAt: Long = 0L,
    val display: WidgetDisplay = WidgetDisplay(),
) : AgentWidget

/** 考勤记录。 */
@Serializable
data class AttendanceWidget(val records: List<AttendanceWaterRecord> = emptyList(), val display: WidgetDisplay = WidgetDisplay()) : AgentWidget

/** 成绩 + 加权 GPA 汇总。 */
@Serializable
data class GradeWidget(val grades: List<ReportedGrade> = emptyList(), val gpa: Double? = null, val totalPoints: Double = 0.0, val display: WidgetDisplay = WidgetDisplay(limit = 15)) : AgentWidget

/** 校园卡信息。 */
@Serializable
data class CardWidget(
    val info: CardInfo,
    val transactions: List<com.xjtu.toolbox.card.Transaction> = emptyList(),
    val rangeLabel: String = "",
    val transactionsLoaded: Boolean = false,
    val display: WidgetDisplay = WidgetDisplay(),
    val spend: Double? = null,
    val income: Double? = null,
) : AgentWidget

/**
 * 仲英学辅资料站的检索结果。
 *
 * 和其它卡片不同，这张是**可操作**的：点文件就下载，点目录就让屁岱继续往里翻。
 * 检索结果天然是"给你一串候选，你挑一个"，纯文本把文件名和 ID 抄一遍再让用户
 * 复述给屁岱，中间那几步毫无意义。
 */
@Serializable
data class ZyxfWidget(val query: String = "", val items: List<ZyxfEntryRef> = emptyList(), val display: WidgetDisplay = WidgetDisplay()) : AgentWidget

/** 卡片里的一条。卡片要随会话一起存盘。 */
@Serializable
data class ZyxfEntryRef(
    val id: Int = 0,
    val name: String = "",
    val path: String = "",
    val sizeText: String = "",
    val isFolder: Boolean = false,
)

/**
 * 图书馆某区域的平面图缩略图：底图来自平面图磁盘缓存（工具执行时已落盘），
 * 有人的座位压暗，空座就亮出来。点一下打开图书馆页并定位到这个区域。
 */
@Serializable
data class LibraryWidget(
    val campusId: String = "",
    val campusName: String = "",
    val areaCode: String = "",
    val areaName: String = "",
    val imageName: String = "",
    val seats: List<com.xjtu.toolbox.library.PlanSeat> = emptyList(),
    val display: WidgetDisplay = WidgetDisplay(),
    val highlightedSeats: List<String> = emptyList(),
    val areas: List<LibraryAreaRef> = emptyList(),
    val booking: String? = null,
) : AgentWidget

@Serializable
data class LibraryAreaRef(val code: String = "", val name: String = "", val free: Int? = null, val total: Int? = null)

/** 校区简图：标出几个地点（按名字回查打包的地图数据），[route] 为 true 时按顺序连成路线。 */
@Serializable
data class CampusMapWidget(
    val campus: String = "",
    val places: List<String> = emptyList(),
    val route: Boolean = false,
    /** 路线的一句话（「步行约 6 分钟」），画在图下。 */
    val caption: String = "",
    /** 用户当前位置 [lat, lon]，有就标一个「你在这」。 */
    val here: List<Double>? = null,
    val display: WidgetDisplay = WidgetDisplay(),
) : AgentWidget

/**
 * 小岱提议的安排：从对话或材料里理出的日程、待办，用户逐条点「加入」才写入。
 * [date] 为 yyyy-MM-dd，[start]/[end] 为 HH:mm；待办的 [start] 是该办完的时刻，可空（当天截止）。
 */
@Serializable
data class PlanProposal(
    val title: String = "",
    val date: String = "",
    val start: String = "",
    val end: String = "",
    val place: String = "",
    val note: String = "",
    val todo: Boolean = false,
)

@Serializable
data class PlanWidget(val items: List<PlanProposal> = emptyList(), val caption: String = "") : AgentWidget

/** 类型名是存盘格式的一部分（沿用旧版的类名），不要改。 */
fun AgentWidget.toStored(): StoredWidget = when (this) {
    is NoticeWidget -> StoredWidget("NoticeWidget", AppJson.encodeToString(this))
    is ScheduleWidget -> StoredWidget("ScheduleWidget", AppJson.encodeToString(this))
    is ExamWidget -> StoredWidget("ExamWidget", AppJson.encodeToString(this))
    is RoomWidget -> StoredWidget("RoomWidget", AppJson.encodeToString(this))
    is LiveRoomWidget -> StoredWidget("LiveRoomWidget", AppJson.encodeToString(this))
    is AttendanceWidget -> StoredWidget("AttendanceWidget", AppJson.encodeToString(this))
    is GradeWidget -> StoredWidget("GradeWidget", AppJson.encodeToString(this))
    is CardWidget -> StoredWidget("CardWidget", AppJson.encodeToString(this))
    is ZyxfWidget -> StoredWidget("ZyxfWidget", AppJson.encodeToString(this))
    is LibraryWidget -> StoredWidget("LibraryWidget", AppJson.encodeToString(this))
    is CampusMapWidget -> StoredWidget("CampusMapWidget", AppJson.encodeToString(this))
    is PlanWidget -> StoredWidget("PlanWidget", AppJson.encodeToString(this))
}

/** 读不回来（旧版半截写入、未知类型）就丢掉这张卡片，不影响整段会话。 */
fun storedToWidget(stored: StoredWidget): AgentWidget? = runCatching {
    when (stored.type) {
        "NoticeWidget" -> AppJson.decodeFromString<NoticeWidget>(stored.json)
        "ScheduleWidget" -> AppJson.decodeFromString<ScheduleWidget>(stored.json)
        "ExamWidget" -> AppJson.decodeFromString<ExamWidget>(stored.json)
        "RoomWidget" -> AppJson.decodeFromString<RoomWidget>(stored.json)
        "LiveRoomWidget" -> AppJson.decodeFromString<LiveRoomWidget>(stored.json)
        "AttendanceWidget" -> AppJson.decodeFromString<AttendanceWidget>(stored.json)
        "GradeWidget" -> AppJson.decodeFromString<GradeWidget>(stored.json)
        "CardWidget" -> AppJson.decodeFromString<CardWidget>(stored.json)
        "ZyxfWidget" -> AppJson.decodeFromString<ZyxfWidget>(stored.json)
        "LibraryWidget" -> AppJson.decodeFromString<LibraryWidget>(stored.json)
        "CampusMapWidget" -> AppJson.decodeFromString<CampusMapWidget>(stored.json)
        "PlanWidget" -> AppJson.decodeFromString<PlanWidget>(stored.json)
        else -> null
    }
}.getOrNull()
