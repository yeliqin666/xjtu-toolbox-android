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

/**
 * 桌面端的**导入写侧**（S5 收尾）：`:core` 的 [ArchiveImportTarget] 在 jvm 上的落点。
 *
 * 合并语义一个字都不在这里 —— §6.4 的裁决（`at` 新者胜 / 任一方缺 `at` 本端胜 / 本端有档案没有 ⇒
 * 保持不变 / `set` 覆盖不并集 / `format`·`version` 不认识 ⇒ 明确失败）全在 [StoreArchive.import]。
 * 这一份只做它写下来的那几笔，按接口 KDoc 的三条「实现负责」：
 *
 *  1. **按声明类型落地**（§6.3 的桌面行）：Properties 只有字符串，`b`/`i` 走 [KeyValueStore] 的
 *     `putBoolean`/`putInt`，`l`/`f` 写成字符串（读回来由 `inferValue` 的
 *     `toLongOrNull`/`toFloatOrNull` 反推成同一档），`set` 走 `putStringSet`（`\u0000` join ——
 *     与 `SecureStore.jvm.kt` 同一套落盘形状，绝不能写成逗号串）；
 *  2. **旁挂时间戳**：[ArchiveImportTarget.putKey] 的 `at` 非空时同时写
 *     `<key>#written_at`（§5.2 第 5 条）—— 不写的话，导进来的那一份下次就被判成「没有年龄」，
 *     再导入一份更新的档案也永远赢不了（A10 的同一个键，反方向）；
 *  3. **原子写**（§8.8）：`files/` 那一层走 [DesktopArchiveIo.writeFile]（先 `.tmp` 再 `ATOMIC_MOVE`）。
 *
 * ## 写哪个句柄（`plain` 还是 `secure`）
 *
 * 落点逐字照 §1.2：`<数据根>/<端内名>.properties` —— `keyValueStore` 与 `secureKeyValueStore`
 * **指向同一个文件**（`KeyValueStore.jvm.kt` 与 `SecureStore.jvm.kt` 的数据根是同一个 XDG 字面量），
 * 所以选哪一个都不改变字节落点。这里一律走 [secureKeyValueStore]，理由两条：桌面把整个数据根都当私有
 * （设置 / 课表 / 下载 / 教材 / agent 全走它，见 `DesktopSettings` 那一段 KDoc），且它覆盖了端上
 * 绝大多数 store。导入是**启动时**的一次性动作（`Main.kt`，早于任何屏建句柄）⇒ 另一族的句柄
 * 之后第一次用时从磁盘读到的是导入后的文件。
 *
 * ## 只写 `agent_sessions<suffix>/` 这一族文件
 *
 * §2.3 里桌面的 `files/` 层只有 `agent_sessions<suffix>/`（S3 的读侧 `JvmFileBackend` 就只枚举这一族），
 * 课表族在 KV 店（store 投影）。Android 那三族行数组（`files/courses` / `files/media` / `files/jiaocai`）
 * 在桌面上是**另外几个动作的 KV 店**（`KeyValueCustomCourseStore` / `DesktopDownloadTaskStore` /
 * `DesktopJiaocai1Storage`），形状不是行数组 —— §6.3 那条「拆成行数组」的转换是另一件事，
 * 这里**不假装**做了：不是这两族的 `files/` 条目一律不落地。
 */
class DesktopArchiveImportTarget(
    /** 写侧：按**端内名逐字**取那个已有句柄（默认 [secureKeyValueStore]）。 */
    private val storeOf: (String) -> KeyValueStore = { secureKeyValueStore(it) },
    /** 读侧现状（S2 的缝 A：`<数据根>/<name>.properties`）。 */
    private val storeBackend: ArchiveStoreBackend = archiveStoreBackend(),
    /** `files/` 那一层的读侧现状（S3 的缝 B）。 */
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

/**
 * 桌面端的一次性导入**编排**（S5 收尾）：自校验 → §6.4 合并 → 落「已导入」标记。
 *
 * 与 `:app` 侧（`AppArchiveImport`）是同一条顺序、同一条标记规则，理由也一样（§6.1 第 3 条）：
 * 自校验在写第一笔之前返回 ⇒ 坏档案端内零改动（A7）。
 *
 * ## 「已导入」标记的落点（§8.7：不写进 `data_cache*`；这里也没有 prefs 可写）
 *
 * 标记是**档案落地目录里的一个文件**，跟着那份档案走：
 *  - 目录形态（`<数据根>/archive/`）⇒ `<数据根>/archive/.imported`；
 *  - zip 形态（`<数据根>/archive.zip`）⇒ `<数据根>/archive.zip.imported`（旁挂一个同名文件）。
 *
 * 第一行是那份档案的身份（`createdAt` + `createdBy` + `version`）⇒ 同一份档案再导一次只跳过
 * （§6.1 第 1 条的幂等）；其余几行是这次落地的计数。
 */
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

    /** 明确失败，端内**零改动**（原因逐字来自自校验 / [StoreArchive.import]）。 */
    data class Failed(val reason: String) : DesktopImportOutcome
}

/** 数据根（这一端恒非 null；包一层 runCatching 只是为了不在测试环境里炸）。 */
private fun archiveDataRootOrNull(): File? = runCatching { File(DesktopArchiveHost.dataRoot().path) }.getOrNull()

