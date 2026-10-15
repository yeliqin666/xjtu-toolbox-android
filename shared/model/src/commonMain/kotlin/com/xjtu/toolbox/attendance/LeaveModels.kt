package com.xjtu.toolbox.attendance

enum class LeaveType(val code: String, val displayName: String) {
    SICK("SICK", "病假"),
    PERSONAL("PERSONAL", "私事假");

    companion object {
        fun fromCode(code: String?): LeaveType =
            if (code?.uppercase() == "SICK") SICK else PERSONAL
    }
}

data class LeaveApprover(
    val userId: String,
    val name: String,
)

data class LeaveFlowNode(
    val nodeNo: Int,
    val nodeRole: String,
    val nodeName: String,
    val configured: Boolean,
    val approvers: List<LeaveApprover>,
)

data class LeaveFlowPreview(
    val nextNodeNo: Int,
    val nextApprovers: List<LeaveApprover>,
    val nodes: List<LeaveFlowNode>,
)

data class LeaveEvidence(
    val evidenceId: String,
    val fileName: String,
    val fileType: String,
    val fileSize: Long,
)

data class LeaveApprovalNode(
    val nodeName: String,
    val status: String,
    val approverName: String,
    val processedName: String,
    val processedTime: String,
    val remark: String,
    val actionType: String,
    val autoReason: String,
)

data class LeaveCourse(
    val courseId: String,
    val courseName: String,
    val courseDate: String,
    val startSection: Int,
    val endSection: Int,
    val teacherNames: String,
)

data class LeaveRecord(
    val leaveId: String,
    val leaveType: String,
    val reason: String,
    val applyTime: String,
    val startTime: String,
    val endTime: String,
    val status: String,
    val displayStatus: String,
    val approvalStatus: String,
    val cancelStatus: String,
    val sourceType: String,
    val durationMinutes: Int,
    val durationText: String,
    val archiveId: String,
    val withdrawable: Boolean,
    val cancellable: Boolean,
    val evidenceFiles: List<LeaveEvidence>,
    val approvalNodes: List<LeaveApprovalNode>,
    val courses: List<LeaveCourse>,
) {
    val effectiveStatus: String
        get() = when {
            cancelStatus.equals("CANCELLED", true) -> "CANCELLED"
            displayStatus.isNotBlank() -> displayStatus
            status.isNotBlank() -> status
            else -> approvalStatus
        }
}

data class LeavePage(
    val records: List<LeaveRecord>,
    val total: Int,
    val pageNum: Int,
    val pageSize: Int,
)

data class LeaveSemesterWindow(
    val semesterId: String,
    val academicYear: String,
    val semesterName: String,
    val startDate: String,
    val endDate: String,
    val maxStudentLeaveMinutes: Int,
)

fun leaveStatusLabel(code: String): String = when (code.uppercase()) {
    "PENDING" -> "审批中"
    "APPROVED" -> "已通过"
    "REJECTED" -> "已驳回"
    "CANCELLED" -> "已销假"
    "WITHDRAWN" -> "已撤回"
    "PENDING_CANCEL" -> "销假确认中"
    else -> code.ifBlank { "未知" }
}
