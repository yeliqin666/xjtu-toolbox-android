package com.xjtu.toolbox.desktop.archive

import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.archive.ArchiveAccount
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.ArchiveInspection
import com.xjtu.toolbox.archive.ArchiveFile
import com.xjtu.toolbox.archive.ArchiveNames
import com.xjtu.toolbox.archive.ArchiveStoreBackend
import com.xjtu.toolbox.archive.ArchiveFileBackend
import com.xjtu.toolbox.archive.ExportOptions
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.archive.archiveSourceOf
import com.xjtu.toolbox.archive.archiveDataRoot
import com.xjtu.toolbox.archive.archiveFileBackend
import com.xjtu.toolbox.archive.archiveStoreBackend
import com.xjtu.toolbox.desktop.DesktopBuildConfig
import com.xjtu.toolbox.platform.Log
import java.io.File
import kotlin.time.Clock

/** 桌面端的档案宿主装配：数据根扫描 + 一次性导入的闸门 + 导出到目录/zip。 */
object DesktopArchiveHost {

    /** 数据根下的档案落地目录（目录即档案本体，§2.0）。 */
    const val ARCHIVE_DIR = "archive"

    /** 目录形态旁边的运输形态：`<数据根>/archive.zip`。 */
    const val ARCHIVE_ZIP_NAME = "archive.zip"

    private const val TAG = "DesktopArchive"

    /** 两个匿名档：未登录 / 登出中间态在档案里也占目录（§4）。 */
    private val ANONYMOUS_SUFFIXES = listOf("default", "_")

    /** §2.1 `createdBy`：`desktop-linux` / `desktop-windows`。 */
    val createdBy: String
        get() = if ((System.getProperty("os.name") ?: "").lowercase().contains("win")) {
            "desktop-windows"
        } else {
            "desktop-linux"
        }

    /** §2.1 `appVersion`：与 `DesktopSettingsHost.versionText` 同一个字面（桌面包的版本 + `(desktop)`）。 */
    val appVersion: String get() = "v${DesktopBuildConfig.VERSION_NAME} (desktop)"

    /** 数据根（`archiveDataRoot()`；jvm 上恒非 null）。 */
    fun dataRoot(): File = File(archiveDataRoot() ?: error("这一端没有数据根"))

    /** 档案落地目录：`<数据根>/archive/`。 */
    fun archiveRoot(): File = File(dataRoot(), ARCHIVE_DIR)

    /** 运输形态：`<数据根>/archive.zip`。 */
    fun archiveZip(): File = File(dataRoot(), ARCHIVE_ZIP_NAME)

    // ── 装配（只读端内） ────────────────────────────────────────────────────

    /** `manifest.accounts[]`（§2.1）的纯映射：账号表 → [ArchiveAccount]。 */
    fun accountsFrom(accounts: List<Account>): List<ArchiveAccount> = accounts.map { a ->
        ArchiveAccount(
            accountId = a.accountId,
            suffix = ArchiveNames.suffixFor(a.accountId),
            accountType = a.accountType.name,
            lastUsedAt = a.lastUsedAt,
        )
    }

    /** 读端内 store 投影：全部 store 名 → 快照。 */
    fun readStores(backend: ArchiveStoreBackend): List<StoreSnapshot> =
        backend.storeNames().mapNotNull { backend.readStore(it) }

    /** 读端内 `files` 那一层（缝 B） */
    fun readFiles(backend: ArchiveFileBackend?, suffixes: List<String>): List<ArchiveFile> {
        val fb = backend ?: return emptyList()
        return suffixes.flatMap { suffix ->
            fb.list(suffix, "agent_sessions").mapNotNull { rel ->
                fb.read(suffix, rel)?.let { ArchiveFile(suffix, rel, it) }
            }
        }
    }

    /** 纯组装：已投影的 stores + files + accounts → [StoreArchive.export] 的档案字节映射。 */
    fun buildArchive(
        accounts: List<ArchiveAccount>,
        stores: List<StoreSnapshot>,
        files: List<ArchiveFile>,
        now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): Map<String, String> =
        StoreArchive.export(accounts, createdBy, appVersion, stores, files, ExportOptions(now = now))

    /** 端内 → 完整档案字节映射（导出动作；只读端内，不写任何 store / 文件）。 */
    fun exportArchive(now: () -> Long = { Clock.System.now().toEpochMilliseconds() }): Map<String, String> {
        val accounts = accountsFrom(AccountStore().list())
        val suffixes = (accounts.map { it.suffix } + ANONYMOUS_SUFFIXES).distinct()
        return buildArchive(
            accounts = accounts,
            stores = readStores(archiveStoreBackend()),
            files = readFiles(archiveFileBackend(), suffixes),
            now = now,
        )
    }

