package com.xjtu.toolbox.desktop.archive

import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.archive.ArchiveAccount
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.ArchiveInspection
import com.xjtu.toolbox.archive.ArchiveFile
import com.xjtu.toolbox.archive.ArchiveNames
import com.xjtu.toolbox.archive.ArchiveScoreReport
import com.xjtu.toolbox.archive.ArchiveStoreBackend
import com.xjtu.toolbox.archive.ArchiveFileBackend
import com.xjtu.toolbox.archive.ArchiveImportMode
import com.xjtu.toolbox.archive.ExportOptions
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.archive.archiveSourceOf
import com.xjtu.toolbox.archive.archiveDataRoot
import com.xjtu.toolbox.archive.archiveFileBackend
import com.xjtu.toolbox.archive.archiveStoreBackend
import com.xjtu.toolbox.desktop.DesktopBuildConfig
import com.xjtu.toolbox.desktop.DesktopScheduleHost
import com.xjtu.toolbox.desktop.score.DesktopScoreReportCache
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.keyValueStoreRoot
import com.xjtu.toolbox.platform.secureKeyValueStore
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

    /**
     * 桌面 `files/` 层里要扫的族（B-9）：会话记录 + 课表缓存族。
     * 三族行数组与成绩缓存族是另外两条路（[readRowFiles] / [readScoreFiles]），不在这里。
     */
    private val FILES_PREFIXES = listOf("agent_sessions", "schedule")

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

    /**
     * 读端内 `files` 那一层（缝 B）：`agent_sessions<suffix>/` 与课表缓存族 `schedule/` 下的 ` *.json`
     * （B-9，桌面那一族的真身是键值店，`JvmFileBackend` 直接读 `schedule_cache<suffix>.properties`）；
     * 三族行数组见 [readRowFiles]，成绩缓存族见 [readScoreFiles]。
     */
    fun readFiles(backend: ArchiveFileBackend?, suffixes: List<String>): List<ArchiveFile> {
        val fb = backend ?: return emptyList()
        return suffixes.flatMap { suffix ->
            FILES_PREFIXES.flatMap { prefix ->
                fb.list(suffix, prefix).mapNotNull { rel ->
                    fb.read(suffix, rel)?.let { ArchiveFile(suffix, rel, it) }
                }
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

    /**
     * 端内 → 完整档案字节映射（导出动作；只读端内，不写任何 store / 文件）。
     *
     * §6.3 逐族迁移（「成绩缓存」与「课表缓存」两族）：这两族端内都是键值店、档案里都是 `files/` 下的文件
     * （`schedule/score_report.json`、`schedule/<key>.json`）⇒ **单一表示**：这两族的店**不进 `stores/`**
     * （否则同一份缓存会在档案里出现两遍，Android 那边还会把 `schedule_cache` 当 prefs 店落下来）。
     * 族名按 base 认，用的就是 [StoreArchive.export] 自己的拆分口径（[ArchiveNames.split]）。
     *
     * 代价（如实记一笔）：文件层没有逐键 `at`，课表缓存族在档案里的年龄就只剩「导入时刻」
     * （见 [DesktopArchiveImport]）—— 这一族是 `rebuildable` 缓存，年龄丢了只是「重拉一次」，不丢用户数据。
     */
    fun exportArchive(
        now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        storeOf: (String) -> KeyValueStore = { secureKeyValueStore(it) },
        accountId: String? = AccountContext.activeAccountId,
    ): Map<String, String> {
        val accounts = accountsFrom(AccountStore().list())
        val suffixes = (accounts.map { it.suffix } + ANONYMOUS_SUFFIXES).distinct()
        val stores = readStores(archiveStoreBackend())
        // 以文件为规范表示的两族（成绩缓存 / 课表缓存）：端内那份店不进 `stores/`（理由见上面 KDoc）。
        val fileFamilyBases = setOf(
            DesktopScoreReportCache.STORE_NAME,
            DesktopScheduleHost.SCHEDULE_CACHE_STORE,
        )
        val fileFamilyStores = ArchiveNames.split(stores.map { it.rawName }, suffixes)
            .filter { it.base in fileFamilyBases }
            .map { it.raw }
            .toSet()
        return buildArchive(
            accounts = accounts,
            stores = stores.filterNot { it.rawName in fileFamilyStores },
            files = readFiles(archiveFileBackend(), suffixes) + readRowFiles(storeOf, accountId) +
                readScoreFiles(accounts.map { it.accountId }, now = now),
            now = now,
        )
    }

    /** 三族行数组（桌面上是键值店），写成与 Android 同形的行数组；没有行的族不写。 */
    fun readRowFiles(
        storeOf: (String) -> KeyValueStore = { secureKeyValueStore(it) },
        accountId: String? = AccountContext.activeAccountId,
    ): List<ArchiveFile> = DesktopArchiveRows.filesOf(storeOf, accountId)

    /**
     * §6.3 逐族迁移（「成绩缓存」那一族）：端内是键值店 `score_report<账号后缀>` 的 `score_report_<学号>`
     * （[DesktopScoreReportCache]），档案里是**文件** `schedule/score_report.json`，值是 `{"at":<ms>,"grades":[…]}`，
     * 名字里不带学号（账号维度由 `accounts/<suffix>/` 承担）。
     *
     * 读不到这一族的缓存 ⇒ 不出这一条。旧端内形状（裸数组）用那份 properties 的 mtime 补 `at`（§6.3「mtime → at」）；
     * 连 mtime 也没有 ⇒ 用导出时刻。转不动的那份跳过（导出不报错，§8.6）。
     */
    fun readScoreFiles(
        accountIds: List<String>,
        now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        storeOf: (String) -> KeyValueStore = { keyValueStore(it) },
    ): List<ArchiveFile> = accountIds.mapNotNull { id ->
        val rawName = DesktopScoreReportCache.storeName(id)
        val key = DesktopScoreReportCache.keyFor(id)
        val legacyName = DesktopScoreReportCache.STORE_NAME
        val raw = runCatching { storeOf(rawName).getString(key) }.getOrNull()
            ?: runCatching { storeOf(legacyName).getString(key) }.getOrNull()
            ?: return@mapNotNull null
        val at = scoreStoreMtime(rawName) ?: scoreStoreMtime(legacyName) ?: now()
        ArchiveScoreReport.toCanonical(raw, at)?.let {
            ArchiveFile(ArchiveNames.suffixFor(id), ArchiveScoreReport.CANONICAL_REL, it)
        }
    }

    /** 端内那份店的落盘文件的 mtime（没有这一份 / 取不到 ⇒ null）。 */
    private fun scoreStoreMtime(rawName: String): Long? = runCatching {
        File(keyValueStoreRoot(), "$rawName.properties").takeIf { it.isFile }?.lastModified()
    }.getOrNull()

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

    /**
     * 第一步：找到待导入档案并**只算差异**（端内零改动，§6.4「显式镜像档」）。
     *
     * 设置页拿 [PendingArchivePlan] 弹窗（话术全用 [com.xjtu.toolbox.archive.ArchiveImportPrompt]），
     * 用户选完档位、点了确认之后调 [confirmImport] 落地。与 [maybeImportPendingArchive] 的区别只是多了一步
     * “先看后写”：不弹窗的老路径（`Main.kt` 启动钩子）照旧直接合并。
     */
    fun planPendingArchive(mode: ArchiveImportMode = ArchiveImportMode.MERGE): PendingArchivePlan? {
        val path = pendingArchives().firstOrNull() ?: return null
        val entries = runCatching { DesktopArchiveIo.read(path) }.getOrElse {
            Log.w(TAG, "待导入档案 ${path.name} 读不出来：${it.message ?: it::class.simpleName}（端内零变化）")
            val reason = "读不出来：${it.message ?: it::class.simpleName}"
            return PendingArchivePlan(path, ArchiveCheck.Failed(reason), DesktopArchivePlanOutcome.Failed(reason))
        }
        return when (val check = checkArchive(entries)) {
            is ArchiveCheck.Failed -> PendingArchivePlan(path, check, DesktopArchivePlanOutcome.Failed(check.reason))
            is ArchiveCheck.Ok -> PendingArchivePlan(
                path = path,
                check = check,
                plan = DesktopArchiveImport.plan(path, archiveSourceOf(entries), DesktopArchiveImportTarget(), mode),
            )
        }
    }

    /** 第二步：用户确认后才落地（镜像档中途失败已在共享层整体回滚）。 */
    fun confirmImport(planned: DesktopArchivePlanOutcome.Planned): DesktopImportOutcome =
        DesktopArchiveImport.apply(planned)

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

/** 数据根里发现的一份待导入档案 + 自校验结果 + **只有差异、还没落地**的结论（§6.4 镜像档）。 */
data class PendingArchivePlan(
    val path: File,
    val check: ArchiveCheck,
    val plan: DesktopArchivePlanOutcome,
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
