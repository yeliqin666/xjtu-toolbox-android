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

/** 课表：courses 已按目标日期/本周过滤好，控件内部按星期分组展示。 */
@Serializable
data class ScheduleWidget(val title: String = "", val courses: List<CourseItem> = emptyList()) : AgentWidget

/** 考试安排。 */
@Serializable
data class ExamWidget(val exams: List<ExamItem> = emptyList()) : AgentWidget

/** 空闲教室。currentPeriod 为 0 基节次索引，-1 表示无"当前节次"语境。 */
@Serializable
data class RoomWidget(val condition: String = "", val rooms: List<RoomInfo> = emptyList(), val currentPeriod: Int = -1) : AgentWidget

/** 空闲教室的实时状态（智慧教室平台）：只放空闲和"没排课但有人"的教室。 */
@Serializable
data class LiveRoomWidget(
    val condition: String = "",
    val rooms: List<com.xjtu.toolbox.emptyroom.LiveRoom> = emptyList(),
    val fetchedAt: Long = 0L,
) : AgentWidget

/** 考勤记录。 */
@Serializable
data class AttendanceWidget(val records: List<AttendanceWaterRecord> = emptyList()) : AgentWidget

/** 成绩 + 加权 GPA 汇总。 */
@Serializable
data class GradeWidget(val grades: List<ReportedGrade> = emptyList(), val gpa: Double? = null, val totalPoints: Double = 0.0) : AgentWidget

/** 校园卡信息。 */
@Serializable
data class CardWidget(val info: CardInfo) : AgentWidget

/**
 * 仲英学辅资料站的检索结果。
 *
 * 和其它卡片不同，这张是**可操作**的：点文件就下载，点目录就让屁岱继续往里翻。
 * 检索结果天然是"给你一串候选，你挑一个"，纯文本把文件名和 ID 抄一遍再让用户
 * 复述给屁岱，中间那几步毫无意义。
 */
@Serializable
data class ZyxfWidget(val query: String = "", val items: List<ZyxfEntryRef> = emptyList()) : AgentWidget

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
) : AgentWidget

/** 类型名是存盘格式的一部分（沿用旧版的类名），不要改。 */
fun AgentWidget.toStored(): StoredWidget = when (this) {
    is ScheduleWidget -> StoredWidget("ScheduleWidget", AppJson.encodeToString(this))
    is ExamWidget -> StoredWidget("ExamWidget", AppJson.encodeToString(this))
    is RoomWidget -> StoredWidget("RoomWidget", AppJson.encodeToString(this))
    is LiveRoomWidget -> StoredWidget("LiveRoomWidget", AppJson.encodeToString(this))
    is AttendanceWidget -> StoredWidget("AttendanceWidget", AppJson.encodeToString(this))
    is GradeWidget -> StoredWidget("GradeWidget", AppJson.encodeToString(this))
    is CardWidget -> StoredWidget("CardWidget", AppJson.encodeToString(this))
    is ZyxfWidget -> StoredWidget("ZyxfWidget", AppJson.encodeToString(this))
    is LibraryWidget -> StoredWidget("LibraryWidget", AppJson.encodeToString(this))
}

/** 读不回来（旧版半截写入、未知类型）就丢掉这张卡片，不影响整段会话。 */
fun storedToWidget(stored: StoredWidget): AgentWidget? = runCatching {
    when (stored.type) {
        "ScheduleWidget" -> AppJson.decodeFromString<ScheduleWidget>(stored.json)
        "ExamWidget" -> AppJson.decodeFromString<ExamWidget>(stored.json)
        "RoomWidget" -> AppJson.decodeFromString<RoomWidget>(stored.json)
        "LiveRoomWidget" -> AppJson.decodeFromString<LiveRoomWidget>(stored.json)
        "AttendanceWidget" -> AppJson.decodeFromString<AttendanceWidget>(stored.json)
        "GradeWidget" -> AppJson.decodeFromString<GradeWidget>(stored.json)
        "CardWidget" -> AppJson.decodeFromString<CardWidget>(stored.json)
        "ZyxfWidget" -> AppJson.decodeFromString<ZyxfWidget>(stored.json)
        "LibraryWidget" -> AppJson.decodeFromString<LibraryWidget>(stored.json)
        else -> null
    }
}.getOrNull()
