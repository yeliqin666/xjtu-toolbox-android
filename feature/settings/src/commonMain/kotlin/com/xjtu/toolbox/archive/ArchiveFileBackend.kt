package com.xjtu.toolbox.archive

/** 端内 `files` 那一层的只读投影（§1.4 缝 B），只管真实的文件；三族行数组见 [ArchiveRowFamilies]。 */
interface ArchiveFileBackend {
    /** 枚举某个账号档下 `files/` 某前缀的相对路径，按码点升序（§2.3）。 */
    fun list(suffix: String, prefix: String): List<String>

    /** 读某个账号档下 `files/` 的一个相对路径；不存在 ⇒ null。 */
    fun read(suffix: String, relPath: String): String?

    /**
     * 这个条目在端内的 mtime（§6.3「mtime → `at`」）：档案里要带年龄的族（[ArchiveScoreReport]）取它当 `at`。
     *
     * 默认 null = 「年龄不可知」（本端没有文件系统、或这一族端内不落成文件）：调用方按 §6.3 的口径处理，
     * 不许为此抛异常。
     */
    fun mtimeMs(suffix: String, relPath: String): Long? = null
}

/** 本端的 `files` 层投影；wasm = null（浏览器没有文件系统）。 */
expect fun archiveFileBackend(): ArchiveFileBackend?

/** 本端「缓存根目录」的绝对路径（`data_cache*` 的父目录，§8.7 / `DataCache` 的落点）。 */
expect fun archiveCacheRoot(): String?
