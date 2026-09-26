package com.xjtu.toolbox.attendance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.SiteSession
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID

class LeaveApi(private val site: SiteSession) {

    private val jsonType = "application/json".toMediaType()

    fun getSemesterWindow(): LeaveSemesterWindow {
        val data = KqHttp.dataObject(getJson("/student/leaves/semester-window"))
        return LeaveSemesterWindow(
            semesterId = KqHttp.str(data, "semesterId", "termId", "id"),
            academicYear = KqHttp.str(data, "academicYear", "schoolYear", "year"),
            semesterName = KqHttp.str(data, "semesterName", "termName", "name"),
            startDate = KqHttp.str(data, "startDate", "beginDate"),
            endDate = KqHttp.str(data, "endDate", "finishDate"),
            maxStudentLeaveMinutes = KqHttp.int(data, "maxStudentLeaveMinutes", "maxLeaveMinutes"),
        )
    }

    fun getFlowPreview(type: LeaveType): LeaveFlowPreview {
        val data = KqHttp.dataObject(getJson("/student/leaves/flow-preview", mapOf("leaveType" to type.code)))
        val approvers = KqHttp.rows(data.get("nextApprovers")).map { parseApprover(it) }
        val nodes = KqHttp.rows(data.get("nodes")).map { node ->
            LeaveFlowNode(
                nodeNo = KqHttp.int(node, "nodeNo", "nodeNumber", "sort"),
                nodeRole = KqHttp.str(node, "nodeRole", "role"),
                nodeName = KqHttp.str(node, "nodeName", "name"),
                configured = KqHttp.bool(node, "configured"),
                approvers = KqHttp.rows(node.get("approvers")).map { parseApprover(it) },
            )
        }
        return LeaveFlowPreview(
            nextNodeNo = KqHttp.int(data, "nextNodeNo", "nodeNo"),
            nextApprovers = approvers,
            nodes = nodes,
        )
    }

    fun getLeavePage(
        pageNum: Int = 1,
        pageSize: Int = 20,
        status: String = "",
        leaveType: String = "",
    ): LeavePage {
        val filter = JsonObject()
        when (status) {
            "CANCELLED" -> {
                filter.addProperty("status", "APPROVED")
                filter.addProperty("cancelStatus", "CANCELLED")
            }
            "APPROVED" -> {
                filter.addProperty("status", "APPROVED")
                filter.addProperty("cancelStatus", "PENDING_CANCEL")
            }
            else -> if (status.isNotBlank()) filter.addProperty("status", status)
        }
        if (leaveType.isNotBlank()) filter.addProperty("leaveType", leaveType)
        val body = JsonObject().apply {
            addProperty("pageNum", pageNum.coerceAtLeast(1))
            addProperty("pageSize", pageSize.coerceIn(1, 100))
            add("data", filter)
        }
        val data = KqHttp.dataObject(postJson("/student/leaves/page", body, retryable = true))
        val rows = KqHttp.rows(data.get("rows")).ifEmpty { KqHttp.rows(data) }
        val parsed = rows.map { parseLeaveRecord(it) }
        val remotePage = KqHttp.int(data, "pageNum", "current").takeIf { it > 0 } ?: pageNum
        val remoteSize = KqHttp.int(data, "pageSize", "size").takeIf { it > 0 } ?: pageSize
        return LeavePage(parsed, KqHttp.int(data, "total", "totalCount", "count"), remotePage, remoteSize)
    }

    fun uploadEvidence(fileName: String, contentType: String, bytes: ByteArray): JsonObject {
        val mime = contentType.ifBlank { "application/octet-stream" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                bytes.toRequestBody(mime.toMediaType()),
            )
            .build()
        val req = Request.Builder()
            .url("${KqHttp.baseOf(site)}/student/leaves/evidence/upload")
            .post(body)
            .build()
        val data = KqHttp.obj(KqHttp.execute(site, req, "/student/leaves/evidence/upload", retryable = false).get("data"))
            ?: throw RuntimeException("附件上传成功但响应缺少文件信息")
        return data
    }

    fun createLeave(
        type: LeaveType,
        startTime: String,
        endTime: String,
        reason: String,
        nextApproverUserId: String,
        evidenceFiles: List<JsonObject>,
        requestId: String = UUID.randomUUID().toString(),
    ): String {
        val files = JsonArray()
        evidenceFiles.forEach { files.add(it) }
        val body = JsonObject().apply {
            addProperty("requestId", requestId)
            addProperty("leaveType", type.code)
            addProperty("startTime", startTime)
            addProperty("endTime", endTime)
            addProperty("reason", reason)
            addProperty("nextApproverUserId", nextApproverUserId)
            add("evidenceFiles", files)
        }
        val data = KqHttp.dataObject(postJson("/student/leaves", body, retryable = false))
        return KqHttp.str(data, "leaveId", "id")
    }

