package com.xjtu.toolbox.archive

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 三族「行数组」在**端内键值店**与**档案 `files/` 层**之间的那一层（§2.3 / §6.3）。
 *
 * ## 为什么需要它
 *
 * Android 那三族的端内源是 Room 三张表（`custom_courses` / `download_tasks` / `jiaocai1_shelf`），
 * 行适配器在 `:app` 的 `RoomArchiveFamilies`：导出写行数组、导入读行数组，§8.3 钉着「表结构不许动」。
 * 桌面与 web **没有** Room —— 它们那三族是**键值店里的一个键**（`KeyValueCustomCourseStore` 的
 * `custom_courses`、`DesktopDownloadTaskStore` 的 `rows`、`DesktopJiaocai1ShelfStore` 的 `rows`，
 * web 上只有自建日程那一族）。§6.3 那条「`custom_courses` KV（桌面/web 单键）→ 拆成行数组」说的
 * 就是这里：端内那段 JSON 本身就是行数组，所以「拆」= **按 §2.3 重排 + 原样搬运**，不重新建模。
 *
 * ## 两件纯事
 *
 *  - [rowsForArchive]：端内那段 JSON → 档案行数组的字节（排交给 [ArchiveFileRows]，不另写一份排序）；
 *  - [merge]：档案行数组 → 端内那段 JSON（§6.4 的「本端胜」在**行**这一级：本端已有的主键一律保留，
 *    只追加端内没有的主键）。
 *
 * 刻意不碰任何端的 I/O：两端的宿主各自把 `KeyValueStore` 的读写接在两边（桌面是
 * `DesktopArchiveRows`、web 是 `WebArchiveImportTarget` / `WebArchiveHost` 里那四行）。
 *
 * ## 行内列名逐字
 *
 * 列名以 Room 三张实体为准（`CustomCourseEntity` / `DownloadTaskEntity` / `Jiaocai1ShelfEntity`，
 * `:app` 的 `RoomArchiveFamilies` 是标准答案）。这里只认主键与（`custom_courses` 那六列）必填列，
 * **不改行内的键名、值或字段集合**（§2.3 的总则）。
 */
object ArchiveRowFamilies {

    /** §2.3：`files/courses/custom_courses.json`。 */
    const val CUSTOM_COURSES_REL = "courses/custom_courses.json"

    /** §2.3：`files/media/download_tasks.json`。 */
    const val DOWNLOAD_TASKS_REL = "media/download_tasks.json"

    /** §2.3：`files/jiaocai/jiaocai1_shelf.json`。 */
    const val JIAOCAI_SHELF_REL = "jiaocai/jiaocai1_shelf.json"

    /**
     * 一族行数组的形状。
     *
     * @param relPath 档案里 `files/` 之下的相对路径（§2.3 逐字）
     * @param key 桌面/web 端内键值店里那一族的**键名**（Android 没有这一层：那边是 Room 表）
     * @param primaryKey 主键列名（合并的判据）
     * @param requiredColumns 端内读法解不开就会整段失效的列，缺了这些列的行**不追加**。
     *   只有 `custom_courses` 有：那一端走 `AppJson.decodeFromString<List<CustomCourse>>`（严格解码，
     *   这几个字段没有默认值）；下载队列与教材书架那两族的读法是宽容的（缺字段回落默认值），
     *   所以它们的必填列是空表 —— 这与 `:app` 侧「缺字段回落默认值、绝不抛」同一条口径。
     */
    enum class Family(
        val relPath: String,
        val key: String,
        val primaryKey: String,
        val requiredColumns: List<String>,
    ) {
        /** `custom_courses` 表 / 桌面的 `custom_courses` 单键。 */
        CUSTOM_COURSES(
            CUSTOM_COURSES_REL,
            "custom_courses",
            "id",
            listOf("courseName", "weekBits", "dayOfWeek", "startSection", "endSection", "termCode"),
        ),

        /** `download_tasks` 表 / 桌面的 `download_tasks<账号后缀>` 店里的 `rows` 键。 */
        DOWNLOAD_TASKS(DOWNLOAD_TASKS_REL, "rows", "id", emptyList()),

        /** `jiaocai1_shelf` 表 / 桌面的 `jiaocai1_shelf<账号后缀>` 店里的 `rows` 键。 */
        JIAOCAI_SHELF(JIAOCAI_SHELF_REL, "rows", "ssno", emptyList()),
    }

