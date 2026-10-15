package com.xjtu.toolbox.archive

import java.io.File

/**
 * `:app` 侧的一次性导入**编排**（S4 收尾）：自校验 → 行数组预检 → §6.4 合并 → 落「已导入」标记。
 *
 * 它自己**不含任何合并规则** —— 裁决（`at` 新者胜 / 缺 `at` 本端胜 / 本端有档案没有 ⇒ 保持不变 /
 * `set` 覆盖不并集 / `format`·`version` 不认识 ⇒ 明确失败）逐字来自 `:core` 的 [StoreArchive.import]。
 * 这一层只按 §6.1 第 3 条把顺序钉死，让「任一步失败 ⇒ 端内零改动」成立：
 *
 *  1. [StoreArchive.inspect] 先自校验（`manifest.json` 在不在 / `format` / `version` / 每个 store 解得开 /
 *     后缀不碰撞）—— 失败就返回 [ArchiveImportOutcome.Failed]，**连写入缝都还没建**；
 *  2. `files/` 里的 Room 行数组先解一遍（[RoomArchiveFamilies.checkRows]）—— 解不开同样在这里停住，
 *     不会「store 的键写了、行数组那半炸了」；
 *  3. 交给 [StoreArchive.import] 全量裁决 + 逐笔落地；
 *  4. 成功才写「已导入」标记（[ArchiveImportOutcome.Imported]）。
 *
 * ## 「已导入」标记的落点（§8.1 禁新建 prefs / §8.7 禁写 `data_cache*`）
 *
 * 标记是**档案落地目录里的一个文件**，跟着那份档案走：
 *  - 目录形态的档案 ⇒ `<那个目录>/.imported`（`adb push` 解开的档案就地一份）；
 *  - zip 形态 ⇒ `<那个 zip>.imported`（旁挂一个同名文件 —— zip 内部没地方写）。
 *
 * 标记的**第一行**是那份档案的身份（[identityOf]：`createdAt` + `createdBy` + `version`），
 * 用来认「这份档案已经导过了」；其余几行是这一次落地的计数，给人看。
 * 它**不写进 prefs**（§8.1 禁新建 archive 专属 prefs），**也不写进 `data_cache*`**（§8.7：换包清理会
 * 连标记一起删掉 ⇒ 每次升级都重导一遍）；档案目录本身在 `data_extraction_rules.xml` 里已排除备份。
 */
object AppArchiveImport {

    /** 标记文件名（档案目录里的一份点文件；[AppArchiveHost.readDirectory] 读档案时会跳过点文件）。 */
    const val MARKER_NAME = ".imported"

    private const val MARKER_SUFFIX = ".imported"

    /** 那一份档案的标记文件（目录形态写进目录里，zip 形态写它旁边）。 */
    fun markerFileFor(archive: File): File =
        if (archive.isDirectory) {
            File(archive, MARKER_NAME)
        } else {
            File(archive.parentFile, archive.name + MARKER_SUFFIX)
        }

    /** 档案的身份（§2.1）：同一份档案再导入一次要被认出来（§6.1 第 1 条的幂等）。 */
    fun identityOf(manifest: ArchiveManifest): String =
        "${manifest.createdAt}|${manifest.createdBy}|${manifest.version}"

    /** 标记里记着的身份（第一行）；没有标记 / 读不出来 ⇒ null。 */
    fun readMarkerIdentity(marker: File): String? =
        if (!marker.isFile) null
        else runCatching { marker.readText().lineSequence().firstOrNull() }.getOrNull()

    /**
     * 跑一次导入。
     *
     * [targetOf] 是**在自校验通过之后**才被调用的写入缝工厂：坏档案连端内句柄都不会被建起来
     * （A7 的「端内零改动」在这里是结构性的，不靠约定）。而且它拿得到已验证的 [ArchiveManifest]，
     * 好让宿主用它补上「档案里有、端内还没登录」的那些账号 ID。
     */
    fun run(
        archive: File,
        entries: Map<String, String>,
        targetOf: (ArchiveManifest) -> ArchiveImportTarget,
    ): ArchiveImportOutcome {
        val validated = StoreArchive.inspect(archiveSourceOf(entries))
        if (validated is ArchiveInspection.Failed) return ArchiveImportOutcome.Failed(validated.reason)
        val manifest = (validated as ArchiveInspection.Ok).manifest

        val marker = markerFileFor(archive)
        val identity = identityOf(manifest)
        if (readMarkerIdentity(marker) == identity) return ArchiveImportOutcome.AlreadyImported(archive, identity)

        checkRows(entries)?.let { return ArchiveImportOutcome.Failed(it) }

        return when (val result = StoreArchive.import(archiveSourceOf(entries), targetOf(manifest))) {
            is ImportResult.Failed -> ArchiveImportOutcome.Failed(result.reason)
            is ImportResult.Ok -> {
                writeMarker(marker, identity, result)
                ArchiveImportOutcome.Imported(archive, identity, result)
            }
        }
    }

    /** Room 三族的行数组预检：不是 JSON 行数组 ⇒ 一句明确的原因（写第一笔之前就停住）。 */
    private fun checkRows(entries: Map<String, String>): String? {
        entries.forEach { (path, content) ->
            val (relPath, _) = ArchivePaths.parseFilePath(path) ?: return@forEach
            val failure = runCatching { RoomArchiveFamilies.checkRows(relPath, content) }.exceptionOrNull()
                ?: return@forEach
            return "$path 解不开：${failure.message ?: failure::class.simpleName}"
        }
        return null
    }

    /** 标记：第一行是身份，其余是这一次落地的计数（给人看的，不是判据）。 */
    private fun writeMarker(marker: File, identity: String, result: ImportResult.Ok) {
        marker.parentFile?.mkdirs()
        marker.writeText(
            buildString {
                appendLine(identity)
                appendLine("keysWritten=${result.keysWritten}")
                appendLine("keysKept=${result.keysKept}")
                appendLine("filesWritten=${result.filesWritten}")
            },
        )
    }
}

/** 一次导入的结论。 */
sealed interface ArchiveImportOutcome {

    /** 真的导了：写了几笔见 [result]。 */
    data class Imported(val archive: File, val identity: String, val result: ImportResult.Ok) : ArchiveImportOutcome

    /** 这份档案的身份与标记一致 ⇒ 什么都不做（§6.1 第 1 条：导入幂等）。 */
    data class AlreadyImported(val archive: File, val identity: String) : ArchiveImportOutcome

    /** 明确失败，端内**零改动**（原因逐字来自自校验 / 行数组预检 / [StoreArchive.import]）。 */
    data class Failed(val reason: String) : ArchiveImportOutcome
}
