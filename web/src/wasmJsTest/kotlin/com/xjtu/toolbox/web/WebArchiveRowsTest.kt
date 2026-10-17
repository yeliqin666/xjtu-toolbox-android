package com.xjtu.toolbox.web

import com.xjtu.toolbox.archive.ArchiveFile
import com.xjtu.toolbox.archive.ArchivePaths
import com.xjtu.toolbox.archive.ArchiveRowFamilies
import com.xjtu.toolbox.archive.ImportResult
import com.xjtu.toolbox.archive.archiveStoreBackend
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.schedule.CustomCourse
import com.xjtu.toolbox.schedule.KeyValueCustomCourseStore
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 三族行数组在 **web 这一端**的落地（工单尾部：桌面与 web 那两族的收尾；`WebArchiveRows`）。
 *
 * 与桌面那份 `DesktopArchiveRowsJvmTest` 同一批断言，落点换成本端真有的那一档：这里跑在**真浏览器**
 * （karma + chromium）里，`localStorage` 是真的 ⇒ 断言对象就是端内那个键
 * （`xjtu-toolbox.custom_courses.custom_courses`）与这一端**原来的读法**
 * （`KeyValueCustomCourseStore.all()` 里那一句 `AppJson.decodeFromString<List<CustomCourse>>`；
 * 这里不调 `getAll`：它是 `suspend`，而 wasmJsTest 里没有 `runTest` 可用 —— 见
 * `web/build.gradle.kts` 里那段实测记录）。
 *
 * 钉住两件事：
 *  - **导出形状与 Android / 桌面同形**：列名逐字（拿 `:core` 的 [CustomCourse] 编一遍比，不是抄一份字面量）、
 *    `id` 保持原值、排序只调 `:core` 的 `ArchiveFileRows`；
 *  - **导入 = 端内已有主键整行保留、只追加新主键**；另外两族本端没有落点 ⇒ 一条都不假装写。
 */
class WebArchiveRowsTest {

    // ── 导出 ──────────────────────────────────────────────────────────────────

    @Test
    fun `导出 自建日程那一族进 files 行数组 列名逐字且 id 保持原值`() {
        clearCustomCourses()
        try {
            val lateTerm = courseRow(7, "上学期的课", day = 7, startSection = 11, endSection = 12, term = "2025-2026-2")
            val minute = courseRow(2, "分钟版", day = 1, startSection = 3, endSection = 4, startMinuteOfDay = 0)
            val first = courseRow(1, "最早的一行", day = 1, startSection = 3, endSection = 4, startMinuteOfDay = -1)
            val otherDay = courseRow(3, "星期二的课", day = 2, startSection = 1, endSection = 2)
            // 端内故意乱序：落进档案的次序必须由 :core 的排序口径决定，不是端内 KV 里那一段的原序
            customCourses().putString(KEY, arrayText(otherDay, lateTerm, minute, first))

            val archive = WebArchiveHost.exportArchive(now = { FIXED_NOW })
            val path = ArchivePaths.filePath("", ArchiveRowFamilies.CUSTOM_COURSES_REL)

            assertEquals(
                arrayText(lateTerm, first, minute, otherDay),
                archive[path],
                "六键升序：账号 → 学期 → 星期 → 起始节 → 起始分钟 → id",
            )
            val rows = AppJson.parseToJsonElement(archive.getValue(path)) as JsonArray
            assertEquals(
                listOf(modelColumns()),
                rows.map { (it as JsonObject).keys.toList() }.distinct(),
                "列名逐字、列序与 :app 的 CustomCourseEntity 同序（这里拿 :core 的 CustomCourse 编一遍比）",
            )
            assertEquals(
                listOf(7L, 1L, 2L, 3L),
                rows.map { (it as JsonObject).long("id") },
                "id 保持原值（没被重编号成 1..4）",
            )

            assertNull(
                archive[ArchivePaths.filePath("", ArchiveRowFamilies.DOWNLOAD_TASKS_REL)],
                "下载队列这一族本端没有落点：一条都不写",
            )
            assertNull(
                archive[ArchivePaths.filePath("", ArchiveRowFamilies.JIAOCAI_SHELF_REL)],
                "教材书架同理",
            )

            assertEquals(
                KeyValueCustomCourseStore.KEY,
                ArchiveRowFamilies.Family.CUSTOM_COURSES.key,
                "端内读法的键与档案层这一族的键是同一个字面",
            )
            assertEquals(
                listOf(ArchiveRowFamilies.Family.CUSTOM_COURSES),
                WebArchiveRows.landed,
                "本端有落点的族只有自建日程那一族",
            )
        } finally {
            clearCustomCourses()
        }
    }