    /** 档案里的相对路径 → 族；不是这三条之一 ⇒ null（别的 `files/` 条目与这一层无关）。 */
    fun of(relPath: String): Family? = Family.entries.firstOrNull { it.relPath == relPath }

    /** §2.3 的排序口径（逐字调 [ArchiveFileRows]，不在这里另写一份比较器）。 */
    fun sort(family: Family, rows: JsonArray): JsonArray = when (family) {
        Family.CUSTOM_COURSES -> ArchiveFileRows.sortCustomCourses(rows)
        Family.DOWNLOAD_TASKS -> ArchiveFileRows.sortDownloadTasks(rows)
        Family.JIAOCAI_SHELF -> ArchiveFileRows.sortJiaocaiShelf(rows)
    }

    /**
     * 端内那段 JSON → 档案行数组的**字节**（§2.3：原样搬运 + 排好序）。
     *
     * 缺键 / 空表 / 解不开 ⇒ `null` = 这一族**不写进档案**：桌面上这三族没有「一张空表还在那儿」
     * 那种东西（那是 Room 才有的形状），写一个 `[]` 会声称本端这一族是空的。导入侧对 `[]` 也无事可做
     *（追加 0 行）。
     */
    fun rowsForArchive(family: Family, raw: String?): String? {
        val rows = parseRows(raw) ?: return null
        if (rows.isEmpty()) return null
        return sort(family, rows).toString()
    }

    /**
     * 档案行数组 → 端内那段 JSON 的**合并**（§6.4 的「本端胜」在行这一级）。
     *
     * 三条：
     *  1. **本端已有的主键一律保留**（本端那一行的值一个字不改，位置也不动）——与 §6.4
     *     「任一方缺 `at` ⇒ 本端胜」同一精神：桌面/web 的行没有 `at` 可比；
     *  2. 只追加**端内没有的主键**的行，`id` 保持档案里的原值（`id = maxOf(id) + 1` 的自增口径因此
     *     不会被破坏：追加之后那个 max 就是档案里最大的那个 id）；
     *  3. 档案里缺主键、或 `custom_courses` 缺必填列的行**跳过**并计入 [MergeOutcome.skipped] ——
     *     宁可少一行，也不让端内整段数组解不开（那会让这一族的读法变成「一条都没有」）。
     *
     * 档案里那段解不开（不是 JSON 数组）⇒ 端内那段**原样返回**、一行都不追加。
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
            // 本端已有这个主键 ⇒ 本端胜（档案里那一行不进来，端内那一行不改）
            if (!known.add(key)) continue
            out.add(row)
            appended++
        }
        return MergeOutcome(JsonArray(out).toString(), appended = appended, kept = local.size, skipped = skipped)
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /** 一段 JSON → 行数组；空串 / 缺键 / 不是数组 ⇒ null（宽进：坏的那一段当「没有」）。 */
    private fun parseRows(raw: String?): JsonArray? {
        if (raw.isNullOrBlank()) return null
        return runCatching { AppJson.parseToJsonElement(raw) as? JsonArray }.getOrNull()
    }

    /** 主键列的值按**文本**比（数字与字符串两种主键同一处处理）；JSON null / 缺列 ⇒ null。 */
    private fun JsonObject.keyOf(primaryKey: String): String? =
        (this[primaryKey] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}

/** 一次「档案行数组 → 端内键值店」的合并结果。 */
data class MergeOutcome(
    /** 要写回端内那个键的 JSON（本端的行在前、档案里新追加的行在后）。 */
    val content: String,
    /** 追加的行数（端内没有这个主键的那些）。 */
    val appended: Int,
    /** 原样保留的行数（本端胜的那些）。 */
    val kept: Int,
    /** 档案里缺主键或缺必填列、因而没有追加的行数。 */
    val skipped: Int,
)
