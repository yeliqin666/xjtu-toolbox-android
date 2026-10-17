package com.xjtu.toolbox.archive

import android.content.Context
import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.data.AppDatabase
import java.io.File
import kotlin.time.Clock

/** `:app` 侧的档案宿主装配。 */
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

    /** 读端内 store 投影：全部 store 名 → 快照。 */
    fun readStores(backend: ArchiveStoreBackend): List<StoreSnapshot> =
        backend.storeNames().mapNotNull { backend.readStore(it) }

    /** 纯组装：把已投影的 stores + files + accounts 交给 [StoreArchive.export]。 */
    fun assemble(
        accounts: List<ArchiveAccount>,
        stores: List<StoreSnapshot>,
        files: List<ArchiveFile>,
        appVersion: String,
        createdBy: String = "android",
        now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): Map<String, String> =
        StoreArchive.export(accounts, createdBy, appVersion, stores, files, ExportOptions(now = now))

    /** 端内 → 完整档案字节映射（导出动作；只读，不写端内任何数据）。 */
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

    /** 一个档案目录 → 档案字节映射（[writeToDirectory] 的反向，导入侧读目录形态的那一半）。 */
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