    /** 导出到数据根：目录形态（`<数据根>/archive/`，可整拷）+ zip 形态（`<数据根>/archive.zip`，给另一台机器 / 给 Android）。 */
    fun exportToDataRoot(now: () -> Long = { Clock.System.now().toEpochMilliseconds() }): ExportOutcome {
        val archive = exportArchive(now)
        val directory = archiveRoot()
        DesktopArchiveIo.writeDirectory(archive, directory)
        val zip = archiveZip()
        DesktopArchiveIo.writeFile(zip, DesktopArchiveIo.zip(archive))
        return ExportOutcome(directory, zip, archive.size)
    }

    // ── 数据根扫描 + 一次性导入的闸门（§6.2） ───────────────────────────────

    /** 数据根下的待导入档案：`<数据根>/archive/`（里面真有 `manifest.json` 的目录形态）与数据根下的`*.zip`（§6.2 桌面那一行的「有档案目录/zip」）。 */
    fun pendingArchives(): List<File> {
        val root = dataRoot()
        val dir = File(root, ARCHIVE_DIR)
        val fromDirectory = if (dir.isDirectory && File(dir, "manifest.json").isFile) listOf(dir) else emptyList()
        val fromZip = root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(DesktopArchiveIo.ZIP_SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()
        return fromDirectory + fromZip
    }

    /** §6.2 的触发点（`Main.kt` 启动时调一次，设置页那一行也走它）：有档案才跑，没有就静默返回 `null`。 */
    fun maybeImportPendingArchive(): PendingArchive? {
        val path = pendingArchives().firstOrNull() ?: return null
        val entries = runCatching { DesktopArchiveIo.read(path) }.getOrElse {
            Log.w(TAG, "待导入档案 ${path.name} 读不出来：${it.message ?: it::class.simpleName}（端内零变化）")
            val reason = "读不出来：${it.message ?: it::class.simpleName}"
            return PendingArchive(path, ArchiveCheck.Failed(reason), DesktopImportOutcome.Failed(reason))
        }
        val check = checkArchive(entries)
        val outcome = when (check) {
            is ArchiveCheck.Failed -> {
                Log.w(TAG, "待导入档案 ${path.name} 不可用：${check.reason}（端内零变化）")
                DesktopImportOutcome.Failed(check.reason)
            }
            is ArchiveCheck.Ok -> DesktopArchiveImport.run(path, entries)
        }
        return PendingArchive(path, check, outcome)
    }

    /** 自校验一份档案（§6.2 第 3 条的前半，纯读） */
    fun checkArchive(path: File): ArchiveCheck {
        val entries = runCatching { DesktopArchiveIo.read(path) }.getOrElse {
            return ArchiveCheck.Failed("读不出来：${it.message ?: it::class.simpleName}")
        }
        return checkArchive(entries)
    }

    /** 同上，但调用方已经把目录/zip 解好了（[maybeImportPendingArchive] 只读一次档案）。 */
    fun checkArchive(entries: Map<String, String>): ArchiveCheck {
        return when (val inspection = StoreArchive.inspect(archiveSourceOf(entries))) {
            is ArchiveInspection.Failed -> ArchiveCheck.Failed(inspection.reason)
            is ArchiveInspection.Ok -> {
                val root = dataRoot()
                ArchiveCheck.Ok(
                    format = inspection.manifest.format,
                    version = inspection.manifest.version,
                    stores = inspection.stores,
                    files = inspection.files,
                    targets = inspection.stores.mapNotNull { DesktopArchiveIo.dataRootTarget(root, it) },
                )
            }
        }
    }
}

/** 导出动作的结果（目录形态 + zip 形态 + 条目数）。 */
data class ExportOutcome(val directory: File, val zip: File, val entries: Int)

/** 数据根里发现的一份待导入档案 + 它的自校验结果 + 这一次合并的结论（§6.4）。 */
data class PendingArchive(
    val path: File,
    val check: ArchiveCheck,
    val outcome: DesktopImportOutcome,
)

/** 一份档案的自校验结果（§6.2 第 3 条的前半）。 */
sealed interface ArchiveCheck {

    /** 自校验通过。 */
    data class Ok(
        val format: String,
        val version: Int,
        /** 档案里 `stores/` 那一层的相对路径（码点升序）。 */
        val stores: List<String>,
        /** `files/` 那一层里的文件数。 */
        val files: Int,
        /** 每个 store 的档案路径映射到的数据根文件（只列，不落地）。 */
        val targets: List<File>,
    ) : ArchiveCheck

    /** 明确失败（`manifest` 缺 / `format` 不对 / `version` 不认识 / JSON 解不开 / 后缀碰撞）。 */
    data class Failed(val reason: String) : ArchiveCheck
}
