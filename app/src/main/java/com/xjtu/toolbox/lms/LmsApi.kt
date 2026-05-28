package com.xjtu.toolbox.lms

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL

private const val TAG = "LmsApi"

// ════════════════════════════════════════
//  Gson 安全扩展
// ════════════════════════════════════════

private fun JsonElement?.safeString(): String? =
    if (this == null || this.isJsonNull) null else try { this.asString } catch (_: Exception) { null }

private fun JsonElement?.safeInt(default: Int = 0): Int =
    if (this == null || this.isJsonNull) default else try { this.asInt } catch (_: Exception) { default }

private fun JsonElement?.safeBoolean(default: Boolean = false): Boolean =
    if (this == null || this.isJsonNull) default else try { this.asBoolean } catch (_: Exception) { default }

private fun JsonElement?.safeArray(): JsonArray =
    if (this == null || this.isJsonNull || !this.isJsonArray) JsonArray() else this.asJsonArray

private fun JsonElement?.safeObject(): JsonObject? =
    if (this == null || this.isJsonNull || !this.isJsonObject) null else this.asJsonObject

// ════════════════════════════════════════
//  LmsApi — 思源学堂 API 封装
// ════════════════════════════════════════

class LmsApi(private val site: SiteSession) {

    private val baseUrl = "https://lms.xjtu.edu.cn"
    private val rmsBaseUrl = "https://rms-v5.xjtu.edu.cn"

    // 缓存
    private var cachedUserInfo: LmsUserInfo? = null
    private val replayVideoCache = mutableMapOf<String, List<LmsReplayVideo>>()
    private val playerTokenCache = mutableMapOf<Int, String>()
    private val rmsTokenCache = mutableMapOf<Int, String>()

    // ── 公有接口 ──────────────────────────

    /**
     * 获取当前登录用户基本信息
     * 返回值来自 /user/index 页面中的 globalData.user
     */
    fun getUserInfo(refresh: Boolean = false): LmsUserInfo {
        if (cachedUserInfo != null && !refresh) return cachedUserInfo!!

        val page = getIndexPage()
        val userBlock = extractJsBlock(page, "user", "dept")
        val deptBlock = extractJsBlock(page, "dept", "locale")

        val info = LmsUserInfo(
            id = extractJsKeyValue(userBlock, "id")?.toIntOrNull() ?: 0,
            name = extractJsKeyValue(userBlock, "name") ?: "",
            userNo = extractJsKeyValue(userBlock, "userNo") ?: "",
            orgId = extractJsKeyValue(userBlock, "orgId")?.toIntOrNull() ?: 0,
            mobile = extractJsKeyValue(userBlock, "mobile") ?: "",
            orgName = extractJsKeyValue(userBlock, "orgName") ?: "",
            orgCode = extractJsKeyValue(userBlock, "orgCode") ?: "",
            role = extractJsKeyValue(userBlock, "role") ?: "",
            hasAiAbility = extractJsKeyValue(userBlock, "hasAiAbility") == "true",
            dept = LmsDepartment(
                id = extractJsKeyValue(deptBlock, "id")?.toIntOrNull() ?: 0,
                name = extractJsKeyValue(deptBlock, "name") ?: "",
                code = extractJsKeyValue(deptBlock, "code") ?: ""
            )
        )
        cachedUserInfo = info
        return info
    }

    /**
     * 获取我的课程列表
     */
    fun getMyCourses(): List<LmsCourseSummary> {
        val data = postJson("$baseUrl/api/my-courses")
        val courses = data?.getAsJsonArray("courses") ?: return emptyList()
        return courses.mapNotNull { elem ->
            try {
                val obj = elem.asJsonObject
                extractCourseSummary(obj)
            } catch (e: Exception) {
                Log.w(TAG, "getMyCourses: skip bad course", e)
                null
            }
        }
    }

    /**
     * 获取课程详细信息
     */
    fun getCourseDetail(courseId: Int): LmsCourseDetail {
        val data = getJson("$baseUrl/api/courses/$courseId")
            ?: throw RuntimeException("获取课程详情失败")
        val summary = extractCourseSummary(data)
        return LmsCourseDetail(
            summary = summary,
            subjectCode = data.get("subject_code").safeString() ?: "",
            displayName = data.get("display_name").safeString() ?: "",
            publicScope = data.get("public_scope").safeString() ?: "",
            cover = data.get("cover").safeString() ?: ""
        )
    }

