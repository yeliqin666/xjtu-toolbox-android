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

/**
 * 桌面端的**档案宿主装配**（S5）：数据根扫描 + 一次性导入的闸门 + 导出到目录/zip。
 *
 * 这不是缝、也不是共享逻辑，而是宿主签名（plan §1.4 的「数据袋」在桌面侧的落点）：
 * 它只**读**端内已有的东西（`archiveStoreBackend()` 的 store 投影、`archiveFileBackend()` 的
 * `agent_sessions<suffix>/`、`AccountStore()` 的账号表），不新建任何端内文件格式（C1 红线）。
 * 纯函数部分（[accountsFrom] / [readStores] / [readFiles] / [buildArchive] / [checkArchive]）
 * 刻意拆出来，供 `:desktop:test` 用临时数据根（`dataRootOverride` + `keyValueStoreRootOverride`）驱动。
 *
 * ## 数据根（§1.2 / A13）
 *
 * 数据根 = [archiveDataRoot]（jvm = `~/.local/share/xjtu-toolbox`，与 `:data` 的 `dataFilesDir()`
 * 同值 —— S2 的测试钉着这一条）。档案落在数据根下的 [ARCHIVE_DIR] 目录（目录形态，可整拷）
 * 与 `<数据根>/archive.zip`（运输形态，§2.0）。
 *
 * ## 一次性导入：本宿主只做**入口 + 自校验**（与 `:app` 的 S4、web 的 S6 同一处缺口）
 *
 * §6.2 的完整链路是「有档案才跑 → 已导入标记（端内私有项）→ staging 落地 + 自校验 → 逐 store 合并
 * → 任一步失败则端内零变化」。其中**逐 store 合并**（§6.4）在 S1b 已经落地到 `:core`
 * （`StoreArchive.import` + 写侧窄口 `ArchiveImportTarget`），但**这一端还没把写侧接上**
 *（与 `:app` 的 S4 一样：那里也停在自校验，改成真合并要单独一次授权）。所以本宿主：
 *
 *  - [pendingArchives] —— 扫数据根（§6.2 桌面那一行的触发点），有档案才继续；
 *  - [checkArchive] —— 自校验：调 `:core` 的 `StoreArchive.inspect`（S1b 收口），只补本端的
 *    「档案路径 → 数据根文件」那一列（A13）；
 *  - **不写任何端内数据**（连"已导入标记"都不写 —— 没有合并就没有"已导入"可言）。
 *
 * 于是「坏档案不动端」（A7）在这一端是**结构性成立**的：这个方法跑完，端内键集合与值逐字未变。
 * 接上写侧之后，这条性质改由 `StoreArchive.import` 的「先全量校验再落地」保住。
 */
object DesktopArchiveHost {

    /** 数据根下的档案落地目录（目录即档案本体，§2.0）。 */
    const val ARCHIVE_DIR = "archive"

    /** 目录形态旁边的运输形态：`<数据根>/archive.zip`。 */
    const val ARCHIVE_ZIP_NAME = "archive.zip"

    private const val TAG = "DesktopArchive"

    /** 两个匿名档：未登录 / 登出中间态在档案里也占目录（§4），与 `StoreArchive.export` 同口径。 */
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

    /** 读端内 store 投影（S2 缝 A）：全部 store 名 → 快照。 */
    fun readStores(backend: ArchiveStoreBackend): List<StoreSnapshot> =
        backend.storeNames().mapNotNull { backend.readStore(it) }

    /**
     * 读端内 `files` 那一层（S3 缝 B）：桌面只有 `agent_sessions<suffix>/` 一族
     * （课表族在 KV 店、Room 三表在这一端不存在 ⇒ 它们走 store 投影，§2.3）。
     */
    fun readFiles(backend: ArchiveFileBackend?, suffixes: List<String>): List<ArchiveFile> {
        val fb = backend ?: return emptyList()
        return suffixes.flatMap { suffix ->
            fb.list(suffix, "agent_sessions").mapNotNull { rel ->
                fb.read(suffix, rel)?.let { ArchiveFile(suffix, rel, it) }
            }
        }
    }

    /**
     * 纯组装：已投影的 stores + files + accounts → [StoreArchive.export] 的档案字节映射。
     * 这是「导出动作」的核心，也是 A3（注入固定时钟两次导出字节相同）在桌面侧的入口。
     */
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

    /**
     * 导出到数据根：目录形态（`<数据根>/archive/`，可整拷）+ zip 形态
     * （`<数据根>/archive.zip`，给另一台机器 / 给 Android）。两份产物来自**同一个**字节映射。
     */
    fun exportToDataRoot(now: () -> Long = { Clock.System.now().toEpochMilliseconds() }): ExportOutcome {
        val archive = exportArchive(now)
        val directory = archiveRoot()
        DesktopArchiveIo.writeDirectory(archive, directory)
        val zip = archiveZip()
        DesktopArchiveIo.writeFile(zip, DesktopArchiveIo.zip(archive))
        return ExportOutcome(directory, zip, archive.size)
    }

