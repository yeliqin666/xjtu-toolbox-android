package com.xjtu.toolbox.archive

import android.content.Context
import android.net.Uri
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.data.AppDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `:app` 侧的**导入/导出入口与通道**（S4 接线点）：SAF 通道 + 导出动作 + 一次性导入触发点。
 *
 * 本工单只做「入口与通道」（plan §2 的 S4 条目 / §3 的红线四类）——**不做合并语义**：
 * §6.4 的 `StoreArchive.import`（merge / replace、`at` 新者胜、坏档案不动端）要在 `:core`
 * 落地，而目前 `StoreArchive` 只有 `export`。所以这里：
 *  - **导出**：真的组装 + 落盘（`AppArchiveHost.buildArchive` 只读端内）；
 *  - **导入**：只把选中的档案**复制进 staging**（通道），`maybeImportPendingArchive` 只判读
 *    有没有待导入档案 —— 两者都不写任何 store / Room 数据（默认不动用户数据）。
 */
object AppArchiveUx {
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

    /** 把 zip 字节写进 SAF 选中的目标文档。 */
    fun writeExportToUri(context: Context, uri: Uri, archive: Map<String, String>) {
        val bytes = zipArchive(archive)
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("无法打开导出目标：$uri")
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

    /**
     * 一次性导入的**触发点**（§6.2，`XjtuApp.attachBaseContext` 里调用，顺序在
     * `DataCache.clearIfPackageChanged` 之后，§8.7）。
     *
     * 本工单只做入口：真正的合并（§6.4）排在后续工单 ⇒ 这里只判读「有没有待导入的档案」，
     * 不写任何 store / Room 数据 —— **默认不动用户数据**。
     */
    fun maybeImportPendingArchive(context: Context) {
        maybeImportPendingArchive(pendingArchiveRoots(context))
    }

    /**
     * §6.2 的检查点：档案落地目录（`files/archive/`）与 SAF 导入通道的暂存区
     * （`files/archive_staging/`）—— 二者都是「把档案放进端内」的入口位置。
     */
    fun pendingArchiveRoots(context: Context): List<File> =
        listOf(AppArchiveHost.archiveRoot(context), AppArchiveHost.stagingRoot(context))

    /** 同 [maybeImportPendingArchive]，把根目录列表参数化以便单测驱动（无真 Context）。 */
    fun maybeImportPendingArchive(roots: List<File>) {
        if (!roots.any { it.hasPendingArchive() }) return
        // 待导入档案已落地；由后续工单的 StoreArchive.import 消费（本工单不 merge、不删、不标记）。
    }

    /** 根目录下有一个待导入的档案（zip 文件与整目录两种形态都算，§2.0 的两种落地形态）。 */
    private fun File.hasPendingArchive(): Boolean = isDirectory && listFiles()?.isNotEmpty() == true

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
