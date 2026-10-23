package com.xjtu.toolbox.archive

import android.content.Context
import android.net.Uri
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.data.AppDatabase
import com.xjtu.toolbox.platform.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** `:app` 侧的导入/导出入口与通道：SAF 通道 + 导出动作 + 一次性导入触发点。 */
object AppArchiveUx {
    private const val TAG = "AppArchive"

    /** SAF 导出/导入的 MIME：zip 是档案的运输形态（§2.0）。 */
    const val ARCHIVE_MIME = "application/zip"

    /** 档案字节映射 → zip 字节（运输形态；纯函数，可测）。 */
    fun zipArchive(archive: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            archive.forEach { (rel, content) ->
                zos.putNextEntry(ZipEntry(rel))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** zip 字节 → 档案字节映射（[zipArchive] 的反向；目录条目跳过，条目名就是档案内相对路径）。 */
    fun unzipArchive(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    out[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
            }
        }
        return out
    }

    /** 一份待导入档案 → 档案字节映射。 */
    fun readArchive(path: File): Map<String, String>? = runCatching {
        if (path.isDirectory) AppArchiveHost.readDirectory(path) else unzipArchive(path.readBytes())
    }.getOrNull()

    /** 端内 → 完整档案字节映射（导出动作的核心；只读端内，不写任何 store / Room 数据）。 */
    suspend fun exportArchive(context: Context): Map<String, String> {
        val db = AppDatabase.getInstance(context)
        val accounts = AppArchiveHost.accountsFrom(AccountStore().list())
        return AppArchiveHost.buildArchive(
            db = db,
            backend = archiveStoreBackend(),
            filesBackend = archiveFileBackend(),
            accounts = accounts,
            appVersion = appVersionText(),
        )
    }

    /**
     * 导出时同时落的那一份**目录副本**（B-15）：固定落 `<filesDir>/archive_export/`。
     *
     * SAF 的 `CreateDocument` 一次只能选一个**文件**（选整棵目录要另一套 `OpenDocumentTree` 与
     * 树形 Uri 权限，本仓库没有那套依赖），所以“也能给出一棵目录”这件事只能落在端内自己的目录里：
     * 用户用数据线 / 文件管理器把整个 `archive_export/` 拷走，它就是一份合法的目录形态档案
     * （[AppArchiveHost.readDirectory] 能读、桌面端也认）。
     *
     * 每次导出**先清空**：否则上一份导出里才有的条目（比如那时还有的课程）会混进这一份里，
     * 而拿到它的人无从分辨哪一条是新的。
     */
    fun exportToDirectory(context: Context, archive: Map<String, String>): File {
        val root = AppArchiveHost.exportRoot(context)
        runCatching { root.deleteRecursively() }
        AppArchiveHost.writeToDirectory(archive, root)
        return root
    }

    /**
     * 把 zip 字节写进 SAF 选中的目标文档，并**同时**落一份目录副本（B-15）。
     *
     * 顺序有意如此：先落目录、再写 SAF。zip 那一步失败（用户选的 Uri 写不了、U 盘掉了）时，
     * 目录副本已经在了 —— “导出了但没落地”这种空手而归少一种。返回目录副本的根（给提示文案用）。
     */
    fun writeExportToUri(context: Context, uri: Uri, archive: Map<String, String>): File {
        val dir = exportToDirectory(context, archive)
        val bytes = zipArchive(archive)
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("无法打开导出目标：$uri")
        return dir
    }

    /** SAF 导入通道：把选中的文档复制进 `filesDir/archive_staging/`（不合并、不写端内数据）。 */
    fun copyImportToStaging(context: Context, uri: Uri): File? {
        val staging = AppArchiveHost.stagingRoot(context)
        staging.mkdirs()
        val name = documentName(context, uri) ?: "archive.zip"
        val target = File(staging, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        return target
    }

    /** 一次性导入的触发点（§6.2，`XjtuApp.attachBaseContext` 里调用，顺序在`DataCache.clearIfPackageChanged` 之后，§8.7）：扫两个落地目录，把找到的那份档案交给 [AppArchiveImport]（自校验→ 行数组预检→ §6.4 合并→ 落「已导入」标记）。 */
    fun maybeImportPendingArchive(context: Context): ArchiveImportOutcome? {
        val archive = pendingArchiveCandidates(pendingArchiveRoots(context)).firstOrNull() ?: return null
        val entries = readArchive(archive) ?: return log(
            ArchiveImportOutcome.Failed("读不出来：${archive.absolutePath}"),
        )
        val outcome = AppArchiveImport.run(archive, entries) { manifest ->
            AppArchiveImportTarget.of(context, archiveAccountIds = manifest.accounts.map { it.accountId })
        }
        return log(outcome)
    }

    /**
     * 设置页那份导入的两步流第一步：把选中的 zip 读出来算差异，**端内一个字节不动**（§6.4）。
     *
     * 拿到 [AppArchivePlanOutcome.Planned] 后弹窗就用共享层那份话术：
     * [ArchiveImportPrompt.TITLE] / [ArchiveImportPrompt.label] / [ArchiveImportPrompt.detail]，
     * 差异那一行用 `planned.plan.summary`（或按当前选中的档位重新算：`ArchiveImportPrompt.diffSummary(mode, planned.plan.diff)`），
     * 额外提醒用 [ArchiveImportPrompt.warning]。
     */
    fun planImport(context: Context, archive: File, mode: ArchiveImportMode): AppArchivePlanOutcome {
        val entries = readArchive(archive)
            ?: return AppArchivePlanOutcome.Failed("读不出来：${archive.absolutePath}")
        return AppArchiveImport.plan(archive, entries, mode) { manifest ->
            AppArchiveImportTarget.of(context, archiveAccountIds = manifest.accounts.map { it.accountId })
        }
    }

    /** 第二步：用户点了 [ArchiveImportPrompt.CONFIRM] 之后才落地（镜像档失败已由共享层整体回滚）。 */
    fun finishImport(planned: AppArchivePlanOutcome.Planned): ArchiveImportOutcome =
        log(AppArchiveImport.apply(planned))

    private fun log(outcome: ArchiveImportOutcome): ArchiveImportOutcome {
        when (outcome) {
            is ArchiveImportOutcome.Imported -> Log.i(
                TAG,
                "导入 ${outcome.archive.name}：写入 ${outcome.result.keysWritten} 个键 /" +
                    " 保持 ${outcome.result.keysKept} 个 / ${outcome.result.filesWritten} 个文件" +
                    (if (outcome.result.filesSkipped > 0) " / 跳过 ${outcome.result.filesSkipped} 条" else ""),
            )
            is ArchiveImportOutcome.AlreadyImported ->
                Log.i(TAG, "${outcome.archive.name} 已经导过（标记与这份档案一致）—— 什么都不做")
            is ArchiveImportOutcome.Failed -> Log.w(TAG, "导入失败：${outcome.reason}（端内零变化）")
        }
        return outcome
    }

    /** §6.2 的检查点：档案落地目录（`files/archive/`）与 SAF 导入通道的暂存区（`files/archive_staging/`） */
    fun pendingArchiveRoots(context: Context): List<File> =
        listOf(AppArchiveHost.archiveRoot(context), AppArchiveHost.stagingRoot(context))

    /** 同 [maybeImportPendingArchive]（带 Context 的那一个），把根目录列表参数化以便单测驱动（无真 Context）。 */
    fun maybeImportPendingArchive(roots: List<File>) {
        pendingArchiveCandidates(roots)
    }

    /** 根目录列表里真正待导入的那一份（§6.2 的「有档案才跑」）：每个根下按三种形态找 —— */
    fun pendingArchiveCandidates(roots: List<File>): List<File> = roots.flatMap { root ->
        when {
            !root.isDirectory -> emptyList()
            File(root, "manifest.json").isFile -> listOf(root)
            else -> {
                val zips = root.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
                    ?.sortedBy { it.name }
                    ?: emptyList()
                if (zips.isNotEmpty()) {
                    zips
                } else {
                    root.listFiles { f -> f.isDirectory && File(f, "manifest.json").isFile }
                        ?.sortedBy { it.name }
                        ?: emptyList()
                }
            }
        }
    }

    private fun appVersionText(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    private fun documentName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
            if (!name.isNullOrBlank()) return name
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
