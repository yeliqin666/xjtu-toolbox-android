package com.xjtu.toolbox.desktop.archive

import com.xjtu.toolbox.archive.ArchiveCodec
import com.xjtu.toolbox.archive.ArchiveFileBackend
import com.xjtu.toolbox.archive.ArchiveImportTarget
import com.xjtu.toolbox.archive.ArchiveInspection
import com.xjtu.toolbox.archive.ArchiveManifest
import com.xjtu.toolbox.archive.ArchiveSource
import com.xjtu.toolbox.archive.ArchiveStoreBackend
import com.xjtu.toolbox.archive.ImportResult
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.StoreValue
import com.xjtu.toolbox.archive.archiveFileBackend
import com.xjtu.toolbox.archive.archiveSourceOf
import com.xjtu.toolbox.archive.archiveStoreBackend
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.platform.secureKeyValueStore
import java.io.File

/** 桌面端的导入写侧：`:core` 的 [ArchiveImportTarget] 在 jvm 上的落点。 */
class DesktopArchiveImportTarget(
    /** 写侧：按端内名取那个已有句柄（默认 [secureKeyValueStore]）。 */
    private val storeOf: (String) -> KeyValueStore = { secureKeyValueStore(it) },
    /** 读侧现状（`<数据根>/<name>.properties`）。 */
    private val storeBackend: ArchiveStoreBackend = archiveStoreBackend(),
    /** `files/` 那一层的读侧现状。 */
    private val files: ArchiveFileBackend? = archiveFileBackend(),
    /** 数据根（`<数据根>/agent_sessions<suffix>/…` 的父目录）。 */
    private val dataRoot: File? = archiveDataRootOrNull(),
) : ArchiveImportTarget {

    override fun readStore(rawName: String): StoreSnapshot? = storeBackend.readStore(rawName)

    override fun putKey(rawName: String, key: String, value: StoreValue, at: Long?) {
        val store = storeOf(rawName)
        when (value) {
            is StoreValue.S -> store.putString(key, value.v)
            is StoreValue.B -> store.putBoolean(key, value.v)
            is StoreValue.I -> store.putInt(key, value.v)
            is StoreValue.L -> store.putString(key, value.v.toString())
            is StoreValue.F -> store.putString(key, value.v.toString())
            is StoreValue.SetValue -> store.putStringSet(key, value.v.toSet())
        }
        // §5.2 第 5 条：`at` 落在旁挂键上（档案里那份时间戳就是从这里来的）
        if (at != null) store.putString(key + ArchiveCodec.WRITTEN_AT_SUFFIX, at.toString())
    }

    override fun readFile(suffix: String, relPath: String): String? = files?.read(suffix, relPath)

    override fun putFile(suffix: String, relPath: String, content: String) {
        val root = dataRoot ?: return
        if (!relPath.startsWith(AGENT_SESSIONS_PREFIX)) return
        val target = File(File(root, "agent_sessions$suffix"), relPath.removePrefix(AGENT_SESSIONS_PREFIX))
        DesktopArchiveIo.writeFile(target, content.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        /** §2.3：桌面 `files/` 这一层只有这一族（课表族在 KV 店）。 */
        const val AGENT_SESSIONS_PREFIX = "agent_sessions/"
    }
}

/** 桌面端的一次性导入编排：自校验 → §6.4 合并 → 落「已导入」标记。 */
object DesktopArchiveImport {

    /** 标记文件名（档案目录里的一份点文件；[DesktopArchiveIo.readDirectory] 读档案时会跳过点文件）。 */
    const val MARKER_NAME = ".imported"

    private const val MARKER_SUFFIX = ".imported"
    private const val TAG = "DesktopArchive"

    /** 那一份档案的标记文件（目录形态写进目录里，zip 形态写它旁边）。 */
    fun markerFileFor(archive: File): File =
        if (archive.isDirectory) {
            File(archive, MARKER_NAME)
        } else {
            File(archive.parentFile, archive.name + MARKER_SUFFIX)
        }

    /** 档案的身份（§2.1）。 */
    fun identityOf(manifest: ArchiveManifest): String =
        "${manifest.createdAt}|${manifest.createdBy}|${manifest.version}"

    /** 标记里记着的身份（第一行）；没有标记 / 读不出来 ⇒ null。 */
    fun readMarkerIdentity(marker: File): String? =
        if (!marker.isFile) null
        else runCatching { marker.readText().lineSequence().firstOrNull() }.getOrNull()

    /** 跑一次导入（自校验 → §6.4 合并 → 标记）。 */
    fun run(
        archive: File,
        entries: Map<String, String>,
        target: ArchiveImportTarget = DesktopArchiveImportTarget(),
    ): DesktopImportOutcome = run(archive, archiveSourceOf(entries), target)

    /** 同上，但调用方已经有一个 [ArchiveSource]（[DesktopArchiveHost.checkArchive] 走的就是这一条）。 */
    fun run(
        archive: File,
        source: ArchiveSource,
        target: ArchiveImportTarget,
    ): DesktopImportOutcome {
        val validated = StoreArchive.inspect(source)
        if (validated is ArchiveInspection.Failed) return DesktopImportOutcome.Failed(validated.reason)
        val manifest = (validated as ArchiveInspection.Ok).manifest

        val marker = markerFileFor(archive)
        val identity = identityOf(manifest)
        if (readMarkerIdentity(marker) == identity) {
            return DesktopImportOutcome.AlreadyImported(archive, identity)
        }

        return when (val result = StoreArchive.import(source, target)) {
            is ImportResult.Failed -> DesktopImportOutcome.Failed(result.reason)
            is ImportResult.Ok -> {
                writeMarker(marker, identity, result)
                Log.i(
                    TAG,
                    "导入 ${archive.name}：:core 的 StoreArchive.import 写入 ${result.keysWritten} 个键 /" +
                        " 保持 ${result.keysKept} 个 / 写回 ${result.filesWritten} 个文件",
                )
                DesktopImportOutcome.Imported(archive, identity, result)
            }
        }
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
sealed interface DesktopImportOutcome {

    /** 真的导了：写了几笔见 [result]。 */
    data class Imported(val archive: File, val identity: String, val result: ImportResult.Ok) : DesktopImportOutcome

    /** 这份档案的身份与标记一致 ⇒ 什么都不做（§6.1 第 1 条：导入幂等）。 */
    data class AlreadyImported(val archive: File, val identity: String) : DesktopImportOutcome

    /** 明确失败，端内零改动。 */
    data class Failed(val reason: String) : DesktopImportOutcome
}

/** 数据根（这一端恒非 null；包一层 runCatching 只是为了不在测试环境里炸）。 */
private fun archiveDataRootOrNull(): File? = runCatching { File(DesktopArchiveHost.dataRoot().path) }.getOrNull()

