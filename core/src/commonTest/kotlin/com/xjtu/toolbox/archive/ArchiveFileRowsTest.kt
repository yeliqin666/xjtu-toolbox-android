package com.xjtu.toolbox.archive

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** `files` 层行数组排序的验收（§2.3）。 */
class ArchiveFileRowsTest {

    private fun rowsOf(json: String): JsonArray = AppJson.parseToJsonElement(json) as JsonArray

    private fun JsonArray.id(i: Int): String = (this[i] as JsonObject)["id"]?.let { (it as JsonPrimitive).content } ?: ""

    private fun JsonArray.ssno(i: Int): String = (this[i] as JsonObject)["ssno"]?.let { (it as JsonPrimitive).content } ?: ""

    @Test
    fun `§2_3 custom_courses 按六键升序`() {
        val rows = rowsOf(
            """[
              {"id":3,"accountId":"B","termCode":"2026-2027-1","dayOfWeek":1,"startSection":3,"startMinuteOfDay":-1,"courseName":"c"},
              {"id":1,"accountId":"A","termCode":"2026-2027-1","dayOfWeek":1,"startSection":1,"startMinuteOfDay":-1,"courseName":"a"},
              {"id":2,"accountId":"A","termCode":"2026-2027-1","dayOfWeek":1,"startSection":1,"startMinuteOfDay":480,"courseName":"b"},
              {"id":4,"accountId":"A","termCode":"2025-2026-2","dayOfWeek":7,"startSection":9,"startMinuteOfDay":-1,"courseName":"d"}
            ]""",
        )
        val sorted = ArchiveFileRows.sortCustomCourses(rows)
        // 排序键：accountId, termCode, dayOfWeek, startSection, startMinuteOfDay, id（全升序）
        assertEquals(listOf("4", "1", "2", "3"), (0 until sorted.size).map { sorted.id(it) })
    }

    @Test
    fun `§2_3 custom_courses 列名逐字不变`() {
        val rows = rowsOf(
            """[
              {"id":1,"accountId":"A","courseName":"a","teacher":"t","location":"l","weekBits":"01",
               "dayOfWeek":1,"startSection":1,"endSection":2,"startMinuteOfDay":-1,"endMinuteOfDay":-1,
               "termCode":"2026-2027-1","note":"","createdAt":1760000000000}
            ]""",
        )
        val sorted = ArchiveFileRows.sortCustomCourses(rows)
        val keys = (sorted[0] as JsonObject).keys
        assertEquals(
            setOf(
                "id", "accountId", "courseName", "teacher", "location", "weekBits", "dayOfWeek",
                "startSection", "endSection", "startMinuteOfDay", "endMinuteOfDay", "termCode",
                "note", "createdAt",
            ),
            keys,
        )
        assertEquals("a", (sorted[0] as JsonObject)["courseName"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `§2_3 download_tasks 按 createTime desc 再 id 升序`() {
        val rows = rowsOf(
            """[
              {"id":1,"createTime":100},{"id":2,"createTime":300},
              {"id":3,"createTime":300},{"id":4,"createTime":200}
            ]""",
        )
        val sorted = ArchiveFileRows.sortDownloadTasks(rows)
        assertEquals(listOf("2", "3", "4", "1"), (0 until sorted.size).map { sorted.id(it) })
    }

    @Test
    fun `§2_3 jiaocai1_shelf 按 addedAt desc 再 ssno 升序`() {
        val rows = rowsOf(
            """[
              {"ssno":"b","addedAt":100},{"ssno":"a","addedAt":300},
              {"ssno":"c","addedAt":300},{"ssno":"d","addedAt":200}
            ]""",
        )
        val sorted = ArchiveFileRows.sortJiaocaiShelf(rows)
        assertEquals(listOf("a", "c", "d", "b"), (0 until sorted.size).map { sorted.ssno(it) })
    }

    @Test
    fun `§2_3 agent_sessions index 按 updatedAt 降序`() {
        val rows = rowsOf(
            """[
              {"id":"s1","updatedAt":100},{"id":"s2","updatedAt":300},{"id":"s3","updatedAt":200}
            ]""",
        )
        val sorted = ArchiveFileRows.sortAgentSessionsIndex(rows)
        assertEquals(listOf("s2", "s3", "s1"), (0 until sorted.size).map { sorted.id(it) })
    }
}
