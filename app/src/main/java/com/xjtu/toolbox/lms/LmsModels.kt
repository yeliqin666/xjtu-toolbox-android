package com.xjtu.toolbox.lms

/**
 * 思源学堂数据模型
 * 从 XJTUToolBox Python 版 LMSUtil 移植
 */

// ════════════════════════════════════════
//  用户
// ════════════════════════════════════════

data class LmsDepartment(
    val id: Int = 0,
    val name: String = "",
    val code: String = ""
)

data class LmsUserInfo(
    val id: Int = 0,
    val name: String = "",
    val userNo: String = "",
    val orgId: Int = 0,
    val mobile: String = "",
    val orgName: String = "",
    val orgCode: String = "",
    val role: String = "",
    val hasAiAbility: Boolean = false,
    val dept: LmsDepartment = LmsDepartment()
)

// ════════════════════════════════════════
//  课程
// ════════════════════════════════════════

data class LmsAcademicYear(
    val id: Int = 0,
    val code: String = "",
    val name: String = "",
    val sort: Int = 0
)

data class LmsSemester(
    val id: Int = 0,
    val code: String = "",
    val name: String? = null,
    val realName: String? = null,
    val sort: Int = 0
)

data class LmsInstructor(
    val id: Int = 0,
    val name: String = ""
)

data class LmsCourseAttributes(
    val published: Boolean = false,
    val studentCount: Int = 0,
    val teachingClassName: String = ""
)

data class LmsCourseSummary(
    val id: Int = 0,
    val name: String = "",
    val courseCode: String = "",
    val courseType: Int = 0,
    val credit: String = "",
    val compulsory: Boolean = false,
    val startDate: String? = null,
    val endDate: String? = null,
    val academicYear: LmsAcademicYear = LmsAcademicYear(),
    val semester: LmsSemester = LmsSemester(),
    val department: LmsDepartment = LmsDepartment(),
    val instructors: List<LmsInstructor> = emptyList(),
    val courseAttributes: LmsCourseAttributes = LmsCourseAttributes()
) {
    /** 从 start_date 推导学期标签 */
    val semesterLabel: String
        get() {
            val sd = startDate ?: return "未知"
            return try {
                val parts = sd.split("-")
                val year = parts[0].toInt()
                val month = parts[1].toInt()
                if (month >= 8) "${year}-${year + 1} 秋" else "${year - 1}-${year} 春"
            } catch (_: Exception) { "未知" }
        }

    /** 教师名列表拼接 */
    val instructorNames: String
        get() = instructors.joinToString(" / ") { it.name }.ifEmpty { "未知" }
}

data class LmsCourseDetail(
    val summary: LmsCourseSummary,
    val subjectCode: String = "",
    val displayName: String = "",
    val publicScope: String = "",
    val cover: String = "",
    val courseOutline: Map<String, Any?> = emptyMap()
)

// ════════════════════════════════════════
//  活动 (Activity)
// ════════════════════════════════════════

/** 活动类型 */
enum class LmsActivityType(val value: String) {
    HOMEWORK("homework"),
    MATERIAL("material"),
    LESSON("lesson"),
    LECTURE_LIVE("lecture_live"),
    UNKNOWN("unknown");

    companion object {
        fun fromString(s: String): LmsActivityType =
            entries.find { it.value == s } ?: UNKNOWN
    }
}

data class LmsUpload(
    val id: Int = 0,
    val name: String = "",
    val type: String = "",
    val size: Int = 0,
    val referenceId: Int = 0,
    val status: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val downloadUrl: String = "",
    val previewUrl: String = "",
    val attachmentUrl: String = ""
) {
    /** 人类可读的文件大小 */
    val readableSize: String
        get() = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "%.1f MB".format(size / (1024.0 * 1024.0))
        }
}

data class LmsActivity(
    val id: Int = 0,
    val courseId: Int = 0,
    val type: LmsActivityType = LmsActivityType.UNKNOWN,
    val title: String = "",
    val moduleId: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val published: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val submitByGroup: Boolean = false,
    val uploads: List<LmsUpload> = emptyList(),

    // homework 专有
    val description: String? = null,
    val groupId: Int? = null,
    val groupSetName: String? = null,
    val userSubmitCount: Int = 0,
    val submissionList: LmsSubmissionListResponse? = null,
    val averageScore: Double? = null,
    val highestScore: Double? = null,
    val lowestScore: Double? = null,
    val hasScoreCount: Int? = null,
    /** 截止时间（UTC）。上游 deadline，与 endTime 多数一致但以此为准 */
    val deadline: String? = null,
    /** 允许提交次数；[nonSubmitTimes] 为 true 表示不限次 */
    val submitTimes: Int? = null,
    val nonSubmitTimes: Boolean = false,
    /** 作业是否已关闭 */
    val isClosed: Boolean = false,

    // lesson 专有
    val replayCode: String? = null,
    val replayVideos: List<LmsReplayVideo> = emptyList(),
    val replayDownloadUrls: List<String> = emptyList(),
    val replayVideoCount: Int = 0,
    val lessonStart: String? = null,
    val lessonEnd: String? = null,

    // lecture_live 专有
    val liveRoomName: String? = null,
    val liveRoomCode: String? = null,
    val liveStatus: String? = null,
    val liveInstructorNames: List<String> = emptyList(),
    val liveStreams: List<LmsLiveStream> = emptyList(),
    val liveReplayVideos: List<LmsReplayVideo> = emptyList(),
    val externalLiveId: Int? = null,
    val viewLive: Boolean = false,
    val viewRecord: Boolean = false
)

