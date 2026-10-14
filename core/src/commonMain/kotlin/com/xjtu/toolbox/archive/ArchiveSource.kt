package com.xjtu.toolbox.archive

/**
 * 档案的**传输缝**（§1.4）：目录 / zip / SAF / 下载 都只露这两个窄接口，
 * 具体实现各端自定（S4 Android、S5 桌面、S6 web）—— **不在 `:core`**。
 *
 * `name` / `rel` 都是**档案内相对路径**（`manifest.json` 除外）：
 *  - store：`global/stores/<base>.json` 或 `accounts/<suffix>/stores/<base>.json`；
 *  - file：`global/files/<rel>` 或 `accounts/<suffix>/files/<rel>`（`rel` = `files/` 之下的路径）。
 *
 * 这样 A3（字节稳定）与 A7（坏档案不动端）能在**没有真文件系统**的环境里跑
 * （`:core:jvmTest` 用内存实现），而 SAF / zip / 目录这些端内细节不进共享层。
 */
interface ArchiveSource {
    fun readManifest(): String?
    fun storeNames(): List<String>
    fun readStore(name: String): String?
    fun filePaths(): List<String>
    fun readFile(rel: String): String?
}
interface ArchiveSink {
    fun writeManifest(json: String)
    fun writeStore(name: String, json: String)
    fun writeFile(rel: String, content: String)
}

/**
 * 把「档案内相对路径 → 字节」的内存映射当成 [ArchiveSource]（S1b）。
 *
 * 三端的传输通道（桌面目录 / zip、web 的下载与 `input type=file`、Android 的 SAF）解出来的都是这个形状
 * ⇒ 自校验（[StoreArchive.inspect]）与导入（[StoreArchive.import]）只在 `:core` 写一次，
 * 各端只负责「把字节交出来 / 把字节写回去」。
 *
 * `storeNames()` / `filePaths()` 按 §2.0 的形状筛（[ArchivePaths.parseStorePath] / [ArchivePaths.parseFilePath]），
 * 所以 `manifest.json` 与不是档案形状的条目都不会被当成 store / file。
 */
fun archiveSourceOf(entries: Map<String, String>): ArchiveSource = InMemoryArchiveSource(entries)

private class InMemoryArchiveSource(private val entries: Map<String, String>) : ArchiveSource {

    private val storeNames = entries.keys.filter { ArchivePaths.parseStorePath(it) != null }.sorted()
    private val filePaths = entries.keys.filter { ArchivePaths.parseFilePath(it) != null }.sorted()

    override fun readManifest(): String? = entries["manifest.json"]

    override fun storeNames(): List<String> = storeNames

    override fun readStore(name: String): String? = entries[name]

    override fun filePaths(): List<String> = filePaths

    override fun readFile(rel: String): String? = entries[rel]
}