    @Test
    fun `导出 端上没有行的那一族不写 不拿一个空数组声称这一族是空的`() {
        clearCustomCourses()
        try {
            assertNull(customCourses().getString(KEY), "起手端内没有这一段")
            assertEquals(
                0,
                filesLayerOf(WebArchiveHost.exportArchive(now = { FIXED_NOW })),
                "没有行 ⇒ files/ 一条都不写",
            )

            customCourses().putString(KEY, "[]")
            assertEquals(
                0,
                filesLayerOf(WebArchiveHost.exportArchive(now = { FIXED_NOW })),
                "端内是空数组 ⇒ 也不写：空档案不等于「这一族是空的」",
            )
        } finally {
            clearCustomCourses()
        }
    }

    // ── 导入 ──────────────────────────────────────────────────────────────────

    @Test
    fun `导入 自建日程那一族合进键值店 端内已有主键整行保留 只追加新主键`() {
        clearCustomCourses()
        try {
            val localA = courseRow(1, "本机甲", day = 1, startSection = 1, endSection = 2)
            val localB = courseRow(2, "本机乙", day = 2, startSection = 3, endSection = 4)
            customCourses().putString(KEY, arrayText(localA, localB))

            val archive = WebArchiveHost.buildArchive(
                stores = emptyList(),
                files = listOf(
                    ArchiveFile(
                        "",
                        ArchiveRowFamilies.CUSTOM_COURSES_REL,
                        arrayText(
                            courseRow(1, "档案里的甲", day = 3, startSection = 5, endSection = 6),
                            courseRow(5, "档案里的新行", day = 4, startSection = 7, endSection = 8),
                            missingColumnsRow(9, "缺必填列的行"),
                        ),
                    ),
                ),
                now = { FIXED_NOW },
            )

            assertEquals(
                ImportResult.Ok(keysWritten = 0, keysKept = 0, filesWritten = 1),
                WebArchiveHost.importArchive(archive),
                "档案里没有 stores/ ⇒ 键一笔不写；行数组那一族真落了 1 条",
            )

            val merged = AppJson.decodeFromString<List<CustomCourse>>(customCourses().getString(KEY).orEmpty())
            assertEquals(
                listOf(1L, 2L, 5L),
                merged.map { it.id },
                "端内两行的 id 原样；档案里那行按原 id=5 追加（位值没重编号）",
            )
            assertEquals(
                listOf("本机甲", "本机乙", "档案里的新行"),
                merged.map { it.courseName },
                "主键撞了 ⇒ 端内那一行整行保留（「档案里的甲」一个字都没进来）",
            )
            assertEquals(listOf(ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID), merged.map { it.accountId })
            assertTrue(
                merged.none { it.id == 9L },
                "缺必填列的那一行跳过：它进来会让端内整段数组解不开（读法会变成「一条都没有」）",
            )
        } finally {
            clearCustomCourses()
        }
    }