// ════════════════════════════════════════
//  作业提交
// ════════════════════════════════════════

data class LmsSubmissionCorrect(
    val id: Int = 0,
    val comment: String = "",
    val instructorScore: Any? = null,
    val score: Any? = null,
    val updatedAt: String = "",
    val uploads: List<LmsUpload> = emptyList()
)

data class LmsSubmissionItem(
    val id: Int = 0,
    val activityId: Int = 0,
    val studentId: Int = 0,
    val groupId: Int = 0,
    val canRetract: Boolean = false,
    /**
     * 草稿。上游 is_draft=true 表示只是暂存、**并未正式提交**。
     * 不区分的话学生会以为已经交了——这是本页最容易误导人的一个字段。
     */
    val isDraft: Boolean = false,
    val comment: String = "",
    val createdAt: String? = null,
    val createdBy: LmsSubmissionCreator = LmsSubmissionCreator(),
    val instructorComment: String = "",
    val isLatestVersion: Boolean = false,
    val isResubmitted: Boolean = false,
    val isRedo: Boolean = false,
    val mode: String = "",
    val status: String = "",
    val score: Any? = null,
    /** 最终成绩。多次提交按 score_rule 折算后的结果，score 为空时优先看它 */
    val finalScore: String? = null,
    val scoreAt: String? = null,
    val submittedAt: String? = null,
    val submitByInstructor: Boolean = false,
    val submissionCorrect: LmsSubmissionCorrect = LmsSubmissionCorrect(),
    val updatedAt: String? = null,
    val content: String = "",
    val uploads: List<LmsUpload> = emptyList()
) {
    /** 分数显示。score 为空时回退 final_score——多次提交的场次只有后者有值 */
    val scoreDisplay: String
        get() {
            val raw = when (score) {
                null -> finalScore
                is Number -> score.toString()
                else -> score.toString().ifEmpty { finalScore }
            }
            return raw?.takeIf { it.isNotBlank() && it != "null" } ?: "未评分"
        }

    /** 状态中文。草稿优先——它比 status 字段更能说明"到底交了没" */
    val statusLabel: String
        get() = if (isDraft) "草稿（未提交）" else when (status) {
            "submitted" -> "已提交"
            "graded" -> "已批改"
            "not_submitted" -> "未提交"
            "returned" -> "已退回"
            else -> status.ifEmpty { "未知" }
        }
}

data class LmsSubmissionCreator(
    val id: Int = 0,
    val name: String = "",
    val userNo: String = ""
)

data class LmsSubmissionListResponse(
    val list: List<LmsSubmissionItem> = emptyList(),
    val uploads: List<LmsUpload> = emptyList()
)

// ════════════════════════════════════════
//  直播流
// ════════════════════════════════════════

/** HLS 直播流（多机位） */
data class LmsLiveStream(
    val label: String = "",
    val src: String = "",
    val mute: Boolean = false,
    val type: String = "application/x-mpegURL"
) {
    /** 用户友好的标签 */
    val readableLabel: String
        get() = when {
            label.contains("instructor", ignoreCase = true) -> "教师画面"
            label.contains("encoder", ignoreCase = true) || label.contains("screen", ignoreCase = true) -> "电脑屏幕"
            label.isNotEmpty() -> label
            else -> "视频流"
        }

    val isInstructor: Boolean get() = label.contains("instructor", ignoreCase = true)
    val isEncoder: Boolean get() = label.contains("encoder", ignoreCase = true) || label.contains("screen", ignoreCase = true)
}

// ════════════════════════════════════════
//  回放视频
// ════════════════════════════════════════

data class LmsReplayVideo(
    val id: Int = 0,
    val label: String = "",
    val mute: Boolean = false,
    val isBestAudio: Boolean = false,
    val playType: String = "",
    val downloadUrl: String = "",
    /** 在线播放地址，与 downloadUrl 是两个不同用途的字段（上游协议：download_url 用于下载，play_url 用于在线播放）。 */
    val playUrl: String = "",
    val fileKey: String = "",
    val size: Int = 0
) {
    /** 播放时优先用 playUrl；缺失才回退 downloadUrl（容错，防止个别活动类型没给这个字段）。 */
    val streamUrl: String
        get() = playUrl.ifBlank { downloadUrl }

    /** 人类可读的标签 */
    val readableLabel: String
        get() = when {
            label.contains("教师", ignoreCase = true) || label.contains("instructor", ignoreCase = true) -> "教师画面"
            label.contains("屏幕", ignoreCase = true) || label.contains("screen", ignoreCase = true) || label.contains("encoder", ignoreCase = true) -> "电脑屏幕"
            label.contains("best", ignoreCase = true) || isBestAudio -> "最佳音源"
            label.isNotEmpty() -> label
            else -> "视频 $id"
        }

    /** 文件大小 */
    val readableSize: String
        get() = when {
            size <= 0 -> ""
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "%.1f MB".format(size / (1024.0 * 1024.0))
        }
}
