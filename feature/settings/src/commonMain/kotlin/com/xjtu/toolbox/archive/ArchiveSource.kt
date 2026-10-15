package com.xjtu.toolbox.archive

/** 档案的传输缝（§1.4）：目录 / zip / SAF / 下载都只露这两个窄接口。 */
interface ArchiveSource {
    fun readManifest(): String?
    fun storeNames(): List<String>
    fun readStore(name: String): String?
    fun filePaths(): List<String>
    fun readFile(rel: String): String?
}

/** 把「档案内相对路径 → 字节」的内存映射当成 [ArchiveSource]。 */
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
