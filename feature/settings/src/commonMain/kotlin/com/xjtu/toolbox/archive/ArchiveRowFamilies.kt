package com.xjtu.toolbox.archive

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 三族行数组（自建日程、下载队列、教材书架）在端内 JSON 与档案 `files/` 之间的转换与合并（docs/archive-format.md 的 files 与「导入」两节）。
 * 不碰 I/O，键值店读写在各端（`DesktopArchiveRows`、`WebArchiveRows`）；Android 走 Room，不经这里。
 */
object ArchiveRowFamilies {

    const val CUSTOM_COURSES_REL = "courses/custom_courses.json"
    const val DOWNLOAD_TASKS_REL = "media/download_tasks.json"
    const val JIAOCAI_SHELF_REL = "jiaocai/jiaocai1_shelf.json"

    /**
     * @param key 桌面 / web 键值店里存这一族的键
     * @param requiredColumns 缺了就不追加的列：`custom_courses` 端内是严格解码，缺这些字段整段都解不开
     */
    enum class Family(
        val relPath: String,
        val key: String,
        val primaryKey: String,
        val requiredColumns: List<String>,
    ) {
        CUSTOM_COURSES(
            CUSTOM_COURSES_REL,
            "custom_courses",
            "id",
            listOf("courseName", "weekBits", "dayOfWeek", "startSection", "endSection", "termCode"),
        ),
        DOWNLOAD_TASKS(DOWNLOAD_TASKS_REL, "rows", "id", emptyList()),
        JIAOCAI_SHELF(JIAOCAI_SHELF_REL, "rows", "ssno", emptyList()),
    }

    fun of(relPath: String): Family? = Family.entries.firstOrNull { it.relPath == relPath }

    fun sort(family: Family, rows: JsonArray): JsonArray = when (family) {
        Family.CUSTOM_COURSES -> ArchiveFileRows.sortCustomCourses(rows)
        Family.DOWNLOAD_TASKS -> ArchiveFileRows.sortDownloadTasks(rows)
        Family.JIAOCAI_SHELF -> ArchiveFileRows.sortJiaocaiShelf(rows)
    }

    /** 端内 JSON → 排好序的档案行数组。没有行时返回 null（不写 `[]`，免得声称本端这一族是空表）。 */
    fun rowsForArchive(family: Family, raw: String?): String? {
        val rows = parseRows(raw) ?: return null
        if (rows.isEmpty()) return null
        return sort(family, rows).toString()
    }

    /**
     * 档案行数组并进端内 JSON：本端已有的主键整行保留，只追加新主键（`id` 保持原值）；
     * 缺主键或缺必填列的行跳过并计数。档案那段解不开时端内原样返回。
     */
    fun merge(family: Family, localRaw: String?, archiveContent: String): MergeOutcome {
        val local = parseRows(localRaw)?.filterIsInstance<JsonObject>() ?: emptyList()
        val archive = parseRows(archiveContent)
            ?: return MergeOutcome(localRaw ?: "[]", appended = 0, kept = local.size, skipped = 0)

        val known = HashSet<String>(local.size * 2)
        local.forEach { row -> row.keyOf(family.primaryKey)?.let { known.add(it) } }

        val out = ArrayList<JsonObject>(local.size + archive.size)
        out.addAll(local)
        var appended = 0
        var skipped = 0
        for (element in archive) {
            val row = element as? JsonObject
            val key = row?.keyOf(family.primaryKey)
            val complete = row != null && key != null && family.requiredColumns.all { row[it] != null }
            if (!complete) {
                skipped++
                continue
            }
            if (!known.add(key)) continue
            out.add(row)
            appended++
        }
        return MergeOutcome(JsonArray(out).toString(), appended = appended, kept = local.size, skipped = skipped)
    }

    private fun parseRows(raw: String?): JsonArray? {
        if (raw.isNullOrBlank()) return null
        return runCatching { AppJson.parseToJsonElement(raw) as? JsonArray }.getOrNull()
    }

    /** 主键按文本比，数字和字符串主键一样处理。 */
    private fun JsonObject.keyOf(primaryKey: String): String? =
        (this[primaryKey] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}

/** 一次合并的结果：[content] 写回端内，本端的行在前、新追加的在后。 */
data class MergeOutcome(
    val content: String,
    val appended: Int,
    val kept: Int,
    val skipped: Int,
)
