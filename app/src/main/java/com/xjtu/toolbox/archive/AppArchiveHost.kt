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

    /**
     * 导出的**目录副本**落在这里（B-15）。
     *
     * ⚠️ 不能复用 [ARCHIVE_DIR]：那个目录是**导入的投放口**（`pendingArchiveRoots` 会扫它，
     * 见到 `manifest.json` 就当一份待导入档案），导出写进去 = 下次启动自己导自己一遍。
     * 名字分开就不必指望「导入幂等」把这件事撮合过去。
     */
    const val EXPORT_DIR = "archive_export"

    fun archiveRoot(context: Context): File = File(context.filesDir, ARCHIVE_DIR)

    fun stagingRoot(context: Context): File = File(context.filesDir, STAGING_DIR)

    /** 导出时落的那一份目录的根（[EXPORT_DIR]）。 */
    fun exportRoot(context: Context): File = File(context.filesDir, EXPORT_DIR)

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
                    // §6.3 逐族迁移：`schedule/` 之下的条目里，成绩缓存那一族要转形状 + 归一名字
                    //（专到 [scheduleFileOf]）
                    fb.list(suffix, "schedule").forEach { rel ->
                        scheduleFileOf(fb, suffix, rel)?.let { add(it) }
                    }
                }
            }
        }
        return assemble(accounts, stores, files, appVersion, now = now)
    }

    /**
     * `files/schedule/` 之下的一个端内条目 → 档案条目（§6.3 逐族迁移）。
     *
     * 不属于迁移族的照原样搬（§2.3）；成绩缓存那一族（[ArchiveScoreReport]）端内是**裸数组 + mtime**，
     * 档案里统一成 `{"at":<mtime>,"grades":[…]}` 与不含学号的规范名。转不动 ⇒ null（这一条不进档案，
     * 与导入侧的「跳过并计数」同一句话；导出不因此抛）。
     */
    private fun scheduleFileOf(
        filesBackend: ArchiveFileBackend,
        suffix: String,
        relPath: String,
    ): ArchiveFile? {
        val content = filesBackend.read(suffix, relPath) ?: return null
        if (!ArchiveScoreReport.isFamily(relPath)) return ArchiveFile(suffix, relPath, content)
        val canonical = ArchiveScoreReport.toCanonical(content, filesBackend.mtimeMs(suffix, relPath) ?: 0L)
            ?: return null
        return ArchiveFile(suffix, ArchiveScoreReport.CANONICAL_REL, canonical)
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