    // ── 数据根扫描 + 一次性导入的闸门（§6.2） ───────────────────────────────

    /**
     * 数据根下的待导入档案：`<数据根>/archive/`（非空的目录形态）与数据根下的 `*.zip`
     * （§6.2 桌面那一行的「有档案目录/zip」）。按路径升序，稳定。
     */
    fun pendingArchives(): List<File> {
        val root = dataRoot()
        val dir = File(root, ARCHIVE_DIR)
        val fromDirectory = if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) listOf(dir) else emptyList()
        val fromZip = root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(DesktopArchiveIo.ZIP_SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()
        return fromDirectory + fromZip
    }

    /**
     * §6.2 第 1 条 + 第 3 条的前半：有档案才跑（没有就静默返回 `null`），有则自校验。
     * 无论结果如何**都不写端内数据**（合并见类 KDoc 的缺口说明）。
     */
    fun maybeImportPendingArchive(): PendingArchive? {
        val path = pendingArchives().firstOrNull() ?: return null
        val check = checkArchive(path)
        when (check) {
            is ArchiveCheck.Ok ->
                Log.i(TAG, "发现待导入档案 ${path.name}：format=${check.format} v${check.version}，" +
                    "${check.stores.size} 个 store / ${check.files} 个文件；写侧还没接上 StoreArchive.import（S1b 已落地 `:core`）")
            is ArchiveCheck.Failed ->
                Log.w(TAG, "待导入档案 ${path.name} 不可用：${check.reason}（端内零变化）")
        }
        return PendingArchive(path, check)
    }

    /**
     * 自校验一份档案（§6.2 第 3 条的前半，纯读）：`manifest.json` 在不在、`format` 对不对、
     * `version` 认不认识（不认识就**明确失败**，A7）、每个 `stores/` 下的 `.json` 解不解得开、
     * 账号后缀有没有碰撞（§4 / A9）。
     *
     * S1b：这十条判定**已经收到 `:core`**（`StoreArchive.inspect`）—— 这里只剩「目录/zip 解成字节
     * 映射」（[DesktopArchiveIo.read]）与「档案路径 → 数据根文件」这条**本端**的映射（A13）。
     */
    fun checkArchive(path: File): ArchiveCheck {
        val entries = runCatching { DesktopArchiveIo.read(path) }.getOrElse {
            return ArchiveCheck.Failed("读不出来：${it.message ?: it::class.simpleName}")
        }
        return when (val inspection = StoreArchive.inspect(archiveSourceOf(entries))) {
            is ArchiveInspection.Failed -> ArchiveCheck.Failed(inspection.reason)
            is ArchiveInspection.Ok -> {
                val root = dataRoot()
                ArchiveCheck.Ok(
                    format = inspection.manifest.format,
                    version = inspection.manifest.version,
                    stores = inspection.stores,
                    files = inspection.files,
                    // A13：档案里每个 store 映射到数据根下的哪个文件（纯字符串拼接）—— 只作「会落到哪儿」
                    // 的清单，落地由导入那一半做。`files/` 那一层不在这里（见 DesktopArchiveIo.dataRootTarget）。
                    targets = inspection.stores.mapNotNull { DesktopArchiveIo.dataRootTarget(root, it) },
                )
            }
        }
    }
}

/** 导出动作的结果（目录形态 + zip 形态 + 条目数）。 */
data class ExportOutcome(val directory: File, val zip: File, val entries: Int)

/** 数据根里发现的一份待导入档案 + 它的自校验结果。 */
data class PendingArchive(val path: File, val check: ArchiveCheck)

/**
 * 一份档案的自校验结果（§6.2 第 3 条的前半）。
 *
 * S1b：判定已经收到 `:core`（`StoreArchive.inspect`），这里是它的本端映射 —— 多出 `targets`
 * 那一列（A13 的「档案路径 → 数据根文件」只属于桌面）。
 */
sealed interface ArchiveCheck {

    /** 自校验通过。 */
    data class Ok(
        val format: String,
        val version: Int,
        /** 档案里 `stores/` 那一层的相对路径（码点升序）。 */
        val stores: List<String>,
        /** `files/` 那一层里的文件数。 */
        val files: Int,
        /** 每个 store 的**档案路径**映射到的数据根文件（A13；只列，不落地）。 */
        val targets: List<File>,
    ) : ArchiveCheck

    /** 明确失败（`manifest` 缺 / `format` 不对 / `version` 不认识 / JSON 解不开 / 后缀碰撞）。 */
    data class Failed(val reason: String) : ArchiveCheck
}
