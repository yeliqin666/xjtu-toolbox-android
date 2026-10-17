package com.xjtu.toolbox.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.archive.ArchiveImportTarget
import com.xjtu.toolbox.archive.ArchiveInspection
import com.xjtu.toolbox.archive.ArchiveStoreBackend
import com.xjtu.toolbox.archive.ExportOptions
import com.xjtu.toolbox.archive.ImportResult
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.archiveSourceOf
import com.xjtu.toolbox.archive.archiveStoreBackend
import kotlin.time.Clock

/** Web 这一端的档案宿主装配。 */
internal object WebArchiveHost {

    /** §2.1 `createdBy`：本端固定写 `web`。 */
    const val CREATED_BY = "web"

    /** §2.1 `appVersion`：与 `WebSettingsHost.versionText` 同一个字面。 */
    const val APP_VERSION = "Web 构建（wasm）"

    /** 读端内 store 投影（`localStorage` 的 `xjtu-toolbox.` 前缀）。 */
    fun readStores(backend: ArchiveStoreBackend = archiveStoreBackend()): List<StoreSnapshot> =
        backend.storeNames().mapNotNull { backend.readStore(it) }

    /** 纯组装：已投影的 stores → [StoreArchive.export] 的档案字节映射。 */
    fun buildArchive(
        stores: List<StoreSnapshot>,
        now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): Map<String, String> =
        StoreArchive.export(
            accounts = emptyList(),
            createdBy = CREATED_BY,
            appVersion = APP_VERSION,
            stores = stores,
            files = emptyList(),
            options = ExportOptions(now = now),
        )

    /** 端内 → 完整档案字节映射（只读端内，不写任何 store）。 */
    fun exportArchive(now: () -> Long = { Clock.System.now().toEpochMilliseconds() }): Map<String, String> =
        buildArchive(readStores(), now)

    /** 导出：组装 + 交给浏览器下载，返回结论文本。 */
    fun exportToDownload(now: () -> Long = { Clock.System.now().toEpochMilliseconds() }): String {
        val archive = exportArchive(now)
        WebArchiveIo.download(archive)
        return "已导出 ${archive.size} 个条目（${archive.keys.count { it.contains("/stores/") }} 个 store），" +
            "浏览器把它存到默认下载位置"
    }

    /** 自校验一份已解出来的档案（§6.2 第 3 条的前半，纯读） */
    fun checkArchive(entries: Map<String, String>): WebArchiveCheck {
        return when (val inspection = StoreArchive.inspect(archiveSourceOf(entries))) {
            is ArchiveInspection.Failed -> WebArchiveCheck.Failed(inspection.reason)
            is ArchiveInspection.Ok -> WebArchiveCheck.Ok(
                version = inspection.manifest.version,
                stores = inspection.stores,
                files = inspection.files,
                redacted = inspection.manifest.redacted.size,
            )
        }
    }

    /** 导入那一行的结论文本（纯函数，供屏与「手工点一次」共用同一份措辞）。 */
    fun importStatusText(picked: PickedArchive): String = when (picked) {
        is PickedArchive.Failed -> "导入失败：${picked.reason}"
        is PickedArchive.Entries -> importAndReport(picked)
    }

    /** 真导 + 结论文本。 */
    fun importArchive(
        entries: Map<String, String>,
        target: ArchiveImportTarget = WebArchiveImportTarget(),
    ): ImportResult = StoreArchive.import(archiveSourceOf(withoutFilesLayer(entries)), target)

    private fun importAndReport(picked: PickedArchive.Entries): String {
        val skippedFiles = filesLayerCount(picked.entries)
        return when (val result = importArchive(picked.entries)) {
            is ImportResult.Failed -> "「${picked.fileName}」不可用：${result.reason}（本机数据未改动）"
            is ImportResult.Ok -> buildString {
                append("「${picked.fileName}」已导入：:core 的 StoreArchive.import 按 §6.4 写入 ")
                append(result.keysWritten)
                append(" 个键 / 保持 ")
                append(result.keysKept)
                append(" 个")
                if (skippedFiles > 0) {
                    append("；本端没有文件系统 ⇒ 档案里 $skippedFiles 个 files/ 条目未落地")
                }
            }
        }
    }
}

/** 一份档案自校验的结果。 */
internal sealed interface WebArchiveCheck {

    /** 通过。 */
    data class Ok(
        val version: Int,
        /** 档案里 `stores/` 那一层的相对路径（码点升序）。 */
        val stores: List<String>,
        /** `files/` 那一层里的文件数（web 上恒 0 —— 本端导出时不写 `files/`）。 */
        val files: Int,
        /** `manifest.redacted` 的条目数（凭据族没跟过来的那些，UI 只说数量）。 */
        val redacted: Int,
    ) : WebArchiveCheck

    /** 明确失败。 */
    data class Failed(val reason: String) : WebArchiveCheck
}

/** 设置页那两个归档入口动作 + 它的一行结果文本。 */
internal class WebArchiveActions {

    /** 上一次动作的结论（`null` = 还没点过）。 */
    var status: String? by mutableStateOf(null)
        private set

    /** 导出：组装（`localStorage` 投影 → 档案）→ 浏览器下载（同步，落在手势里）。 */
    fun export() {
        status = runCatching { WebArchiveHost.exportToDownload() }
            .getOrElse { "导出失败：${it.message ?: it::class.simpleName}" }
    }

    /** 导入：选 zip → 内存里解 → 自校验 → `StoreArchive.import`（§6.4）→ 结论文本。 */
    fun import() {
        WebArchiveIo.pick { picked -> status = WebArchiveHost.importStatusText(picked) }
    }
}
