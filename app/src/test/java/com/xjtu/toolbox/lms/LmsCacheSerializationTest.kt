package com.xjtu.toolbox.lms

import com.xjtu.toolbox.util.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 课程列表、活动列表落盘后要能原样读回来（见 LmsPageCache）。 */
class LmsCacheSerializationTest {

    @Test
    fun 课程列表往返不丢字段() {
        val courses = listOf(
            LmsCourseSummary(
                id = 7, name = "数据结构", courseCode = "COMP1001", credit = "3.0", startDate = "2026-09-01",
                academicYear = LmsAcademicYear(1, "2026", "2026-2027", 1),
                semester = LmsSemester(2, "1", realName = "秋"),
                department = LmsDepartment(3, "计算机学院", "CS"),
                instructors = listOf(LmsInstructor(4, "张老师")),
                courseAttributes = LmsCourseAttributes(true, 60, "计算机01"),
            )
        )
        val back = AppJson.decodeFromString<List<LmsCourseSummary>>(AppJson.encodeToString(courses))
        assertEquals(courses, back)
        assertEquals("2026-2027 秋", back[0].semesterLabel)
    }

    @Test
    fun 活动列表往返_详情专有的提交记录不落盘() {
        val activity = LmsActivity(
            id = 11, courseId = 7, type = LmsActivityType.LESSON, title = "第一讲",
            deadline = "2026-10-01T15:59:00Z",
            uploads = listOf(LmsUpload(id = 5, name = "slides.pdf", size = 2048)),
            replayVideos = listOf(LmsReplayVideo(id = 1, label = "教师", playUrl = "https://x/1.m3u8")),
            liveStreams = listOf(LmsLiveStream(label = "instructor", src = "https://x/live.m3u8")),
            submissionList = LmsSubmissionListResponse(list = listOf(LmsSubmissionItem(id = 1, score = 95))),
        )
        val back = AppJson.decodeFromString<List<LmsActivity>>(AppJson.encodeToString(listOf(activity)))
        assertEquals(activity.copy(submissionList = null), back[0])
        assertNull(back[0].submissionList)
    }
}