    @Test
    fun `导入 另两族在本端没有落点 一条都不假装写 未落地数如实报`() {
        clearCustomCourses()
        try {
            val archive = WebArchiveHost.buildArchive(
                stores = emptyList(),
                files = listOf(
                    ArchiveFile("", ArchiveRowFamilies.CUSTOM_COURSES_REL, arrayText(courseRow(4, "自建日程"))),
                    ArchiveFile("", ArchiveRowFamilies.DOWNLOAD_TASKS_REL, DOWNLOAD_TASKS_ROWS),
                    ArchiveFile("", ArchiveRowFamilies.JIAOCAI_SHELF_REL, JIAOCAI_SHELF_ROWS),
                    ArchiveFile("", AGENT_SESSIONS_REL, AGENT_SESSIONS_INDEX),
                ),
                now = { FIXED_NOW },
            )

            assertEquals(
                ImportResult.Ok(keysWritten = 0, keysKept = 0, filesWritten = 1),
                WebArchiveHost.importArchive(archive),
                "只有自建日程那一族落了：另两条行数组与那一条 agent_sessions 文件本端都没有落点",
            )

            val storeNames = archiveStoreBackend().storeNames()
            assertTrue(
                storeNames.none { it == "download_tasks" || it.startsWith("download_tasks_") },
                "下载队列这一族本端没有业务：不新起一个没人读的店（实际：$storeNames）",
            )
            assertTrue(
                storeNames.none { it == "jiaocai1_shelf" || it.startsWith("jiaocai1_shelf_") },
                "教材书架同理（实际：$storeNames）",
            )
            assertNull(keyValueStore("download_tasks").getString("rows"), "不写没人读的键")
            assertNull(keyValueStore("jiaocai1_shelf").getString("rows"), "同上")

            val text = WebArchiveHost.importStatusText(PickedArchive.Entries("fixture.zip", archive))
            assertTrue(text.contains("落了 1 条"), text)
            assertTrue(text.contains("3 个其他 files/ 条目未落地"), text)
        } finally {
            clearCustomCourses()
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    /** 端内那一段：`localStorage["xjtu-toolbox.custom_courses.custom_courses"]`（`KeyValueStore` 的拼法）。 */
    private fun customCourses() = keyValueStore(WebScheduleHost.CUSTOM_COURSE_STORE)

    private fun clearCustomCourses() = customCourses().remove(KEY)

    /** 档案里 `files/` 那一层的条目数。 */
    private fun filesLayerOf(archive: Map<String, String>): Int =
        archive.keys.count { ArchivePaths.parseFilePath(it) != null }

    private fun arrayText(vararg rows: JsonElement): String = JsonArray(rows.toList()).toString()

    /** `:app` 的 `CustomCourseEntity` 那 14 列，逐字取自 `:core` 的 [CustomCourse] 的声明序。 */
    private fun modelColumns(): List<String> {
        val json = AppJson.encodeToString(
            CustomCourse(
                courseName = "占位",
                weekBits = "0",
                dayOfWeek = 1,
                startSection = 1,
                endSection = 2,
                termCode = TERM,
            ),
        )
        return (AppJson.parseToJsonElement(json) as JsonObject).keys.toList()
    }

    /** 一行端内形状的自建日程（14 列，列序同 Room 的插入序）。 */
    private fun courseRow(
        id: Long,
        name: String,
        day: Int = 1,
        startSection: Int = 1,
        endSection: Int = 2,
        startMinuteOfDay: Int = -1,
        term: String = TERM,
        account: String = ACCOUNT_ID,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("accountId", account)
        put("courseName", name)
        put("teacher", "")
        put("location", "西-101")
        put("weekBits", "01111111111111111100")
        put("dayOfWeek", day)
        put("startSection", startSection)
        put("endSection", endSection)
        put("startMinuteOfDay", startMinuteOfDay)
        put("endMinuteOfDay", -1)
        put("termCode", term)
        put("note", "")
        put("createdAt", FIXED_NOW)
    }

    /** 有主键但缺必填列（`weekBits` 等）的一行：合并时必须跳过。 */
    private fun missingColumnsRow(id: Long, name: String): JsonObject = buildJsonObject {
        put("id", id)
        put("accountId", ACCOUNT_ID)
        put("courseName", name)
    }

    private fun JsonObject.long(key: String): Long =
        (get(key) as? JsonPrimitive)?.content?.toLongOrNull() ?: -1L

    private companion object {
        /** 与 `:core` 的 `ArchiveFixture.ACCOUNT_ID` 同一个值（端内读法按它过滤）。 */
        const val ACCOUNT_ID = "2253415556"

        const val TERM = "2026-2027-1"

        const val FIXED_NOW = 1_760_000_000_000L

        /** 端内那个键名（`KeyValueCustomCourseStore.KEY`，逐字相同）。 */
        const val KEY = "custom_courses"

        const val AGENT_SESSIONS_REL = "agent_sessions/index.json"

        /** 编造的两条别的族的行（本端没有对应业务，只用来验「不假装写」）。 */
        const val DOWNLOAD_TASKS_ROWS = """[{"id":7,"courseName":"编造的课","createTime":100}]"""

        const val JIAOCAI_SHELF_ROWS = """[{"ssno":"S-1","title":"编造的教材","addedAt":100}]"""

        const val AGENT_SESSIONS_INDEX = """{"sessions":[]}"""
    }
}
