package com.xjtu.toolbox.archive

import java.io.File

/** `:app` 侧的一次性导入编排：自校验 → 行数组预检 → §6.4 合并 → 落「已导入」标记。 */
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

    /** 跑一次导入。 */
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

    /** 明确失败，端内零改动。 */
    data class Failed(val reason: String) : ArchiveImportOutcome
}
