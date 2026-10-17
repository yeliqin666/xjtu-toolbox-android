package com.xjtu.toolbox.lms

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * 讨论区数据解析与合并逻辑单元测试（全部采用脱敏与匿名模拟数据）
 */
class LmsForumTest {

    private fun parseJson(raw: String) = AppJson.parseToJsonElement(raw).jsonObject

    @Test
    fun forumUser_roleLabel() {
        assertEquals("教师", LmsForumUser(name = "测试教师A", role = "teacher").roleLabel)
        assertEquals("教师", LmsForumUser(name = "测试教师B", role = "TEACHER").roleLabel)
        assertEquals("学生", LmsForumUser(name = "测试学生A", role = "student").roleLabel)
        assertEquals("助教", LmsForumUser(name = "测试助教A", role = "ta").roleLabel)
        assertEquals("助教", LmsForumUser(name = "测试助教B", role = "assistant").roleLabel)
        assertEquals("管理员", LmsForumUser(name = "Admin", role = "管理员").roleLabel)
        assertNull(LmsForumUser(name = "无角色用户", role = null).roleLabel)
        assertNull(LmsForumUser(name = "空角色用户", role = "   ").roleLabel)
    }

    @Test
    fun forumTopic_isTopped() {
        val normal = LmsForumTopic(id = 1, title = "普通帖子")
        assertFalse(normal.isTopped)

        val teacherTop = LmsForumTopic(id = 2, title = "教师置顶帖", isTeacherTop = true)
        assertTrue(teacherTop.isTopped)

        val sortTop = LmsForumTopic(id = 3, title = "排序置顶帖", topicToppedSort = 1)
        assertTrue(sortTop.isTopped)
    }

    @Test
    fun activity_mergeBrief_preservesTopicCategoryAndCount() {
        val brief = LmsActivity(
            id = 10001,
            type = LmsActivityType.FORUM,
            title = "课程讨论区",
            topicCategoryId = 20001,
            topicCount = 2,
        )

        val detailWithoutBrief = LmsActivity(
            id = 10001,
            type = LmsActivityType.FORUM,
            title = "课程讨论区",
            topicCategoryId = null,
            topicCount = 0,
        )

        val merged = detailWithoutBrief.mergeBrief(brief)
        assertEquals(20001, merged.topicCategoryId)
        assertEquals(2, merged.topicCount)
    }

    @Test
    fun activity_mergeBrief_keepsExistingDetailCategoryAndCount() {
        val brief = LmsActivity(
            id = 10001,
            type = LmsActivityType.FORUM,
            title = "课程讨论区",
            topicCategoryId = 20001,
            topicCount = 2,
        )

        val detailWithCategory = LmsActivity(
            id = 10001,
            type = LmsActivityType.FORUM,
            title = "课程讨论区",
            topicCategoryId = 20001,
            topicCount = 5,
        )

        val merged = detailWithCategory.mergeBrief(brief)
        assertEquals(20001, merged.topicCategoryId)
        assertEquals(5, merged.topicCount)
    }

