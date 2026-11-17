package com.xjtu.toolbox.lms

/**
 * 思源学堂的移动端站点（h5）。
 *
 * 作业提交表单（选附件、填正文、点「提交」）只有这里有，本 App 不自己实现那一套：
 * 屏上只负责把官方页面打开，提交这个动作本身仍在学堂网页里完成。
 */
internal const val LMS_H5_BASE_URL = "https://lms-h5.xjtu.edu.cn"

/**
 * 「去提交作业」按钮要打开的官方页面。
 *
 * 路由取自 h5 自己的前端路由表（`app.js` 里 name = `HomeworkSubmissionCreate`）：
 * `/course/:courseId/homework/:homeworkId/submission/create/:committerId`。
 *
 * [committerId] 是**提交主体**而不是简单的「我」：
 * - 分组作业 → 小组 id（与 `/api/activities/{id}/groups/{gid}/submission_list` 里的 gid 同一个数）；
 * - 个人作业 → 学生 id（与 `/api/activities/{id}/students/{uid}/submission_list` 里的 uid 同一个数）。
 *
 * 拿不到这两样就退到作业详情页 `/course/:courseId/homework/:homeworkId`：那一页官方自己会给
 * 「提交」入口，多一次点击，但不会因为拼了一个猜出来的号而落到 404 上。
 */
internal fun lmsHomeworkSubmissionUrl(activity: LmsActivity, studentId: Int?): String {
    val detail = "$LMS_H5_BASE_URL/course/${activity.courseId}/homework/${activity.id}"
    val committerId = if (activity.submitByGroup) {
        activity.groupId?.takeIf { it > 0 } ?: studentId?.takeIf { it > 0 }
    } else {
        studentId?.takeIf { it > 0 }
    }
    return if (committerId != null) "$detail/submission/create/$committerId" else detail
}
