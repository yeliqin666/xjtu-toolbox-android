package com.xjtu.toolbox.lms

import com.xjtu.toolbox.util.AppJson
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.Test

class LmsBulletinSerializationTest {

    @Test
    fun bulletinRoundTripKeepsAllFields() {
        val bulletin = LmsBulletin(
            id = 7511,
            courseId = 33671,
            title = "第一次上机验收",
            content = "<p>大家注意，本周周六我们会组织进行第一次上机验收...</p>",
            createdAt = "2026-10-12T04:29:46Z",
            updatedAt = "2026-10-12T05:00:00Z",
            createdBy = LmsBulletinAuthor(id = 109258, name = "王怡雯"),
            updatedBy = LmsBulletinAuthor(id = 22682, name = "赵曦"),
            targets = listOf("a0"),
            targetsName = listOf("所有人"),
            uploads = listOf(
                LmsUpload(
                    id = 101,
                    name = "验收说明.pdf",
                    type = "application/pdf",
                    size = 102400,
                    courseId = 33671
                )
            )
        )

        val json = AppJson.encodeToString(listOf(bulletin))
        assertNotNull(json)

        val back = AppJson.decodeFromString<List<LmsBulletin>>(json)
        assertEquals(1, back.size)
        assertEquals(bulletin, back[0])
        assertEquals("第一次上机验收", back[0].title)
        assertEquals("王怡雯", back[0].createdBy.name)
        assertEquals(1, back[0].uploads.size)
        assertEquals("验收说明.pdf", back[0].uploads[0].name)
    }
}
