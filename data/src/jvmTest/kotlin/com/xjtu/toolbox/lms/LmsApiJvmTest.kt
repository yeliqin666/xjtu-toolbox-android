package com.xjtu.toolbox.lms

import com.xjtu.toolbox.auth.withLmsLogin
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.CLOSED_UPLOAD_BODY
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.CLOSED_UPLOAD_ID
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.COURSE_CODE
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.COURSE_ID
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.COURSE_NAME
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.HOMEWORK_DEADLINE
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.HOMEWORK_ID
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.HOMEWORK_TITLE
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.INSTRUCTOR_NAME
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.MATERIAL_ID
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.MATERIAL_TITLE
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.PAGE_ID
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.SUBMISSION_ID
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.UPLOAD_BYTES
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.UPLOAD_ID
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.UPLOAD_NAME
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.UPLOAD_TYPE
import com.xjtu.toolbox.lms.LmsFakeUpstream.Companion.USER_ID
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 思源学堂取数（`LmsApi`）的**字段级口径**：对着 `:testkit` 的 [LmsFakeUpstream] 逐行读。
 *
 * ## 为什么在 `:data` 而不是 `:app`
 *
 * 这一轮把 lms 的取数（[LmsApi]）与站点（[com.xjtu.toolbox.auth.LmsSession] +
 * [com.xjtu.toolbox.lms.LmsLogin]）从 `:app` 搬进了 `:data`（桌面端第 18 条真数据路由）。
 * 搬之前先用夹具把**搬之前**的口径钉住 —— 这些断言全部来自**夹具原文**与**搬迁前的代码语义**：
 * `user/index` 里 globalData 的 JS 形状、课程/活动的列表字段、详情接口**没有 deadline 键**
 *（只能靠列表那条并进来）、作业提交态（`user_submit_count` 只在详情）、正文的两种载体
 *（作业/课件 `data.description`、页面型 `data.content`）、已结束作业的 /blob 403 → Forbidden
 *（LmsSession 把它认成业务拒绝，不重登）。
 *
 * 模型与 `mergeBrief`/`lmsActivityBody` 一并随搬（`LmsApi` 同包同类型名），所以这套断言
 * 在搬动前后是同一份 —— 这就是「先钉住再搬」。
 */
class LmsApiJvmTest {

    @Test
    fun `登录：直接 CAS 回跳落点在 lms 上，getUserInfo 从 user-index 的 globalData 解析学号`() {
        withLmsLogin { site, fake ->
            assertTrue(site.hasLogin, "真登录应当成功")
            // 真打过一次统一认证的凭据表单（不是靠别处缓存直通）
            assertTrue(fake.library.credentialPosts.get() > 0, "应当真提交过一次凭据")
            // 登录那一刻**不会**额外摸 /user/index：CAS 回跳落点就在 lms 根路径（带 ticket 的那次
            // 200 种下会话 cookie）⇒ `LmsLogin.postLogin` 判到「最终 URL 已在 lms 上」就早返回。
            // /user/index 那份 200 是下面 `getUserInfo()` 拉的 —— 计数放到它后面断。

            val api = LmsApi(site)
            val info = runBlocking { api.getUserInfo() }
            assertEquals(USER_ID, info.id.toString())
            assertEquals("张三", info.name)
            assertEquals("2021000001", info.userNo)
            assertEquals("软件学院", info.dept.name)
            assertTrue(fake.lms.indexCalls.get() > 0, "getUserInfo 应当拉过 /user/index")
        }
    }

    @Test
    fun `课程与活动：列表字段口径，详情把列表的截止时间并进来（mergeBrief 钉死）`() {
        withLmsLogin { site, _ ->
            val api = LmsApi(site)
            val courses = runBlocking { api.getMyCourses() }
            assertEquals(1, courses.size)
            val course = courses.single()
            assertEquals(COURSE_ID, course.id)
            assertEquals(COURSE_NAME, course.name)
            assertEquals(COURSE_CODE, course.courseCode)
            assertEquals("3.0", course.credit)
            assertEquals(INSTRUCTOR_NAME, course.instructorNames)
            // start_date = 2026-09-01 ⇒ 秋学期标签
            assertEquals("2026-2027 秋", course.semesterLabel)

            val activities = runBlocking { api.getCourseActivities(COURSE_ID) }
            assertEquals(3, activities.size)
            val homeworkBrief = activities.first { it.id == HOMEWORK_ID }
            // 列表接口带 deadline
            assertEquals(HOMEWORK_DEADLINE, homeworkBrief.deadline)

            // 详情接口故意**没有** deadline 键 ⇒ 必须从列表那条并进来（详情 79 个键里本来就没有）
            val detail = runBlocking { api.getActivityDetail(HOMEWORK_ID, homeworkBrief) }
            assertEquals(HOMEWORK_DEADLINE, detail.deadline)
            assertEquals(HOMEWORK_TITLE, detail.title)
            assertTrue(detail.description?.contains("完成习题并提交报告") == true, "作业正文在 data.description")
            assertEquals(1, detail.userSubmitCount)

            // 附件：downloadUrl 按 id 拼 /blob
            val upload = detail.uploads.single()
            assertEquals(UPLOAD_ID, upload.id)
            assertEquals(UPLOAD_NAME, upload.name)
            assertEquals(UPLOAD_TYPE, upload.type)
            assertEquals("https://lms.xjtu.edu.cn/api/uploads/$UPLOAD_ID/blob", upload.downloadUrl)

            // 提交列表：状态与分数字段
            assertNotNull(detail.submissionList)
            assertEquals(1, detail.submissionList!!.list.size)
            val sub = detail.submissionList!!.list.single()
            assertEquals(SUBMISSION_ID, sub.id)
            assertEquals("已批改", sub.statusLabel)
            assertEquals("95", sub.scoreDisplay)
            assertEquals("张三", sub.createdBy.name)
        }
    }

    @Test
    fun `课件与页面的正文载体不同：课件走 description、页面型走 content`() {
        withLmsLogin { site, _ ->
            val api = LmsApi(site)
            val material = runBlocking { api.getActivityDetail(MATERIAL_ID, null) }
            assertEquals(MATERIAL_TITLE, material.title)
            assertTrue(material.description?.contains("第一章：绪论") == true, "课件正文在 data.description")
            assertEquals(1, material.uploads.size)

            val page = runBlocking { api.getActivityDetail(PAGE_ID, null) }
            assertTrue(page.description?.contains("期中 40%") == true, "页面型正文在 data.content")
            assertEquals(LmsActivityType.PAGE, page.type)
        }
    }

    @Test
    fun `下载：open 的附件能取到字节，已结束作业的 blob 403 判成 Forbidden（业务拒绝、不重登）`() {
        withLmsLogin { site, _ ->
            val api = LmsApi(site)
            val bytes = runBlocking { api.downloadBytes("https://lms.xjtu.edu.cn/api/uploads/$UPLOAD_ID/blob") }
            assertTrue(bytes.contentEquals(UPLOAD_BYTES), "附件字节应当原样回来")

            val out = ByteArrayOutputStream()
            val result = runBlocking {
                api.downloadUpload(
                    LmsUpload(id = CLOSED_UPLOAD_ID, name = "已结束.pdf", type = UPLOAD_TYPE),
                    out,
                )
            }
            assertEquals(LmsDownloadResult.Forbidden, result)
            assertEquals(0, out.size())
            // 403 的响应体是「没有权限」—— LmsSession 不把它当掉登录（那会空转重登）
            assertTrue(CLOSED_UPLOAD_BODY == "没有权限")
        }
    }
}