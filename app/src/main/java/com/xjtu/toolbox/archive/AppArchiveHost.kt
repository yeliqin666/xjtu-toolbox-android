package com.xjtu.toolbox.archive

import android.content.Context
import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.data.AppDatabase
import java.io.File
import kotlin.time.Clock

/**
 * `:app` 侧的**档案宿主装配**（S4 接线点）：把端内的 store / Room 三表 / `files` 层投影
 * 组装成一份档案字节映射，交给 `:core` 的 [StoreArchive.export]（纯函数）。
 *
 * 这一份不是缝、也不是共享逻辑，而是宿主签名（plan §1.4 的「数据袋」在 Android 侧的落点）：
 * 它只**读**端内已有的东西（`archiveStoreBackend()` / DAO / `archiveFileBackend()` / 账号表），
 * 不新建任何端内文件格式（§8.1），不写任何 store / Room 行（§8.3）。
 *
 * 纯函数部分（[accountsFrom] / [readStores] / [assemble] / [writeToDirectory]）刻意拆出来，
 * 供 `:app:testDebugUnitTest` 用 `InMemorySharedPreferences` + 临时目录驱动断言。
 */
object AppArchiveHost {
    /** §1.1 未决 #3：档案落地目录 = `filesDir/archive/`（与 `dataFilesDir()` 同根，零新权限）。 */
    const val ARCHIVE_DIR = "archive"

    /** §6.1：SAF 选中的档案先落地 `filesDir/archive_staging/`，下次启动由导入触发点消费。 */
    const val STAGING_DIR = "archive_staging"

    fun archiveRoot(context: Context): File = File(context.filesDir, ARCHIVE_DIR)

    fun stagingRoot(context: Context): File = File(context.filesDir, STAGING_DIR)

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
     * 纯组装：把已投影的 stores + files + accounts 交给 [StoreArchive.export]。
     * 这是「导出动作」的核心，也是 A3（注入固定时钟两次导出字节相同）在 `:app` 侧的入口。
     */
    fun assemble(
        accounts: List<ArchiveAccount>,
        stores: List<StoreSnapshot>,
        files: List<ArchiveFile>,
        appVersion: String,
        createdBy: String = "android",
        now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): Map<String, String> =
        StoreArchive.export(accounts, createdBy, appVersion, stores, files, ExportOptions(now = now))

    /**
     * 端内 → 完整档案字节映射（导出动作；只读，不写端内任何数据）。
     *
     * `files` 层：Room 三表（§2.3 行数组）+ `agent_sessions` / `schedule` 两族（S3 缝 B，目录名不带
     * suffix）。空教室缓存族（`empty_room_cache`）在 S1 的 `ArchiveRegistry` 里是 KV 店承载的可重建
     * 缓存 ⇒ 走 store 投影（`stores/empty_room_cache.json`），不在 `files/` 这一层，也不在本工单的
     * 接线范围（见 plan §2 的 S4 条目：本工单只落 Room 三表 + files 缝 + 触发/动作/通道）。
     */
    suspend fun buildArchive(
        db: AppDatabase,
        backend: ArchiveStoreBackend,
        filesBackend: ArchiveFileBackend?,
        accounts: List<ArchiveAccount>,
        appVersion: String,
        now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): Map<String, String> {
        val stores = readStores(backend)
        val suffixes = (accounts.map { it.suffix } + listOf("default", "_")).distinct()
        val files = buildList {
            add(
                RoomArchiveFamilies.customCoursesFile(
                    RoomArchiveFamilies.readCustomCourses(db.customCourseDao(), accounts.map { it.accountId }),
                ),
            )
            add(RoomArchiveFamilies.downloadTasksFile(RoomArchiveFamilies.readDownloadTasks(db.downloadTaskDao())))
            add(RoomArchiveFamilies.jiaocaiShelfFile(RoomArchiveFamilies.readJiaocaiShelf(db.jiaocai1ShelfDao())))
            filesBackend?.let { fb ->
                suffixes.forEach { suffix ->
                    fb.list(suffix, "agent_sessions").forEach { rel ->
                        fb.read(suffix, rel)?.let { add(ArchiveFile(suffix, rel, it)) }
                    }
                    fb.list(suffix, "schedule").forEach { rel ->
                        fb.read(suffix, rel)?.let { add(ArchiveFile(suffix, rel, it)) }
                    }
                }
            }
        }
        return assemble(accounts, stores, files, appVersion, now = now)
    }

    /** 把档案字节映射落盘到目录（目录即档案本体，§2.0；每个条目一个文件，父目录按需建）。 */
    fun writeToDirectory(archive: Map<String, String>, root: File) {
        root.mkdirs()
        archive.forEach { (rel, content) ->
            val target = File(root, rel)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
    }

    /**
     * 一个**档案目录** → 档案字节映射（[writeToDirectory] 的反向，导入侧读目录形态的那一半）。
     *
     * 相对路径用 `/` 连接、按码点升序读取（`read` 那边是查表，顺序只影响可复现性）。
     * 跳过两类**不是档案内容**的东西：点开头的名字（「已导入」标记 [AppArchiveImport.MARKER_NAME]、
     * 编辑器残片）与 `.tmp` 换名残片 —— 它们落在同一个目录里，但都不该被当成档案的条目。
     */
    fun readDirectory(root: File): Map<String, String> {
        if (!root.isDirectory) return emptyMap()
        val out = linkedMapOf<String, String>()
        root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".tmp") }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                out[file.relativeTo(root).invariantSeparatorsPath] = file.readText()
            }
        return out
    }
}
