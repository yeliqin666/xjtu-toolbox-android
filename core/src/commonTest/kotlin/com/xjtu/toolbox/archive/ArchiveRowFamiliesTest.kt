package com.xjtu.toolbox.archive

import com.xjtu.toolbox.archive.ArchiveRowFamilies.Family
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 三族行数组这一层（[ArchiveRowFamilies]，§2.3 / §6.3）的两件纯事：搬运（只排序）与合并（本端胜）。
 *
 * 钉住的口径：
 *  - **导出形状与 Android 同形**：列名逐字（下面每个夹具都按 Room 实体的列名与插入序自己写一遍）、
 *    值一个字不改、`id` 保持原值、排序只调 [ArchiveFileRows] 那三条口径；
 *  - **导入合并 = 本端已有的主键整行保留、只追加新主键**（§6.4 的「本端胜」在行这一级）。
 *
 * 夹具值全是编造的（没有真实站点、没有网络、没有模型）。
 */
class ArchiveRowFamiliesTest {

    // ── 搬运：端内那段 JSON → 档案行数组的字节（只排序） ────────────────────────

    @Test
    fun `导出 custom_courses 列名逐字原样搬运 只按六键升序重排`() {
        // 七行**故意乱序**给出，期望顺序逐条对着 §2.3 的 (accountId, termCode, dayOfWeek,
        // startSection, startMinuteOfDay, id) 升序：
        //  1) 别的账号在前（accountId 是第一个键，压过 termCode / dayOfWeek）；
        //  2) 同学号里更早的学期在前（压过 dayOfWeek：2025-2026-2 那条 dayOfWeek=7 仍排在 dayOfWeek=1 前）；
        //  3) 其余键全同 ⇒ 按 id 升序（id=7 在 id=9 前，且两个 id 都不被改写）；
        //  4) 再同 ⇒ 按 startMinuteOfDay（-1 在 0 前）；
        //  5) dayOfWeek 压过 startSection（day=1 / start=3 在 day=2 / start=1 前）；
        //  6) 最后 startSection（同一天里 start=1 在 start=4 前，尽管后者的 minute 更小）。
        val otherAccount = courseRow(1, "1000000000", "甲", day = 1, start = 3, end = 4, minute = -1, term = "2026-2027-1")
        val earlyTerm = courseRow(2, SUFFIX_ID, "乙", day = 7, start = 11, end = 12, minute = -1, term = "2025-2026-2")
        val local = courseRow(7, SUFFIX_ID, "丙", day = 1, start = 3, end = 4, minute = -1, term = "2026-2027-1")
        val tail = courseRow(9, SUFFIX_ID, "丁", day = 1, start = 3, end = 4, minute = -1, term = "2026-2027-1")
        val minute = courseRow(2, SUFFIX_ID, "戊", day = 1, start = 3, end = 4, minute = 0, term = "2026-2027-1")
        val laterDay = courseRow(3, SUFFIX_ID, "己", day = 2, start = 1, end = 2, minute = -1, term = "2026-2027-1")
        val laterDayLate = courseRow(4, SUFFIX_ID, "庚", day = 2, start = 4, end = 5, minute = -5, term = "2026-2027-1")

        val raw = arrayText(laterDayLate, local, laterDay, otherAccount, minute, earlyTerm, tail)
        val expected = arrayText(otherAccount, earlyTerm, local, tail, minute, laterDay, laterDayLate)

        assertEquals(expected, ArchiveRowFamilies.rowsForArchive(Family.CUSTOM_COURSES, raw))

        // 「原样搬运」= 行内的键名与值逐字：拿出去那一份解回来与端内那一份逐对象相同
        val out = AppJson.parseToJsonElement(ArchiveRowFamilies.rowsForArchive(Family.CUSTOM_COURSES, raw)!!) as JsonArray
        assertEquals(7, out.size)
        assertEquals(listOf(columnsOfCUSTOM), out.map { (it as JsonObject).keys.toList() }.distinct())
        assertEquals(setOf("甲", "乙", "丙", "丁", "戊", "己", "庚"),
            out.map { (it as JsonObject).str("courseName") }.toSet())
        assertEquals(setOf(1L, 2L, 3L, 4L, 7L, 9L), out.map { (it as JsonObject).long("id") }.toSet())
    }

    @Test
    fun `导出另两族的排序口径 下载队列按 createTime 降序 教材书架按 addedAt 降序`() {
        val older = downloadRow(9, createTime = 100)
        val first = downloadRow(2, createTime = 300)
        val second = downloadRow(5, createTime = 300)
        assertEquals(
            arrayText(first, second, older),
            ArchiveRowFamilies.rowsForArchive(Family.DOWNLOAD_TASKS, arrayText(older, second, first)),
            "createTime 降序在前、同 createTime 按 id 升序",
        )

        val s9 = shelfRow("S-9", addedAt = 500)
        val s1 = shelfRow("S-1", addedAt = 500)
        val s5 = shelfRow("S-5", addedAt = 100)
        assertEquals(
            arrayText(s1, s9, s5),
            ArchiveRowFamilies.rowsForArchive(Family.JIAOCAI_SHELF, arrayText(s9, s5, s1)),
            "addedAt 降序在前、同 addedAt 按 ssno 升序",
        )
    }