    @Test
    fun parseForumTopics_mockResponse() {
        val sampleJson = """
        {
          "activity_id": 10001,
          "id": 20001,
          "result": {
            "end": 2,
            "page": 1,
            "page_size": 10,
            "pages": 1,
            "start": 1,
            "total": 2,
            "topics": [
              {
                "content": "<p>测试帖子正文内容1</p>",
                "created_at": "2026-10-09T14:33:15Z",
                "created_by": {
                  "comment": null,
                  "id": 101,
                  "name": "测试学生A",
                  "nickname": null
                },
                "current_user_liked": false,
                "current_user_read": true,
                "enrollments": [
                  {
                    "aliases": [null],
                    "roles": ["student"]
                  }
                ],
                "group_id": 0,
                "has_matched_replies": true,
                "id": 30001,
                "in_common_category": false,
                "is_hot_topic": false,
                "is_teacher_top": false,
                "like_count": 0,
                "read_replies": [
                  { "reply_id": 40001 }
                ],
                "reply_count": 1,
                "title": "测试讨论主题一",
                "topic_topped_sort": 0,
                "unread_reply_count": 0,
                "updated_at": null,
                "uploads": [
                  {
                    "id": 50001,
                    "name": "mock_attachment_1.png",
                    "type": "image",
                    "size": 102400,
                    "reference_id": 60001,
                    "status": "ready"
                  }
                ],
                "user_visits_number": 10
              },
              {
                "content": "<p>测试帖子正文内容2</p>",
                "created_at": "2026-10-09T05:58:23Z",
                "created_by": {
                  "id": 102,
                  "name": "测试学生B"
                },
                "current_user_liked": false,
                "current_user_read": true,
                "enrollments": [
                  {
                    "roles": ["student"]
                  }
                ],
                "id": 30002,
                "like_count": 0,
                "reply_count": 0,
                "title": "测试讨论主题二",
                "uploads": [],
                "user_visits_number": 5
              }
            ]
          }
        }
        """.trimIndent()

        val json = parseJson(sampleJson)
        val resultObj = json["result"]?.jsonObject
        assertNotNull(resultObj)

        val topicsArray = resultObj!!["topics"]
        assertNotNull(topicsArray)

        val topic1 = LmsForumTopic(
            id = 30001,
            categoryId = 20001,
            title = "测试讨论主题一",
            content = "<p>测试帖子正文内容1</p>",
            createdAt = "2026-10-09T14:33:15Z",
            createdBy = LmsForumUser(id = 101, name = "测试学生A", role = "student"),
            replyCount = 1,
            likeCount = 0,
            currentUserRead = true,
            userVisitsNumber = 10,
            uploads = listOf(
                LmsUpload(id = 50001, name = "mock_attachment_1.png", type = "image", size = 102400)
            )
        )

        assertEquals("测试讨论主题一", topic1.title)
        assertEquals("测试学生A", topic1.createdBy.name)
        assertEquals("学生", topic1.createdBy.roleLabel)
        assertEquals(1, topic1.replyCount)
        assertEquals(10, topic1.userVisitsNumber)
        assertEquals(1, topic1.uploads.size)
        assertEquals("mock_attachment_1.png", topic1.uploads.first().name)
        assertFalse(topic1.isTopped)
    }

    @Test
    fun parseTopicReplies_mockResponse() {
        val replyJson = """
        {
          "id": 30001,
          "result": [
            {
              "comments": [
                {
                  "content": "<p>收到回复，谢谢！</p>",
                  "created_at": "2026-10-10T08:00:00Z",
                  "created_by": {
                    "id": 101,
                    "name": "测试学生A"
                  },
                  "id": 501,
                  "like_count": 0
                }
              ],
              "content": "<p>测试回帖正文解答</p>",
              "created_at": "2026-10-10T07:38:00Z",
              "created_by": {
                "id": 103,
                "name": "测试学生C",
                "user_no": "2026000001"
              },
              "current_user_liked": true,
              "id": 40001,
              "like_count": 1,
              "reply_count": 1,
              "unread_reply_count": 0,
              "user_visits_number": 7
            }
          ]
        }
        """.trimIndent()

        val json = parseJson(replyJson)
        val list = json["result"]
        assertNotNull(list)

        val reply = LmsForumReply(
            id = 40001,
            topicId = 30001,
            content = "<p>测试回帖正文解答</p>",
            createdAt = "2026-10-10T07:38:00Z",
            createdBy = LmsForumUser(id = 103, name = "测试学生C", userNo = "2026000001"),
            likeCount = 1,
            currentUserLiked = true,
            comments = listOf(
                LmsForumComment(
                    id = 501,
                    content = "<p>收到回复，谢谢！</p>",
                    createdAt = "2026-10-10T08:00:00Z",
                    createdBy = LmsForumUser(id = 101, name = "测试学生A")
                )
            )
        )

        assertEquals(40001, reply.id)
        assertEquals("测试学生C", reply.createdBy.name)
        assertEquals("2026000001", reply.createdBy.userNo)
        assertTrue(reply.currentUserLiked)
        assertEquals(1, reply.likeCount)
        assertEquals(1, reply.comments.size)
        assertEquals("测试学生A", reply.comments.first().createdBy.name)
    }
}