    /**
     * 获取课程活动列表
     */
    fun getCourseActivities(courseId: Int): List<LmsActivity> {
        val data = getJson("$baseUrl/api/courses/$courseId/activities")
            ?: return emptyList()
        val activities = data.getAsJsonArray("activities") ?: return emptyList()
        return activities.mapNotNull { elem ->
            try {
                val obj = elem.asJsonObject
                extractActivityBrief(obj)
            } catch (e: Exception) {
                Log.w(TAG, "getCourseActivities: skip bad activity", e)
                null
            }
        }
    }

    /**
     * 获取活动详细信息
     * - homework 类型自动注入 submissionList
     * - lesson 类型自动注入 replayVideos + replayDownloadUrls
     */
    fun getActivityDetail(activityId: Int): LmsActivity {
        val data = getJson("$baseUrl/api/activities/$activityId")
            ?: throw RuntimeException("获取活动详情失败")
        var detail = extractActivityDetail(data)

        // homework → 自动注入提交列表及批改附件
        if (detail.type == LmsActivityType.HOMEWORK) {
            try {
                val submissionList = getSubmissionList(activityId, activityDetail = data)
                val enrichedItems = submissionList.list.map { sub -> injectMarkedAttachments(sub) }
                detail = detail.copy(submissionList = submissionList.copy(list = enrichedItems))
            } catch (e: Exception) {
                Log.w(TAG, "getActivityDetail: failed to get submissions for $activityId", e)
            }
        }

        return detail
    }

    // ── 内部方法 ──────────────────────

    private fun injectMarkedAttachments(sub: LmsSubmissionItem): LmsSubmissionItem {
        if (sub.uploads.isEmpty()) return sub
        return try {
            val data = getJson("$baseUrl/api/submissions/${sub.id}/marked_attachments") ?: return sub
            val rules = data.get("rules").safeArray()
            if (rules.isEmpty) return sub
            val nameToUrl = mutableMapOf<String, String>()
            for (rule in rules) {
                val r = rule.safeObject() ?: continue
                val name = (r.get("origin_upload_name").safeString()
                    ?: r.get("origin_name").safeString()
                    ?: r.get("name").safeString())?.trim() ?: continue
                val url = (r.get("marked_attachment_url").safeString()
                    ?: r.get("attachment_url").safeString()
                    ?: r.get("url").safeString())?.trim() ?: continue
                if (name.isNotEmpty() && url.isNotEmpty()) nameToUrl[name.lowercase()] = url
            }
            if (nameToUrl.isEmpty()) return sub
            val enriched = sub.uploads.map { upload ->
                val attachUrl = nameToUrl[upload.name.lowercase()]
                if (attachUrl != null) upload.copy(attachmentUrl = attachUrl) else upload
            }
            sub.copy(uploads = enriched)
        } catch (e: Exception) {
            Log.w(TAG, "injectMarkedAttachments: failed for submission ${sub.id}", e)
            sub
        }
    }