    fun withdrawLeave(leaveId: String, reason: String, requestId: String = UUID.randomUUID().toString()) {
        val body = JsonObject().apply {
            addProperty("requestId", requestId)
            addProperty("reason", reason)
        }
        postJson("/student/leaves/${encode(leaveId)}/withdraw", body, retryable = false)
    }

    fun cancelLeave(leaveId: String, requestId: String = UUID.randomUUID().toString()) {
        val body = JsonObject().apply { addProperty("requestId", requestId) }
        postJson("/student/leaves/${encode(leaveId)}/cancel", body, retryable = false)
    }

    private fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonObject {
        val req = Request.Builder().url(KqHttp.buildUrl(site, path, query)).get().build()
        return KqHttp.execute(site, req, path, retryable = true)
    }

    private fun postJson(path: String, body: JsonObject, retryable: Boolean): JsonObject {
        val req = Request.Builder()
            .url(KqHttp.buildUrl(site, path))
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return KqHttp.execute(site, req, path, retryable)
    }

    private fun parseApprover(row: JsonObject) = LeaveApprover(
        userId = KqHttp.str(row, "approverUserId", "userId", "id"),
        name = KqHttp.str(row, "approverName", "userName", "name"),
    )

    private fun parseLeaveRecord(row: JsonObject): LeaveRecord {
        val evidence = KqHttp.rows(row.get("evidenceFiles")).map { file ->
            LeaveEvidence(
                evidenceId = KqHttp.str(file, "evidenceId", "id", "fileId"),
                fileName = KqHttp.str(file, "fileName", "originalName", "name"),
                fileType = KqHttp.str(file, "fileType", "contentType", "mimeType"),
                fileSize = KqHttp.long(file, "fileSize", "size"),
            )
        }
        val nodes = KqHttp.rows(row.get("approvalNodes")).map { node ->
            LeaveApprovalNode(
                nodeName = KqHttp.str(node, "nodeName", "name"),
                status = KqHttp.str(node, "status", "nodeStatus"),
                approverName = KqHttp.str(node, "approverName", "candidateName"),
                processedName = KqHttp.str(node, "processedName", "processorName"),
                processedTime = KqHttp.str(node, "processedTime", "processTime"),
                remark = KqHttp.str(node, "remark", "comment"),
                actionType = KqHttp.str(node, "actionType", "action"),
                autoReason = KqHttp.str(node, "autoReason", "reason"),
            )
        }
        val courses = KqHttp.rows(row.get("courses")).map { course ->
            LeaveCourse(
                courseId = KqHttp.str(course, "courseId", "id"),
                courseName = KqHttp.str(course, "courseName", "name"),
                courseDate = KqHttp.str(course, "courseDate", "date"),
                startSection = KqHttp.int(course, "startSection", "startSectionNo"),
                endSection = KqHttp.int(course, "endSection", "endSectionNo"),
                teacherNames = KqHttp.str(course, "teacherNames", "teacherName"),
            )
        }
        val status = KqHttp.str(row, "status", "approvalStatus")
        val cancelStatus = KqHttp.str(row, "cancelStatus")
        val displayStatus = KqHttp.str(row, "displayStatus")
        val effective = when {
            cancelStatus.equals("CANCELLED", true) -> "CANCELLED"
            displayStatus.isNotBlank() -> displayStatus
            status.isNotBlank() -> status
            else -> KqHttp.str(row, "approvalStatus")
        }
        val withdrawable = if (KqHttp.first(row, "withdrawable", "canWithdraw") != null) {
            KqHttp.bool(row, "withdrawable", "canWithdraw")
        } else {
            effective.equals("PENDING", true)
        }
        val cancellable = if (KqHttp.first(row, "cancellable", "canCancel") != null) {
            KqHttp.bool(row, "cancellable", "canCancel")
        } else {
            effective.equals("APPROVED", true)
        }
        return LeaveRecord(
            leaveId = KqHttp.str(row, "leaveId", "id"),
            leaveType = KqHttp.str(row, "leaveType", "type"),
            reason = KqHttp.str(row, "reason", "leaveReason"),
            applyTime = KqHttp.str(row, "applyTime", "createTime"),
            startTime = KqHttp.str(row, "startTime", "leaveStartTime"),
            endTime = KqHttp.str(row, "endTime", "leaveEndTime"),
            status = status,
            displayStatus = displayStatus,
            approvalStatus = KqHttp.str(row, "approvalStatus"),
            cancelStatus = cancelStatus,
            sourceType = KqHttp.str(row, "sourceType"),
            durationMinutes = KqHttp.int(row, "durationMinutes", "leaveMinutes"),
            durationText = KqHttp.str(row, "durationText", "duration"),
            archiveId = KqHttp.str(row, "archiveId"),
            withdrawable = withdrawable,
            cancellable = cancellable,
            evidenceFiles = evidence,
            approvalNodes = nodes,
            courses = courses,
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
