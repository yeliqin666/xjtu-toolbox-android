package com.xjtu.toolbox.desktop.archive

import com.xjtu.toolbox.archive.ArchiveNames
import com.xjtu.toolbox.archive.ArchivePaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 桌面端档案的传输那一半（§1.4 的 `ArchiveSource` 在这一端的落点）。 */
object DesktopArchiveIo {

    /** 桌面数据根下的文件名后缀（§1.2：`<name>.properties`）。 */
    const val STORE_FILE_SUFFIX = ".properties"

    /** zip 运输形态的扩展名（§2.0）。 */
    const val ZIP_SUFFIX = ".zip"

    /** zip 条目的固定时间戳：`ZipEntry` 不设时间会写「现在」，两次导出的字节就不一样了。 */
    private const val FIXED_ZIP_TIME_MS = 0L

    // ── zip 形态（运输） ────────────────────────────────────────────────────

    /** 档案字节映射 → zip 字节（纯函数；条目顺序 = 映射的迭代顺序，时间是固定值 ⇒ 可复现）。 */
    fun zip(archive: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.setLevel(Deflater.NO_COMPRESSION)
            archive.forEach { (rel, content) ->
                val entry = ZipEntry(rel)
                entry.time = FIXED_ZIP_TIME_MS
                zos.putNextEntry(entry)
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** zip 字节 → 档案字节映射（目录条目跳过；条目名就是档案内相对路径）。 */
    fun unzip(bytes: ByteArray): Map<String, String> {
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

    // ── 目录形态（档案本体） ────────────────────────────────────────────────

    /** 档案字节映射 → 目录（每个条目一个文件，父目录按需建；每个条目先 `.tmp` 再原子换名）。 */
    fun writeDirectory(archive: Map<String, String>, root: File) {
        root.mkdirs()
        archive.forEach { (rel, content) ->
            val target = File(root, rel)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(content)
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }

    /** 目录 → 档案字节映射（相对路径用 `/` 连接，按码点升序；空目录 = 空映射）。 */
    fun readDirectory(root: File): Map<String, String> {
        if (!root.isDirectory) return emptyMap()
        val out = linkedMapOf<String, String>()
        root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".tmp") }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                out[rel] = file.readText()
            }
        return out
    }

    /** 一个单文件的原子落盘（zip 运输形态用它，§8.8）。 */
    fun writeFile(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    /** 一个**待导入**的档案路径 → 档案字节映射：目录按目录读，`.zip` 按 zip 解。 */
    fun read(path: File): Map<String, String> = when {
        path.isDirectory -> readDirectory(path)
        path.name.endsWith(ZIP_SUFFIX) -> unzip(path.readBytes())
        else -> emptyMap()
    }


    /** 档案里 `stores/` 的相对路径 → 数据根下的文件（§2.0 的唯一映射规则）。 */
    fun dataRootTarget(dataRoot: File, archivePath: String): File? {
        val segments = archivePath.split('/').filter { it.isNotEmpty() }
        val suffix = when {
            segments.size == 3 && segments[0] == "global" && segments[1] == "stores" -> ""
            segments.size == 4 && segments[0] == "accounts" && segments[2] == "stores" -> segments[1]
            else -> return null
        }
        val fileName = segments.last()
        if (!fileName.endsWith(".json")) return null
        val base = fileName.removeSuffix(".json")
        if (base.isEmpty()) return null
        return File(dataRoot, ArchivePaths.rawStoreName(base, suffix) + STORE_FILE_SUFFIX)
    }

    /** 反向：数据根下的 store 文件名（不含 `.properties`）→ 档案里 `stores/` 的相对路径。 */
    fun storeArchivePath(rawName: String, suffixes: List<String>): String {
        val id = ArchiveNames.split(listOf(rawName), suffixes).first()
        return ArchivePaths.storePath(id.base, id.suffix)
    }
}
