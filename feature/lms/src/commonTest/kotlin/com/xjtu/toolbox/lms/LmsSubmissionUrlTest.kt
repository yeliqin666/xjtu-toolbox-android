package com.xjtu.toolbox.lms

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 官方 h5 作业提交页的地址拼装。
 *
 * 判据是 h5 自己的前端路由表（`app.js` 里 name = `HomeworkSubmissionCreate`）：
 * `/course/:courseId/homework/:homeworkId/submission/create/:committerId`。
 * committerId 个人作业 = 登录者 id、分组作业 = 小组 id —— 猜错那一格会直接落到 404，
 * 所以两条分支都必须钉住。
 */
class LmsSubmissionUrlTest {

    private fun homework(
        courseId: Int = 44114,
        id: Int = 1711640,
        submitByGroup: Boolean = false,
        groupId: Int? = null,
    ) = LmsActivity(
        id = id,
        courseId = courseId,
        type = LmsActivityType.HOMEWORK,
        title = "第三次作业",
        submitByGroup = submitByGroup,
        groupId = groupId,
    )

    @Test
    fun `个人作业用登录者 id 拼提交页`() {
        assertEquals(
            "https://lms-h5.xjtu.edu.cn/course/44114/homework/1711640/submission/create/298052",
            lmsHomeworkSubmissionUrl(homework(), studentId = 298052),
        )
    }

    @Test
    fun `分组作业用小组 id，即使也有登录者 id`() {
        assertEquals(
            "https://lms-h5.xjtu.edu.cn/course/44114/homework/1711640/submission/create/77",
            lmsHomeworkSubmissionUrl(homework(submitByGroup = true, groupId = 77), studentId = 298052),
        )
    }

    /** 分组作业但上游没给 group_id：拿登录者 id 顶，官方那一页至少还能自己判出个结果。 */
    @Test
    fun `分组作业缺 group_id 时退回登录者 id`() {
        assertEquals(
            "https://lms-h5.xjtu.edu.cn/course/44114/homework/1711640/submission/create/298052",
            lmsHomeworkSubmissionUrl(homework(submitByGroup = true), studentId = 298052),
        )
    }

    /** 拿不到号就不许猜：退到作业详情页，官方那一页自己会给提交入口。 */
    @Test
    fun `拿不到 id 时退到作业详情页`() {
        assertEquals(
            "https://lms-h5.xjtu.edu.cn/course/44114/homework/1711640",
            lmsHomeworkSubmissionUrl(homework(), studentId = null),
        )
        // 0 等同于「不知道」，不是「第 0 号」
        assertEquals(
            "https://lms-h5.xjtu.edu.cn/course/44114/homework/1711640",
            lmsHomeworkSubmissionUrl(homework(), studentId = 0),
        )
        assertEquals(
            "https://lms-h5.xjtu.edu.cn/course/44114/homework/1711640",
            lmsHomeworkSubmissionUrl(homework(submitByGroup = true, groupId = 0), studentId = null),
        )
    }
}