    @Test
    fun `导出 端上没有这一族 空表 解不开 都不写进档案`() {
        assertNull(ArchiveRowFamilies.rowsForArchive(Family.CUSTOM_COURSES, null), "端上没有这个键")
        assertNull(ArchiveRowFamilies.rowsForArchive(Family.CUSTOM_COURSES, ""), "空串")
        assertNull(ArchiveRowFamilies.rowsForArchive(Family.CUSTOM_COURSES, "[]"), "端内那份是空表")
        assertNull(ArchiveRowFamilies.rowsForArchive(Family.CUSTOM_COURSES, "{\"a\":1}"), "不是数组")
        assertNull(ArchiveRowFamilies.rowsForArchive(Family.CUSTOM_COURSES, "[{\"id\":1"), "截断")

        // 端内那一行只写了几个键 ⇒ 也原样搬（不补列、不改字段集合）
        assertEquals(
            "[{\"id\":3,\"courseName\":\"只有两个键\"}]",
            ArchiveRowFamilies.rowsForArchive(Family.CUSTOM_COURSES, "[{\"id\":3,\"courseName\":\"只有两个键\"}]"),
        )
    }

    // ── 合并：档案行数组 → 端内那段 JSON（本端胜在行这一级） ────────────────────

    @Test
    fun `合并时本端已有的主键整行保留 只追加端内没有的主键且 id 保持原值`() {
        val localA = courseRow(7, SUFFIX_ID, "本机甲", day = 1, start = 1, end = 2, minute = -1, term = TERM)
        val localB = courseRow(9, SUFFIX_ID, "本机乙", day = 2, start = 3, end = 4, minute = -1, term = TERM)
        val localRaw = arrayText(localA, localB)
        val archive = arrayText(
            courseRow(7, SUFFIX_ID, "档案里的甲", day = 3, start = 5, end = 6, minute = -1, term = TERM),
            courseRow(11, SUFFIX_ID, "档案里的新行", day = 4, start = 7, end = 8, minute = -1, term = TERM),
            courseRow(9, SUFFIX_ID, "档案里的乙", day = 5, start = 9, end = 10, minute = -1, term = TERM),
        )

        val outcome = ArchiveRowFamilies.merge(Family.CUSTOM_COURSES, localRaw, archive)

        assertEquals(1, outcome.appended, "只有 id=11 是端内没有的主键")
        assertEquals(2, outcome.kept)
        assertEquals(0, outcome.skipped)
        assertEquals(
            arrayText(localA, localB, courseRow(11, SUFFIX_ID, "档案里的新行", day = 4, start = 7, end = 8, minute = -1, term = TERM)),
            outcome.content,
            "端内两行整行保留、位置也不动（id=7 / id=9 的那两行按端内的值），新行按档案里的原 id 追加在后",
        )
        assertTrue(outcome.content.startsWith(localRaw.dropLast(1)), "端内那两行就在最前面，逐字不动")
    }

    @Test
    fun `合并时缺主键或缺必填列的行跳过 端内那段解不开时一行都不追加`() {
        val local = arrayText(courseRow(7, SUFFIX_ID, "本机", day = 1, start = 1, end = 2, minute = -1, term = TERM))
        val archive = arrayTextOf(
            // 缺主键
            """{"accountId":"$SUFFIX_ID","courseName":"没有 id","weekBits":"0101010101","dayOfWeek":1,"startSection":1,"endSection":2,"termCode":"$TERM"}""",
            // 有主键但缺必填列（`custom_courses` 的端内读法是严格解码 ⇒ 这一行落进去会让整段解不开）
            """{"id":5,"accountId":"$SUFFIX_ID","courseName":"缺列"}""",
            courseRow(6, SUFFIX_ID, "完整的一行", day = 1, start = 1, end = 2, minute = -1, term = TERM).toString(),
        )

        val outcome = ArchiveRowFamilies.merge(Family.CUSTOM_COURSES, local, archive)

        assertEquals(1, outcome.appended)
        assertEquals(1, outcome.kept)
        assertEquals(2, outcome.skipped, "缺主键与缺必填列各一行")
        assertEquals(
            arrayText(courseRow(7, SUFFIX_ID, "本机", day = 1, start = 1, end = 2, minute = -1, term = TERM),
                courseRow(6, SUFFIX_ID, "完整的一行", day = 1, start = 1, end = 2, minute = -1, term = TERM)),
            outcome.content,
        )

        // 端内那段解不开 ⇒ 端内原样返回（一行都不追加、也不删）
        val broken = ArchiveRowFamilies.merge(Family.CUSTOM_COURSES, local, "{\"not\":\"an array\"}")
        assertEquals(local, broken.content)
        assertEquals(0, broken.appended)
        assertEquals(1, broken.kept)

        // 端上没有这一族 + 档案那段解不开 ⇒ 写一个空数组（端内原来就没有这个键）
        val empty = ArchiveRowFamilies.merge(Family.CUSTOM_COURSES, null, "不是 JSON")
        assertEquals("[]", empty.content)
        assertEquals(0, empty.kept)

        // 另两族的必填列是空表：只有主键的行也照追加（端内那两族的读法是宽容的）
        val wide = ArchiveRowFamilies.merge(Family.DOWNLOAD_TASKS, null, "[{\"id\":3}]")
        assertEquals("[{\"id\":3}]", wide.content)
        assertEquals(1, wide.appended)
    }

