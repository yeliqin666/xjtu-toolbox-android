package com.xjtu.toolbox.archive

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 「成绩缓存」这一族的**档案形状 + 档案键名**（storage-layout §6.3 逐族迁移规则的一员：
 * 「Android `cache/data_cache<suffix>/<key>.json` → `files/schedule/<key>.json`，`mtime → at`」那一行）。
 *
 * 为什么这一族要单独一条规则：三端**端内**是三种形状，档案要是原样搬运，就有两端的成绩屏读不出来 ——
 * - `:app`：`cache/data_cache<suffix>/score_report_<学号>.json`，内容是**裸数组**，年龄靠文件 mtime
 *   （`DataCache` / `ScoreReportApp`）；
 * - 桌面：`score_report<suffix>` 店里的 `score_report_<学号>` 键，值是 `{"at":<ms>,"grades":[…]}`（`DesktopScoreReportCache`）；
 * - web：`score_report_cache` 店的 `all` 键，同一个形状（`WebScoreReportCache`）。
 *
 * 所以本族在档案里定成**一个**规范形状 + **一个**规范名：
 *
 * - **形状** `{"at":<ms>,"grades":[…]}`（＝ 桌面/web 的端内形状）：`at` 是这份成绩的采集时刻，
 *   端内的 mtime（`:app`）与端内的 `at`（桌面/web）都折进它（§6.3「mtime → `at`」）。
 *   `grades` 是端内那份 JSON 数组**逐字**搬过来，不重新建模（§2.3 总则：`files/` 只搬字节）。
 * - **名字** `accounts/<suffix>/files/schedule/score_report.json` —— **不含学号**：账号维度已经由
 *   `accounts/<suffix>/` 那一层承担，学号只是同一件事的第二种说法（§2.0 的唯一映射）。
 *
 * 名字按**族**认（[FAMILY] 这条模式），不是只认某一个文件名：
 * - `schedule/score_report.json` —— 规范名，导出写它；
 * - `schedule/score_report_<学号>.json` —— 老档案里的名字、也是 `:app` 的端内原名，**照样认**
 *   （§6.1 第 2 条：老档案必须还能导入）。
 *
 * **端内形状一个都不改**（C1 红线）：导出只在「写进档案」那一刻转成规范形状，导入只在「落进端内」
 * 那一刻转回本端形状（见 [ArchiveImportTarget.fileRelPath] / [ArchiveImportTarget.fileContent]）。
 * 转不动的条目由调用方**跳过并计数**，绝不抛（§8.6）。
 */
object ArchiveScoreReport {

    /** §2.3：这一族在 `files/` 之下的目录（档案与端内同名）。 */
    const val REL_DIR = "schedule"

    /** 端内键名的公共前缀（三端同一个键名：`ScoreReportApp` / `DesktopScoreReportCache.keyFor` / `WebScoreReportCache.KEY`）。 */
    const val KEY = "score_report"

    /** 规范的档案路径：不含学号（见类 KDoc）。落在 `files/<relPath>`，即 `accounts/<suffix>/files/schedule/score_report.json`。 */
    const val CANONICAL_REL = "$REL_DIR/$KEY.json"

    /** 族成员的名字模式：`schedule/score_report.json`（规范）或 `schedule/score_report_<学号>.json`（旧名）。 */
    private val FAMILY = Regex("^$REL_DIR/($KEY)(?:_([^/]+))?\\.json$")

    /** `DataCache` 的文件名安全化：键名里只留 `[a-zA-Z0-9_-]`（`/` 这类字符会换掉，不会跑出目录）。 */
    private val UNSAFE = Regex("[^a-zA-Z0-9_-]")

    /** 档案里这条 `files/` 相对路径是不是这一族（规范名与旧名都算）。 */
    fun isFamily(relPath: String): Boolean = FAMILY.matches(relPath)

    /** 旧名里的学号（`schedule/score_report_<学号>.json` ⇒ `<学号>`）；规范名 / 不是这一族 ⇒ null。 */
    fun legacyStudentId(relPath: String): String? =
        FAMILY.matchEntire(relPath)?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() }

    /**
     * 端内原名（与 `DataCache` 的 `${key}.json` 同一口径）：`schedule/score_report_<学号>.json`。
     *
     * `:app` 把它当真正的落地路径；桌面端内那一族在 KV 店里，但也用这个名字当「落地路径」把学号带过去
     * （见 `DesktopArchiveImportTarget`）—— 端内名要学号这件事只在这一处表达。
     */
    fun endRelPath(studentId: String): String = "$REL_DIR/${(KEY + "_" + studentId).replace(UNSAFE, "_")}.json"

    /**
     * `accounts/<suffix>` → 学号。
     *
     * `manifest.accounts` 里那一档的 `accountId` 最权威（安全化不可逆：`a-b` 与 `a_b` 会撞成同一个 suffix，
     * §4 也只在那时判碰撞）；档案里查不到时才退一步剥掉后缀开头那个 `_`。
     * `global/`（没有账号维度）/ `default`（无账号）/ `_`（空账号）⇒ null（调用方跳过并计数）。
     */
    fun studentIdOf(suffix: String, accountId: String?): String? {
        if (!accountId.isNullOrBlank()) return accountId
        if (!suffix.startsWith("_")) return null
        return suffix.drop(1).takeIf { it.isNotEmpty() }
    }

    /**
     * 端内那份（裸数组 / 已经是规范形状）⇒ **档案里的规范形状**；转不动 ⇒ null。
     *
     * [at] 是这份成绩的年龄：导出时给端内 mtime（§6.3「mtime → at」），导入端重建旧档案时给导入时刻。
     * 裸数组按字节拼进去（不重新序列化 `grades`）；已经是 `{"at","grades"}` 的照原样（它自己带着更可信的 `at`）。
     */
    fun toCanonical(content: String, at: Long): String? {
        val text = content.trim()
        return when {
            text.startsWith("[") -> if (gradesArray(text) == null) null else """{"at":$at,"grades":$text}"""
            text.startsWith("{") -> text.takeIf { canonicalGrades(it) != null }
            else -> null
        }
    }

    /** 档案形状 ⇒ `:app` 端内的裸 `grades` 数组（年龄交给文件 mtime）；转不动 ⇒ null。 */
    fun toBareGrades(content: String): String? {
        val text = content.trim()
        return when {
            text.startsWith("[") -> text.takeIf { gradesArray(it) != null }
            text.startsWith("{") -> canonicalGrades(text)?.toString()
            else -> null
        }
    }

    /** 规范形状里那份 `grades`；不是规范形状（缺 `at` / 缺 `grades` / 不是对象）⇒ null。 */
    fun canonicalGrades(content: String): JsonArray? = runCatching {
        val obj = AppJson.parseToJsonElement(content) as? JsonObject ?: return@runCatching null
        (obj["at"] as? JsonPrimitive)?.longOrNull ?: return@runCatching null
        obj["grades"] as? JsonArray
    }.getOrNull()

    /** 一个 JSON 数组（裸数组形状的合法性）；不是数组 / 解不开 ⇒ null。 */
    fun gradesArray(content: String): JsonArray? =
        runCatching { AppJson.parseToJsonElement(content) as? JsonArray }.getOrNull()
}
