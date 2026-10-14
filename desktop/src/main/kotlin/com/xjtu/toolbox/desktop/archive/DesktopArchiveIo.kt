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

/**
 * 桌面端档案的**传输那一半**（§1.4 的 `ArchiveSource` / `ArchiveSink` 在这一端的落点）。
 *
 * 只有两件事：档案字节映射（`档案内相对路径 → 字节`，`StoreArchive.export` 的产物）与
 * **目录形态 / zip 形态**之间的搬运，以及「档案路径 ⇄ 数据根文件名」这条唯一映射规则（A13）。
 *
 * | 形态 | 是什么 | 证据 |
 * |---|---|---|
 * | 目录 | 档案本体，可整拷（§2.0「目录即档案本体，zip 只是运输形态」） | [writeDirectory] / [readDirectory] |
 * | zip | 给「拷给另一台机器 / 给 Android」用的运输形态 | [zip] / [unzip] |
 *
 * ## A11：这里**不**是断言对象
 *
 * 数据根下的 `<store>.properties` 首行是 `Properties.store` 写的时间戳注释
 * （本机实测 `#Wed Oct 14 08:10:56 CST 2026`）⇒ **文件字节不稳定**，桌面门禁的断言一律比
 * [com.xjtu.toolbox.archive.StoreValue]（解析后的键值），不比 `.properties` 的字节。
 *
 * ## 落地是原子的（§8.8）
 *
 * 写目录形态时每个条目先写 `<name>.tmp` 再 `ATOMIC_MOVE` 换名：新代码没有理由继承
 * `KeyValueScheduleCacheStore` 那份「没有原子写」的降级（那条降级是端内旧实现的如实声明）。
 */
object DesktopArchiveIo {

    /** 桌面数据根下的文件名后缀（§1.2：`<name>.properties`）。 */
    const val STORE_FILE_SUFFIX = ".properties"

    /** zip 运输形态的扩展名（§2.0）。 */
    const val ZIP_SUFFIX = ".zip"

    /**
     * zip 条目的固定时间戳：`ZipEntry` 不设时间会写「现在」，两次导出的字节就不一样了。
     * `0` 会被 `java.util.zip` 夹到 DOS 时间的下界（1980-01-01）。
     */
    private const val FIXED_ZIP_TIME_MS = 0L

    // ── zip 形态（运输） ────────────────────────────────────────────────────

    /** 档案字节映射 → zip 字节（纯函数；条目顺序 = 映射的迭代顺序，时间是固定值 ⇒ 可复现）。 */
    fun zip(archive: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            // stored（不压缩）：档案里全是 JSON 文本、体积小，换来的是「解出来的字节与写进去的逐字相同」
            // 这条最好核的性质（A3 的口径在 :core 的 export 上，这里不引入第二处压缩差异）。
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
            .filter { it.isFile }
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

    // ── A13：档案路径 ⇄ 数据根文件名（纯字符串拼接） ────────────────────────

    /**
     * 档案里 `stores/` 的相对路径 → 数据根下的**文件**（§2.0 的唯一映射规则，A13）：
     *
     *  - `global/stores/app_settings.json`            ⇄ `<数据根>/app_settings.properties`
     *  - `accounts/_2253415556/stores/schedule_cache.json` ⇄ `<数据根>/schedule_cache_2253415556.properties`
     *
     * 不是 `stores/` 那一层的路径（`manifest.json`、`files/` 下那几族）⇒ `null`：
     * `files/` 那一层的落点是 [com.xjtu.toolbox.archive.ArchiveFileBackend] 的事（S3），不在这里。
     */
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
        // 「端内名 = base + suffix；桌面再加 .properties」——逐字照 §2.0 的映射规则，纯字符串拼接。
        return File(dataRoot, ArchivePaths.rawStoreName(base, suffix) + STORE_FILE_SUFFIX)
    }

    /**
     * 反向：数据根下的 store 文件名（**不含** `.properties`）→ 档案里 `stores/` 的相对路径。
     *
     * `suffixes` 由调用方给（来自 `manifest.accounts[].suffix` + 两个匿名档，与
     * [com.xjtu.toolbox.archive.ArchiveNames.split] 同一口径）—— base 与 suffix 的边界只能靠
     * 「后缀清单」判，没有它就没法把 `schedule_cache_2253415556` 拆开（§2.0）。
     */
    fun storeArchivePath(rawName: String, suffixes: List<String>): String {
        val id = ArchiveNames.split(listOf(rawName), suffixes).first()
        return ArchivePaths.storePath(id.base, id.suffix)
    }
}