    /** 流式下载到 OutputStream（带认证），用于大文件保存到本地 */
    fun downloadToStream(url: String, outputStream: java.io.OutputStream): Boolean {
        return try {
            val resp = runBlocking { site.executeWithReAuth(authenticatedRequest(url).get().build()) }
            resp.body?.byteStream()?.use { input ->
                outputStream.use { out -> input.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "downloadToStream failed: $url", e)
            false
        }
    }

    /** 下载任意 URL 的字节数组（带认证），用于附件预览 */
    fun downloadBytes(url: String): ByteArray? {
        return try {
            val resp = runBlocking { site.executeWithReAuth(authenticatedRequest(url).get().build()) }
            resp.body?.use { it.bytes() }
        } catch (e: Exception) {
            Log.e(TAG, "downloadBytes failed: $url", e)
            null
        }
    }

    private fun getIndexPage(): String {
        val req = authenticatedRequest("$baseUrl/user/index").get().build()
        val resp = runBlocking { site.executeWithReAuth(req) }
        return resp.body?.use { it.string() } ?: ""
    }

    private fun getJson(url: String, headers: Map<String, String>? = null): JsonObject? {
        val builder = authenticatedRequest(url).get()
        headers?.forEach { (k, v) -> builder.header(k, v) }
        val resp = runBlocking { site.executeWithReAuth(builder.build()) }
        val body = resp.body?.use { it.string() } ?: return null
        return try {
            body.safeParseJsonObject()
        } catch (e: Exception) {
            Log.e(TAG, "getJson: parse failed for $url", e)
            null
        }
    }

    private fun postJson(url: String): JsonObject? {
        val emptyBody = "".toRequestBody("application/json".toMediaType())
        val req = authenticatedRequest(url)
            .post(emptyBody)
            .build()
        val resp = runBlocking { site.executeWithReAuth(req) }
        val body = resp.body?.use { it.string() } ?: return null
        return try {
            body.safeParseJsonObject()
        } catch (e: Exception) {
            Log.e(TAG, "postJson: parse failed for $url", e)
            null
        }
    }

    private fun authenticatedRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Referer", "$baseUrl/user/courses")
            .header("Accept", "application/json, text/plain, */*")

    // ── 作业提交列表 ──────────────────────

    private fun getSubmissionList(
        activityId: Int,
        userId: Int? = null,
        groupId: Int? = null,
        submitByGroup: Boolean? = null,
        activityDetail: JsonObject? = null
    ): LmsSubmissionListResponse {
        val detail = activityDetail ?: getJson("$baseUrl/api/activities/$activityId")
            ?: throw RuntimeException("获取活动详情失败")

        val isByGroup = submitByGroup ?: detail.get("submit_by_group").safeBoolean()

        val url = if (isByGroup) {
            val gid = groupId ?: detail.get("group_id").safeInt()
            if (gid == 0) throw RuntimeException("小组作业但找不到 group_id")
            "$baseUrl/api/activities/$activityId/groups/$gid/submission_list"
        } else {
            val uid = userId ?: getUserInfo().id
            if (uid == 0) throw RuntimeException("无法获取 user_id")
            "$baseUrl/api/activities/$activityId/students/$uid/submission_list"
        }

        val data = getJson(url) ?: throw RuntimeException("获取提交列表失败")
        return extractSubmissionList(data)
    }

    // ── 课堂回放视频 ──────────────────────

    private fun getLessonPlayerUrl(lessonActivityId: Int): String {
        val data = getJson("$baseUrl/api/lessons/$lessonActivityId/player-url?from_page=course")
            ?: throw RuntimeException("获取播放器 URL 失败")
        return data.get("url").safeString()
            ?.takeIf { it.isNotEmpty() }
            ?: throw RuntimeException("播放器 URL 为空")
    }

    private fun getLessonPlayerToken(lessonActivityId: Int): String {
        playerTokenCache[lessonActivityId]?.let { return it }
        val playerUrl = getLessonPlayerUrl(lessonActivityId)
        val token = try {
            URL(playerUrl).let { url ->
                url.query?.split("&")
                    ?.associate { it.split("=", limit = 2).let { parts -> parts[0] to (parts.getOrNull(1) ?: "") } }
                    ?.get("token")
            }
        } catch (_: Exception) { null }
            ?: throw RuntimeException("播放器 URL 中找不到 token")
        playerTokenCache[lessonActivityId] = token
        return token
    }

    private fun exchangeEmbedToken(playerToken: String): String {
        val data = getJson("$rmsBaseUrl/api/v1/auth/embed-token?token=$playerToken")
            ?: throw RuntimeException("embed-token 交换失败")

        // 检查错误
        data.safeObject()?.let { obj ->
            obj.get("error").safeObject()?.let { error ->
                val code = error.get("code").safeInt()
                if (code != 0) {
                    throw RuntimeException("embed-token 交换失败: code=$code, message=${error.get("message").safeString()}")
                }
            }
        }

        return data.get("data").safeString()
            ?.takeIf { it.isNotEmpty() }
            ?: throw RuntimeException("embed-token 返回空 rms_token")
    }

    private fun getLessonRmsToken(lessonActivityId: Int): String {
        rmsTokenCache[lessonActivityId]?.let { return it }
        val playerToken = getLessonPlayerToken(lessonActivityId)
        val rmsToken = exchangeEmbedToken(playerToken)
        rmsTokenCache[lessonActivityId] = rmsToken
        return rmsToken
    }

    private fun getReplayVideos(
        replayCode: String,
        lessonActivityId: Int? = null
    ): List<LmsReplayVideo> {
        replayVideoCache[replayCode]?.let { return it }

        val headers = mutableMapOf<String, String>()
        if (lessonActivityId != null) {
            try {
                val rmsToken = getLessonRmsToken(lessonActivityId)
                headers["Authorization"] = "Bearer $rmsToken"
            } catch (e: Exception) {
                Log.w(TAG, "getReplayVideos: failed to get RMS token", e)
            }
        }

        val data = getJson("$rmsBaseUrl/api/embed/lesson-activities/captures/$replayCode", headers)
            ?: return emptyList()

        // 检查错误
        data.get("error").safeObject()?.let { error ->
            val code = error.get("code").safeInt()
            if (code != 0) {
                Log.w(TAG, "getReplayVideos: error code=$code, message=${error.get("message").safeString()}")
                return emptyList()
            }
        }

        var videosArray = data.get("lesson_videos").safeArray()
        if (videosArray.size() == 0) {
            // 尝试嵌套结构
            data.get("data").safeObject()?.let { inner ->
                videosArray = inner.get("lesson_videos").safeArray()
            }
        }

        val videos = videosArray.mapNotNull { elem ->
            try {
                val obj = elem.asJsonObject
                LmsReplayVideo(
                    id = obj.get("id").safeInt(),
                    label = obj.get("label").safeString() ?: "",
                    mute = obj.get("mute").safeBoolean(),
                    isBestAudio = obj.get("is_best_audio").safeBoolean(),
                    playType = obj.get("play_type").safeString() ?: "",
                    downloadUrl = obj.get("download_url").safeString() ?: "",
                    fileKey = obj.get("file_key").safeString() ?: "",
                    size = obj.get("size").safeInt()
                )
            } catch (e: Exception) {
                Log.w(TAG, "getReplayVideos: skip bad video", e)
                null
            }
        }
        replayVideoCache[replayCode] = videos
        return videos
    }

    // ── 数据提取 ──────────────────────────

    private fun extractCourseSummary(obj: JsonObject): LmsCourseSummary {
        val instructors = obj.get("instructors").safeArray().mapNotNull { elem ->
            elem.safeObject()?.let { LmsInstructor(it.get("id").safeInt(), it.get("name").safeString() ?: "") }
        }
        val ay = obj.get("academic_year").safeObject()
        val sem = obj.get("semester").safeObject()
        val dept = obj.get("department").safeObject()
        val attrs = obj.get("course_attributes").safeObject()

        return LmsCourseSummary(
            id = obj.get("id").safeInt(),
            name = obj.get("name").safeString() ?: "",
            courseCode = obj.get("course_code").safeString() ?: "",
            courseType = obj.get("course_type").safeInt(),
            credit = obj.get("credit").safeString() ?: "",
            compulsory = obj.get("compulsory").safeBoolean(),
            startDate = obj.get("start_date").safeString(),
            endDate = obj.get("end_date").safeString(),
            academicYear = LmsAcademicYear(
                id = ay?.get("id").safeInt() ?: 0,
                code = ay?.get("code").safeString() ?: "",
                name = ay?.get("name").safeString() ?: "",
                sort = ay?.get("sort").safeInt() ?: 0
            ),
            semester = LmsSemester(
                id = sem?.get("id").safeInt() ?: 0,
                code = sem?.get("code").safeString() ?: "",
                name = sem?.get("name").safeString(),
                realName = sem?.get("real_name").safeString(),
                sort = sem?.get("sort").safeInt() ?: 0
            ),
            department = LmsDepartment(
                id = dept?.get("id").safeInt() ?: 0,
                name = dept?.get("name").safeString() ?: ""
            ),
            instructors = instructors,
            courseAttributes = LmsCourseAttributes(
                published = attrs?.get("published").safeBoolean() ?: false,
                studentCount = attrs?.get("student_count").safeInt() ?: 0,
                teachingClassName = attrs?.get("teaching_class_name").safeString() ?: ""
            )
        )
    }

    private fun extractActivityBrief(obj: JsonObject): LmsActivity {
        return LmsActivity(
            id = obj.get("id").safeInt(),
            courseId = obj.get("course_id").safeInt(),
            type = LmsActivityType.fromString(obj.get("type").safeString() ?: ""),
            title = obj.get("title").safeString() ?: "",
            moduleId = obj.get("module_id")?.let { if (it.isJsonNull) null else it.safeInt() },
            startTime = obj.get("start_time").safeString(),
            endTime = obj.get("end_time").safeString(),
            submitByGroup = obj.get("submit_by_group").safeBoolean(),
            published = obj.get("published").safeBoolean(),
            createdAt = obj.get("created_at").safeString() ?: "",
            updatedAt = obj.get("updated_at").safeString() ?: ""
        )
    }

    private fun extractUpload(obj: JsonObject): LmsUpload {
        val uploadId = obj.get("id").safeInt()
        val refId = obj.get("reference_id").safeInt()
        return LmsUpload(
            id = uploadId,
            name = obj.get("name").safeString() ?: "",
            type = obj.get("type").safeString() ?: "",
            size = obj.get("size").safeInt(),
            referenceId = refId,
            status = obj.get("status").safeString() ?: "",
            createdAt = obj.get("created_at").safeString() ?: "",
            updatedAt = obj.get("updated_at").safeString() ?: "",
            downloadUrl = if (uploadId > 0) "$baseUrl/api/uploads/$uploadId/blob" else "",
            previewUrl = if (refId > 0) "$baseUrl/api/uploads/reference/document/$refId/url" else ""
        )
    }

    private fun extractActivityDetail(obj: JsonObject): LmsActivity {
        val typeStr = obj.get("type").safeString() ?: ""
        val type = LmsActivityType.fromString(typeStr)
        val dataObj = obj.get("data").safeObject()
        val lessonResource = obj.get("lesson_resource").safeObject()
        val lessonProperties = lessonResource?.get("properties").safeObject()

        val uploads = obj.get("uploads").safeArray().mapNotNull { elem ->
            try { extractUpload(elem.asJsonObject) } catch (_: Exception) { null }
        }

        val common = LmsActivity(
            id = obj.get("id").safeInt(),
            courseId = obj.get("course_id").safeInt(),
            type = type,
            title = obj.get("title").safeString() ?: "",
            moduleId = obj.get("module_id")?.let { if (it.isJsonNull) null else it.safeInt() },
            startTime = obj.get("start_time").safeString(),
            endTime = obj.get("end_time").safeString(),
            published = obj.get("published").safeBoolean(),
            createdAt = obj.get("created_at").safeString() ?: "",
            updatedAt = obj.get("updated_at").safeString() ?: "",
            uploads = uploads
        )

        return when (type) {
            LmsActivityType.HOMEWORK -> common.copy(
                submitByGroup = obj.get("submit_by_group").safeBoolean(),
                groupId = obj.get("group_id")?.let { if (it.isJsonNull) null else it.safeInt() },
                groupSetName = obj.get("group_set_name").safeString(),
                userSubmitCount = obj.get("user_submit_count").safeInt(),
                description = dataObj?.get("description").safeString(),
                averageScore = obj.get("average_score")?.let { if (it.isJsonNull) null else it.asDouble },
                highestScore = obj.get("highest_score")?.let { if (it.isJsonNull) null else it.asDouble },
                lowestScore = obj.get("lowest_score")?.let { if (it.isJsonNull) null else it.asDouble },
                hasScoreCount = obj.get("has_score_count")?.let { if (it.isJsonNull) null else it.safeInt() }
            )

            LmsActivityType.MATERIAL -> common.copy(
                description = dataObj?.get("description").safeString()
            )

            LmsActivityType.LESSON -> {
                // 提取 replay_code（多处备选）
                var replayCode: String? = obj.get("replay_code").safeString()?.takeIf { it.isNotEmpty() }
                if (replayCode == null) {
                    replayCode = lessonProperties?.get("replay_code").safeString()?.takeIf { it.isNotEmpty() }
                }
                if (replayCode == null) {
                    replayCode = dataObj?.get("external_live_detail").safeObject()
                        ?.get("replay_id").safeString()?.takeIf { it.isNotEmpty() }
                }

                // 获取回放视频
                val lessonActivityId = obj.get("id").safeInt()
                var replayVideos = emptyList<LmsReplayVideo>()
                var replayDownloadUrls = emptyList<String>()

                if (replayCode != null) {
                    try {
                        replayVideos = getReplayVideos(replayCode, lessonActivityId)
                        replayDownloadUrls = replayVideos.mapNotNull { it.downloadUrl.takeIf { url -> url.isNotEmpty() } }
                    } catch (e: Exception) {
                        Log.w(TAG, "extractActivityDetail: failed to get replay videos for $lessonActivityId", e)
                    }
                }

                common.copy(
                    replayCode = replayCode,
                    lessonStart = dataObj?.get("lesson_start").safeString(),
                    lessonEnd = dataObj?.get("lesson_end").safeString(),
                    replayVideos = replayVideos,
                    replayDownloadUrls = replayDownloadUrls,
                    replayVideoCount = replayVideos.size
                )
            }

            LmsActivityType.LECTURE_LIVE -> {
                val external = dataObj?.get("external_live_detail").safeObject()

                // 解析教室信息（room 是 JSON 对象）
                val roomObj = external?.get("room").safeObject()
                val roomName = roomObj?.get("room_name").safeString()
                val roomCode = roomObj?.get("room_code").safeString()

                // 解析教师名单
                val instructorNames = external?.get("instructor_names").safeArray()
                    ?.mapNotNull { it.safeString() } ?: emptyList()

                // 解析 HLS 直播流（多机位）
                val streams = external?.get("streams").safeArray()?.mapNotNull { elem ->
                    try {
                        val s = elem.asJsonObject
                        LmsLiveStream(
                            label = s.get("label").safeString() ?: "",
                            src = s.get("src").safeString()
                                ?: s.get("stream_url").safeString() ?: "",
                            mute = s.get("mute").safeBoolean() || s.get("muted").safeBoolean(),
                            type = s.get("type").safeString() ?: "application/x-mpegURL"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "extractActivityDetail: skip bad stream", e)
                        null
                    }
                }?.filter { it.src.isNotEmpty() } ?: emptyList()

                // 解析录播回放视频
                val replayVideosArr = external?.get("replay_videos").safeArray()
                val liveReplayVideos = replayVideosArr?.mapNotNull { elem ->
                    try {
                        val v = elem.asJsonObject
                        LmsReplayVideo(
                            id = v.get("id").safeInt(),
                            label = v.get("label").safeString()
                                ?: v.get("camera_type").safeString() ?: "",
                            mute = v.get("mute").safeBoolean(),
                            isBestAudio = v.get("is_best_audio").safeBoolean(),
                            playType = v.get("play_type").safeString() ?: "",
                            downloadUrl = v.get("download_url").safeString()
                                ?: v.get("url").safeString() ?: "",
                            fileKey = v.get("file_key").safeString() ?: "",
                            size = v.get("size").safeInt()
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "extractActivityDetail: skip bad live replay video", e)
                        null
                    }
                } ?: emptyList()

                // 如果有 replay_id 且 replay_videos 为空，尝试通过 RMS 获取回放
                val replayId = external?.get("replay_id")?.let {
                    if (it.isJsonNull) null
                    else it.safeString()?.takeIf { s -> s.isNotEmpty() }
                        ?: it.safeInt().takeIf { i -> i > 0 }?.toString()
                }
                val finalReplayVideos = if (liveReplayVideos.isEmpty() && replayId != null) {
                    try {
                        getReplayVideos(replayId, common.id)
                    } catch (e: Exception) {
                        Log.w(TAG, "extractActivityDetail: failed to get RMS replay videos for LECTURE_LIVE", e)
                        emptyList()
                    }
                } else liveReplayVideos

                common.copy(
                    replayCode = replayId,
                    liveRoomName = roomName,
                    liveRoomCode = roomCode,
                    liveStatus = external?.get("status").safeString(),
                    liveInstructorNames = instructorNames,
                    liveStreams = streams,
                    liveReplayVideos = finalReplayVideos,
                    externalLiveId = external?.get("id").safeInt(),
                    viewLive = external?.get("view_live").safeBoolean(),
                    viewRecord = external?.get("view_record").safeBoolean()
                )
            }

            else -> common
        }
    }

    private fun extractSubmissionList(data: JsonObject): LmsSubmissionListResponse {
        val items = data.get("list").safeArray().mapNotNull { elem ->
            try {
                val obj = elem.asJsonObject
                val uploads = obj.get("uploads").safeArray().mapNotNull { u ->
                    try { extractUpload(u.asJsonObject) } catch (_: Exception) { null }
                }
                val createdBy = obj.get("created_by").safeObject()
                val sc = obj.get("submission_correct").safeObject()
                val scUploads = sc?.get("uploads").safeArray()?.mapNotNull { u ->
                    try { extractUpload(u.asJsonObject) } catch (_: Exception) { null }
                } ?: emptyList()

                LmsSubmissionItem(
                    id = obj.get("id").safeInt(),
                    activityId = obj.get("activity_id").safeInt(),
                    studentId = obj.get("student_id").safeInt(),
                    groupId = obj.get("group_id").safeInt(),
                    canRetract = obj.get("can_retract").safeBoolean(),
                    comment = obj.get("comment").safeString() ?: "",
                    createdAt = obj.get("created_at").safeString(),
                    createdBy = LmsSubmissionCreator(
                        id = createdBy?.get("id").safeInt() ?: 0,
                        name = createdBy?.get("name").safeString() ?: "",
                        userNo = (createdBy?.get("user_no").safeString() ?: createdBy?.get("userNo").safeString()) ?: ""
                    ),
                    instructorComment = obj.get("instructor_comment").safeString() ?: "",
                    isLatestVersion = obj.get("is_latest_version").safeBoolean(),
                    isResubmitted = obj.get("is_resubmitted").safeBoolean(),
                    isRedo = obj.get("is_redo").safeBoolean(),
                    mode = obj.get("mode").safeString() ?: "",
                    status = obj.get("status").safeString() ?: "",
                    score = obj.get("score").safeString(),
                    scoreAt = obj.get("score_at").safeString(),
                    submittedAt = obj.get("submitted_at").safeString(),
                    submitByInstructor = obj.get("submit_by_instructor").safeBoolean(),
                    submissionCorrect = LmsSubmissionCorrect(
                        id = sc?.get("id").safeInt() ?: 0,
                        comment = sc?.get("comment").safeString() ?: "",
                        instructorScore = sc?.get("instructor_score").safeString(),
                        score = sc?.get("score").safeString(),
                        updatedAt = sc?.get("updated_at").safeString() ?: "",
                        uploads = scUploads
                    ),
                    updatedAt = obj.get("updated_at").safeString(),
                    content = obj.get("content").safeString() ?: "",
                    uploads = uploads
                )
            } catch (e: Exception) {
                Log.w(TAG, "extractSubmissionList: skip bad item", e)
                null
            }
        }

        val topUploads = data.get("uploads").safeArray().mapNotNull { u ->
            try { extractUpload(u.asJsonObject) } catch (_: Exception) { null }
        }

        return LmsSubmissionListResponse(list = items, uploads = topUploads)
    }

    // ── JavaScript 解析工具 ──────────────

    /**
     * 从 HTML 页面中提取 globalData 的 JS 对象块
     * 例如: user: { id: 123, name: "张三" }, dept: { ... }
     */
    private fun extractJsBlock(page: String, key: String, nextKey: String): String {
        val pattern = Regex(
            """${Regex.escape(key)}\s*:\s*\{(?<body>.*?)\}\s*,\s*${Regex.escape(nextKey)}\s*:""",
            RegexOption.DOT_MATCHES_ALL
        )
        return pattern.find(page)?.groups?.get("body")?.value ?: ""
    }

    /**
     * 从 JS 对象块中提取键值对
     */
    private fun extractJsKeyValue(block: String, key: String): String? {
        if (block.isEmpty()) return null
        val pattern = Regex(
            """${Regex.escape(key)}\s*:\s*(?<value>"(?:\\.|[^"])*"|true|false|null|None|-?\d+(?:\.\d+)?)"""
        )
        val match = pattern.find(block) ?: return null
        return parseJsScalar(match.groups["value"]!!.value)
    }

    private fun parseJsScalar(raw: String): String? {
        val value = raw.trim()
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        if (value == "null" || value == "None") return null
        return value  // true/false/numbers as string
    }
}
