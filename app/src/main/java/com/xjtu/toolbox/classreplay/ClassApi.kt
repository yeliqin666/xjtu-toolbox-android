package com.xjtu.toolbox.classreplay

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "ClassApi"
private const val CLASS_BASE_URL = "https://class.xjtu.edu.cn"

// ════════════════════════════════════════
//  Gson 安全扩展
// ════════════════════════════════════════

/** 安全获取字符串（处理 JsonNull） */
private fun JsonElement?.safeString(): String? =
    if (this == null || this.isJsonNull) null else try { this.asString } catch (_: Exception) { null }

/** 安全获取 Boolean（处理 JsonNull） */
private fun JsonElement?.safeBoolean(default: Boolean = false): Boolean =
    if (this == null || this.isJsonNull) default else try { this.asBoolean } catch (_: Exception) { default }

/** 安全获取 Int（处理 JsonNull） */
private fun JsonElement?.safeInt(default: Int = 0): Int =
    if (this == null || this.isJsonNull) default else try { this.asInt } catch (_: Exception) { default }

// ════════════════════════════════════════
//  数据模型
// ════════════════════════════════════════

/** 课程 */
data class Course(
    val id: Int,
    val name: String,
    val displayName: String,
    val courseCode: String,
    val department: String,
    val instructors: List<String>,
    val isStarted: Boolean,
    val isClosed: Boolean,
    val startDate: String?,
    val endDate: String?,
    val semesterId: Int = 0
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
}

/** 直播/回放活动 */
data class LiveActivity(
    val id: Int,
    val title: String,
    val type: String,        // "lecture_live" / "tencent_meeting" 等
    val startTime: String,
    val endTime: String,
    val courseId: Int,
    val isClosed: Boolean,
    val externalLiveId: String?
)

/** 回放视频源 */
data class ReplayVideo(
    val cameraId: Int,
    val cameraType: String,  // "instructor" (教师/直播源) / "encoder" (电脑/屏幕源)
    val url: String,         // class-rms.xjtu.edu.cn 预览 URL
    val mute: Int            // 0=有声, 1=静音
) {
    /** 用户友好的标签 */
    val label: String get() = when (cameraType) {
        "instructor" -> "教师直播"
        "encoder" -> "电脑屏幕"
        else -> cameraType
    }
}

/** 回放活动详情 (包含视频源) */
data class ReplayDetail(
    val activityId: Int,
    val title: String,
    val startTime: String,
    val endTime: String,
    val roomName: String?,
    val instructorNames: List<String>,
    val replayVideos: List<ReplayVideo>
)

// ════════════════════════════════════════
//  API 调用
// ════════════════════════════════════════

/**
 * 获取用户课程列表（分页）
 */
fun fetchCourses(
    site: SiteSession,
    page: Int = 1,
    pageSize: Int = 50,
    keyword: String = ""
): Pair<List<Course>, Int> {
    val body = Gson().toJson(mapOf(
        "fields" to "id,name,course_code,department(id,name),display_name,start_date,end_date,is_started,is_closed,instructors(id,name)",
        "page" to page,
        "page_size" to pageSize,
        "conditions" to mapOf(
            "keyword" to keyword,
            "classify_type" to "recently_started",
            "display_studio_list" to false
        ),
        "showScorePassedStatus" to false
    ))

    val req = authenticatedRequest("$CLASS_BASE_URL/api/my-courses")
        .header("Content-Type", "application/json;charset=utf-8")
        .post(body.toRequestBody("application/json".toMediaType()))

    val resp = runBlocking { site.executeWithReAuth(req.build()) }
    val json = resp.body?.string().safeParseJsonObject()
    resp.close()

    val total = json.get("total")?.asInt ?: 0
    val courses = mutableListOf<Course>()

    json.getAsJsonArray("courses")?.forEach { elem ->
        try {
            val c = elem.asJsonObject

            // department 可能是 null / JsonNull / JsonObject
            val deptElem = c.get("department")
            val deptName = if (deptElem != null && deptElem.isJsonObject)
                deptElem.asJsonObject.get("name").safeString() ?: "" else ""

            // instructors 可能是 null / JsonNull / JsonArray
            val instrElem = c.get("instructors")
            val instrList = if (instrElem != null && instrElem.isJsonArray)
                instrElem.asJsonArray.mapNotNull { it.asJsonObject.get("name").safeString() }
            else emptyList()

            courses.add(Course(
                id = c.get("id").safeInt(),
                name = c.get("name").safeString() ?: "",
                displayName = c.get("display_name").safeString()
                    ?: c.get("name").safeString() ?: "",
                courseCode = c.get("course_code").safeString() ?: "",
                department = deptName,
                instructors = instrList,
                isStarted = c.get("is_started").safeBoolean(),
                isClosed = c.get("is_closed").safeBoolean(),
                startDate = c.get("start_date").safeString(),
                endDate = c.get("end_date").safeString(),
                semesterId = c.get("semester_id").safeInt()
            ))
        } catch (e: Exception) {
            Log.w(TAG, "parseCourse error: ${e.message}")
        }
    }

    Log.d(TAG, "fetchCourses: ${courses.size} courses, total=$total")
    return Pair(courses, total)
}

