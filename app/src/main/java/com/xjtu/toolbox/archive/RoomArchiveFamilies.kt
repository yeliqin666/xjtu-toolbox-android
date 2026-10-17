package com.xjtu.toolbox.archive

import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfDao
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfEntity
import com.xjtu.toolbox.media.DownloadTaskDao
import com.xjtu.toolbox.media.DownloadTaskEntity
import com.xjtu.toolbox.schedule.CustomCourseDao
import com.xjtu.toolbox.schedule.CustomCourseEntity
import com.xjtu.toolbox.util.AppJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Room 三张表的行适配器（`:app` 侧接线点） */
object RoomArchiveFamilies {
    /** §2.3：`files/courses/custom_courses.json`。 */
    const val CUSTOM_COURSES_REL = "courses/custom_courses.json"

    /** §2.3：`files/media/download_tasks.json`。 */
    const val DOWNLOAD_TASKS_REL = "media/download_tasks.json"

    /** §2.3：`files/jiaocai/jiaocai1_shelf.json`。 */
    const val JIAOCAI_SHELF_REL = "jiaocai/jiaocai1_shelf.json"

    // ── 纯函数：表行 → 档案文件（排序交给 ArchiveFileRows，可测） ──

    /** `custom_courses` 表行 → `files/courses/custom_courses.json`（§2.3 六键升序）。 */
    fun customCoursesFile(rows: List<CustomCourseEntity>): ArchiveFile =
        ArchiveFile(
            suffix = "",
            relPath = CUSTOM_COURSES_REL,
            content = ArchiveFileRows.sortCustomCourses(JsonArray(rows.map { it.toArchiveJson() })).toString(),
        )

    /** `download_tasks` 表行 → `files/media/download_tasks.json`（§2.3 `(createTime desc, id)`）。 */
    fun downloadTasksFile(rows: List<DownloadTaskEntity>): ArchiveFile =
        ArchiveFile(
            suffix = "",
            relPath = DOWNLOAD_TASKS_REL,
            content = ArchiveFileRows.sortDownloadTasks(JsonArray(rows.map { it.toArchiveJson() })).toString(),
        )

    /** `jiaocai1_shelf` 表行 → `files/jiaocai/jiaocai1_shelf.json`（§2.3 `(addedAt desc, ssno)`）。 */
    fun jiaocaiShelfFile(rows: List<Jiaocai1ShelfEntity>): ArchiveFile =
        ArchiveFile(
            suffix = "",
            relPath = JIAOCAI_SHELF_REL,
            content = ArchiveFileRows.sortJiaocaiShelf(JsonArray(rows.map { it.toArchiveJson() })).toString(),
        )

    // ── DAO 只读投影（suspend；接线点在 AppArchiveHost） ──

    /** 只读 DAO 读所有账号的 `custom_courses` 行。 */
    suspend fun readCustomCourses(dao: CustomCourseDao, accountIds: List<String>): List<CustomCourseEntity> {
        val seen = mutableSetOf<Long>()
        val out = mutableListOf<CustomCourseEntity>()
        for (accountId in (accountIds + "").distinct()) {
            dao.getAll(accountId).forEach { row -> if (seen.add(row.id)) out.add(row) }
        }
        return out
    }

    /** 只读 DAO 读 `download_tasks` 全表（既有查询 [DownloadTaskDao.getAll]）。 */
    suspend fun readDownloadTasks(dao: DownloadTaskDao): List<DownloadTaskEntity> = dao.getAll()

    /** 只读 DAO 读 `jiaocai1_shelf` 全表（既有查询 [Jiaocai1ShelfDao.observeAll] 取当前值）。 */
    suspend fun readJiaocaiShelf(dao: Jiaocai1ShelfDao): List<Jiaocai1ShelfEntity> = dao.observeAll().first()

    /** `files/courses/custom_courses.json` → 表行。 */
    fun customCoursesFrom(content: String): List<CustomCourseEntity> =
        rowsOf(content).map { o ->
            CustomCourseEntity(
                id = o.long("id"),
                accountId = o.str("accountId"),
                courseName = o.str("courseName"),
                teacher = o.str("teacher"),
                location = o.str("location"),
                weekBits = o.str("weekBits"),
                dayOfWeek = o.int("dayOfWeek"),
                startSection = o.int("startSection"),
                endSection = o.int("endSection"),
                startMinuteOfDay = o.int("startMinuteOfDay", -1),
                endMinuteOfDay = o.int("endMinuteOfDay", -1),
                termCode = o.str("termCode"),
                note = o.str("note"),
                createdAt = o.long("createdAt"),
            )
        }

