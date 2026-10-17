package com.xjtu.toolbox.lms

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class LmsBulletinParseTest {

    @Test
    fun parseBulletin_fromRealApiResponse() {
        // 来自课程 33671 真实接口返回的公告 JSON
        val rawJson = """
        {
            "content": "<p>大家注意，本周周六我们会组织进行第一次上机验收，请大家按时参加。</p>",
            "created_at": "2026-10-12T04:29:46Z",
            "created_by": {
                "id": 109258,
                "name": "王怡雯"
            },
            "deleted": 0,
            "end_time": null,
            "id": 7511,
            "imported_from": null,
            "imported_track_id": null,
            "send_email": false,
            "send_mail_count": 0,
            "send_type": "default",
            "start_time": null,
            "targets": ["a0"],
            "targets_name": ["所有人"],
            "title": "第一次上机验收",
            "updated_at": null,
            "updated_by": {
                "id": 0,
                "name": null
            },
            "uploads": []
        }
        """.trimIndent()

        val jsonObject = AppJson.parseToJsonElement(rawJson).jsonObject
        val bulletin = extractBulletin(jsonObject, courseId = 33671)

        assertEquals(7511, bulletin.id)
        assertEquals(33671, bulletin.courseId)
        assertEquals("第一次上机验收", bulletin.title)
        assertEquals("王怡雯", bulletin.createdBy.name)
        assertEquals(109258, bulletin.createdBy.id)
        assertEquals("2026-10-12T04:29:46Z", bulletin.createdAt)
        assertEquals(listOf("所有人"), bulletin.targetsName)
        assertTrue(bulletin.uploads.isEmpty())
        assertTrue(bulletin.content.contains("第一次上机验收"))
    }

    @Test
    fun parseBulletin_withUploadsAndImages() {
        val rawJson = """
        {
            "content": "<p><img alt=\"Image\" class=\"lms-sim-image\" src=\"https://lms.xjtu.edu.cn:443/api/uploads/6156269/in-rich-content?created_at=2026-09-10T11:23:27Z\" width=\"269\"><br></p>",
            "created_at": "2026-09-10T11:23:37Z",
            "created_by": {
                "id": 22682,
                "name": "赵曦"
            },
            "id": 4886,
            "title": "请同学们加入课程qq群",
            "updated_at": "2026-09-10T11:23:59Z",
            "targets_name": ["所有人"],
            "uploads": [
                {
                    "id": 12345,
                    "name": "课程大纲.pdf",
                    "type": "application/pdf",
                    "size": 524288,
                    "reference_id": 999
                }
            ]
        }
        """.trimIndent()

        val jsonObject = AppJson.parseToJsonElement(rawJson).jsonObject
        val bulletin = extractBulletin(jsonObject, courseId = 33671)

        assertEquals(4886, bulletin.id)
        assertEquals("请同学们加入课程qq群", bulletin.title)
        assertEquals("赵曦", bulletin.createdBy.name)
        assertEquals(1, bulletin.uploads.size)
        assertEquals("课程大纲.pdf", bulletin.uploads[0].name)
        assertEquals(33671, bulletin.uploads[0].courseId)
        assertEquals("https://lms.xjtu.edu.cn/api/uploads/12345/blob", bulletin.uploads[0].downloadUrl)

        // 配图链接的提取在 feature:lms（LmsBulletinImageUrlTest）
        assertTrue(bulletin.content.contains("in-rich-content"))

    }
}
