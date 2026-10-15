package com.xjtu.toolbox.archive

/** 端内 `files` 那一层的只读投影（§1.4 缝 B）。 */
interface ArchiveFileBackend {
    /** 枚举某个账号档下 `files/` 某前缀的相对路径，按码点升序（§2.3）。 */
    fun list(suffix: String, prefix: String): List<String>

    /** 读某个账号档下 `files/` 的一个相对路径；不存在 ⇒ null。 */
    fun read(suffix: String, relPath: String): String?
}

/** 本端的 `files` 层投影；wasm = null（浏览器没有文件系统）。 */
expect fun archiveFileBackend(): ArchiveFileBackend?

/** 本端「缓存根目录」的绝对路径（`data_cache*` 的父目录，§8.7 / `DataCache` 的落点）。 */
expect fun archiveCacheRoot(): String?