/**
 * 获取课程的直播/回放活动列表（分页）
 */
fun fetchLiveActivities(
    site: SiteSession,
    courseId: Int,
    page: Int = 1,
    pageSize: Int = 20
): Pair<List<LiveActivity>, Int> {
    val url = "$CLASS_BASE_URL/api/courses/$courseId/live-activities?" +
        "status=&types[]=tencent_meeting&types[]=lecture_live&types[]=third_party_live&" +
        "page=$page&page_size=$pageSize"

    val req = authenticatedRequest(url).get().build()
    val resp = runBlocking { site.executeWithReAuth(req) }
    val json = resp.body?.string().safeParseJsonObject()
    resp.close()

    val total = json.get("total")?.asInt ?: 0
    val activities = mutableListOf<LiveActivity>()

    json.getAsJsonArray("items")?.forEach { elem ->
        try {
            val a = elem.asJsonObject
            val data = a.getAsJsonObject("data")
            activities.add(LiveActivity(
                id = a.get("id").asInt,
                title = a.get("title")?.asString ?: "",
                type = a.get("type")?.asString ?: "",
                startTime = a.get("start_time")?.asString ?: "",
                endTime = a.get("end_time")?.asString ?: "",
                courseId = a.get("course_id")?.asInt ?: courseId,
                isClosed = a.get("is_closed")?.asBoolean ?: false,
                externalLiveId = data?.get("external_live_id")?.asString
            ))
        } catch (e: Exception) {
            Log.w(TAG, "parseLiveActivity error: ${e.message}")
        }
    }

    Log.d(TAG, "fetchLiveActivities: courseId=$courseId, ${activities.size} activities, total=$total")
    return Pair(activities, total)
}

/**
 * 获取回放详情（包含视频源 URL）
 */
fun fetchReplayDetail(
    site: SiteSession,
    activityId: Int
): ReplayDetail? {
    val req = authenticatedRequest("$CLASS_BASE_URL/api/activities/$activityId").get().build()
    val resp = runBlocking { site.executeWithReAuth(req) }
    val json = resp.body?.string().safeParseJsonObject()
    resp.close()

    return try {
        val data = json.getAsJsonObject("data")
        val liveDetail = data?.getAsJsonObject("external_live_detail")
            ?: return null

        val videos = liveDetail.getAsJsonArray("replay_videos")?.mapNotNull { v ->
            try {
                val vo = v.asJsonObject
                ReplayVideo(
                    cameraId = vo.get("camera_id").asInt,
                    cameraType = vo.get("camera_type")?.asString ?: "",
                    url = vo.get("url")?.asString ?: "",
                    mute = vo.get("mute")?.asInt ?: 0
                )
            } catch (e: Exception) {
                Log.w(TAG, "parseReplayVideo error: ${e.message}")
                null
            }
        } ?: emptyList()

        val room = liveDetail.getAsJsonObject("room")
        val roomName = room?.get("room_name")?.asString

        val instructorNames = liveDetail.getAsJsonArray("instructor_names")
            ?.map { it.asString } ?: emptyList()

        ReplayDetail(
            activityId = activityId,
            title = json.get("title")?.asString ?: "",
            startTime = liveDetail.get("start_time")?.asString ?: "",
            endTime = liveDetail.get("end_time")?.asString ?: "",
            roomName = roomName,
            instructorNames = instructorNames,
            replayVideos = videos
        ).also {
            Log.d(TAG, "fetchReplayDetail: activityId=$activityId, ${it.replayVideos.size} videos, room=$roomName")
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchReplayDetail error", e)
        null
    }
}

/**
 * 解析视频预览 URL → 获取实际播放 URL (S3 presigned)
 * class-rms.xjtu.edu.cn/...preview?previewToken=... → 302 → review-class.xjtu.edu.cn/...mp4
 *
 * 注意：class-rms 服务器校验 Origin / Referer，必须携带 class.xjtu.edu.cn 头
 */
fun resolveVideoUrl(
    site: SiteSession,
    previewUrl: String
): String? {
    return try {
        // 不跟随重定向，取 Location header
        val noRedirectClient = site.client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val req = Request.Builder()
            .url(previewUrl)
            .header("Accept", "video/webm,video/ogg,video/*;q=0.9,*/*;q=0.5")
            .header("Origin", "https://class.xjtu.edu.cn")
            .header("Referer", "https://class.xjtu.edu.cn/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Safari/537.36")
            .get()
            .build()

        val resp = noRedirectClient.newCall(req).execute()
        val location = resp.header("Location")
        val code = resp.code
        resp.close()

        Log.d(TAG, "resolveVideoUrl: $code, location=${location?.take(100)}")

        if (code == 302 && location != null) {
            location
        } else {
            Log.w(TAG, "resolveVideoUrl: unexpected code=$code for ${previewUrl.take(80)}")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "resolveVideoUrl error", e)
        null
    }
}

private fun authenticatedRequest(url: String): Request.Builder =
    Request.Builder()
        .url(url)
        .header("Referer", "$CLASS_BASE_URL/user/index")
        .header("Accept", "application/json, text/plain, */*")