    /** `files/media/download_tasks.json` → 表行（可空列还原为 `null`）。 */
    fun downloadTasksFrom(content: String): List<DownloadTaskEntity> =
        rowsOf(content).map { o ->
            DownloadTaskEntity(
                id = o.long("id"),
                activityId = o.int("activityId"),
                courseName = o.str("courseName"),
                activityTitle = o.str("activityTitle"),
                cameraType = o.str("cameraType"),
                videoUrl = o.str("videoUrl"),
                audioSource = o.str("audioSource"),
                filePath = o.str("filePath"),
                fileSize = o.long("fileSize"),
                downloadedSize = o.long("downloadedSize"),
                status = o.str("status"),
                createTime = o.long("createTime"),
                completeTime = o.longOrNull("completeTime"),
                errorMessage = o.strOrNull("errorMessage"),
                downloadSpeed = o.long("downloadSpeed"),
            )
        }

    /** `files/jiaocai/jiaocai1_shelf.json` → 表行。 */
    fun jiaocaiShelfFrom(content: String): List<Jiaocai1ShelfEntity> =
        rowsOf(content).map { o ->
            Jiaocai1ShelfEntity(
                ssno = o.str("ssno"),
                title = o.str("title"),
                author = o.str("author"),
                coverUrl = o.str("coverUrl"),
                totalPages = o.int("totalPages"),
                lastReadIndex = o.int("lastReadIndex"),
                lastReadAt = o.long("lastReadAt"),
                addedAt = o.long("addedAt"),
            )
        }

    /** 导入前的行数组预检（§6.1 第 3 条）：解不开就直接抛，由调用方翻译成 [ImportResult.Failed]。 */
    fun checkRows(relPath: String, content: String) {
        when (relPath) {
            CUSTOM_COURSES_REL -> customCoursesFrom(content)
            DOWNLOAD_TASKS_REL -> downloadTasksFrom(content)
            JIAOCAI_SHELF_REL -> jiaocaiShelfFrom(content)
        }
    }


    private fun rowsOf(content: String): List<JsonObject> {
        val array = AppJson.parseToJsonElement(content) as? JsonArray
            ?: throw IllegalStateException("不是 JSON 行数组")
        return array.mapNotNull { it as? JsonObject }
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

    private fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.int(key: String, default: Int = 0): Int =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: default

    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
}


private fun CustomCourseEntity.toArchiveJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("accountId", accountId)
    put("courseName", courseName)
    put("teacher", teacher)
    put("location", location)
    put("weekBits", weekBits)
    put("dayOfWeek", dayOfWeek)
    put("startSection", startSection)
    put("endSection", endSection)
    put("startMinuteOfDay", startMinuteOfDay)
    put("endMinuteOfDay", endMinuteOfDay)
    put("termCode", termCode)
    put("note", note)
    put("createdAt", createdAt)
}

private fun DownloadTaskEntity.toArchiveJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("activityId", activityId)
    put("courseName", courseName)
    put("activityTitle", activityTitle)
    put("cameraType", cameraType)
    put("videoUrl", videoUrl)
    put("audioSource", audioSource)
    put("filePath", filePath)
    put("fileSize", fileSize)
    put("downloadedSize", downloadedSize)
    put("status", status)
    put("createTime", createTime)
    put("completeTime", completeTime?.let { JsonPrimitive(it) } ?: JsonNull)
    put("errorMessage", errorMessage?.let { JsonPrimitive(it) } ?: JsonNull)
    put("downloadSpeed", downloadSpeed)
}

private fun Jiaocai1ShelfEntity.toArchiveJson(): JsonObject = buildJsonObject {
    put("ssno", ssno)
    put("title", title)
    put("author", author)
    put("coverUrl", coverUrl)
    put("totalPages", totalPages)
    put("lastReadIndex", lastReadIndex)
    put("lastReadAt", lastReadAt)
    put("addedAt", addedAt)
}