    @Test
    fun `合并是只增不减：端上本来没有这一族时全量追加 再合并一次逐字不变`() {
        val archive = arrayText(
            shelfRow("S-2", addedAt = 200),
            shelfRow("S-1", addedAt = 100),
        )

        val first = ArchiveRowFamilies.merge(Family.JIAOCAI_SHELF, null, archive)
        assertEquals(2, first.appended)
        assertEquals(0, first.kept)
        assertEquals(archive, first.content, "端上没有旧行 ⇒ 档案那一份原样写进去（不重排：排序是导出侧的事）")

        val again = ArchiveRowFamilies.merge(Family.JIAOCAI_SHELF, first.content, archive)
        assertEquals(0, again.appended, "同一个主键已经在端内 ⇒ 第二笔什么都不追加")
        assertEquals(2, again.kept)
        assertEquals(first.content, again.content)
    }

    @Test
    fun `of 只认这三条相对路径`() {
        assertEquals(Family.CUSTOM_COURSES, ArchiveRowFamilies.of("courses/custom_courses.json"))
        assertEquals(Family.DOWNLOAD_TASKS, ArchiveRowFamilies.of("media/download_tasks.json"))
        assertEquals(Family.JIAOCAI_SHELF, ArchiveRowFamilies.of("jiaocai/jiaocai1_shelf.json"))
        assertNull(ArchiveRowFamilies.of("agent_sessions/index.json"), "agent_sessions 那一族不归这里管")
        assertNull(ArchiveRowFamilies.of("courses/other.json"))
        assertNull(ArchiveRowFamilies.of("custom_courses.json"))
        assertEquals(ArchiveRowFamilies.Family.entries.size, ArchiveRowFamilies.Family.entries.map { it.relPath }.toSet().size)
    }

    // ── 夹具（列名与插入序逐字照 `:app` 的 `RoomArchiveFamilies` 那三个 toArchiveJson） ──

    private fun courseRow(
        id: Long,
        accountId: String,
        name: String,
        day: Int,
        start: Int,
        end: Int,
        minute: Int,
        term: String,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("accountId", accountId)
        put("courseName", name)
        put("teacher", "")
        put("location", "西-101")
        put("weekBits", "01111111111111111100")
        put("dayOfWeek", day)
        put("startSection", start)
        put("endSection", end)
        put("startMinuteOfDay", minute)
        put("endMinuteOfDay", -1)
        put("termCode", term)
        put("note", "")
        put("createdAt", FIXED_NOW)
    }

    private fun downloadRow(id: Long, createTime: Long): JsonObject = buildJsonObject {
        put("id", id)
        put("activityId", 1)
        put("courseName", "编造的课")
        put("activityTitle", "第 $id 讲")
        put("cameraType", "teacher")
        put("videoUrl", "https://example.invalid/$id.m3u8")
        put("audioSource", "")
        put("filePath", "/tmp/download/$id.ts")
        put("fileSize", 1024L)
        put("downloadedSize", 1024L)
        put("status", "completed")
        put("createTime", createTime)
        put("completeTime", createTime + 5)
        put("errorMessage", JsonNull)
        put("downloadSpeed", 0L)
    }

    private fun shelfRow(ssno: String, addedAt: Long): JsonObject = buildJsonObject {
        put("ssno", ssno)
        put("title", "编造的教材")
        put("author", "编造的作者")
        put("coverUrl", "https://example.invalid/$ssno.png")
        put("totalPages", 100)
        put("lastReadIndex", 0)
        put("lastReadAt", 0L)
        put("addedAt", addedAt)
    }

    private fun arrayText(vararg rows: JsonElement): String = JsonArray(rows.toList()).toString()

    /** 同上的「原样文本」版：要给出**缺列 / 缺主键**那种手工行时用。 */
    private fun arrayTextOf(vararg rowTexts: String): String =
        JsonArray(rowTexts.map { AppJson.parseToJsonElement(it) }).toString()

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private companion object {
        /** 夹具账号（与 `ArchiveFixture` 同一串数字，但这里不引它：这层只认列名）。 */
        const val SUFFIX_ID = "2253415556"

        const val TERM = "2026-2027-1"

        const val FIXED_NOW = 1_760_000_000_000L

        /** `custom_courses` 的 14 列（顺序即插入序）。 */
        val columnsOfCUSTOM = listOf(
            "id", "accountId", "courseName", "teacher", "location", "weekBits", "dayOfWeek",
            "startSection", "endSection", "startMinuteOfDay", "endMinuteOfDay", "termCode", "note", "createdAt",
        )
    }
}
