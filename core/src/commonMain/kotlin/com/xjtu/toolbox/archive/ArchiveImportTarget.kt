package com.xjtu.toolbox.archive

/** 导入侧的端内写入缝（§1.4 缝 A 的写半边）。 */
interface ArchiveImportTarget {

    /** 本端某个端内 store 的现状（含旁挂时间戳）；端上没有它 ⇒ null。 */
    fun readStore(rawName: String): StoreSnapshot?

    /** 把一个键写进端内 store（[rawName] = 端内名，含后缀）。 */
    fun putKey(rawName: String, key: String, value: StoreValue, at: Long?)

    /** 本端 `files/` 里这个相对路径的现状；不存在 ⇒ null。 */
    fun readFile(suffix: String, relPath: String): String?

    /** 写一个 `files/` 条目（相对路径 = `files/` 之下的路径，目录名不带 suffix，§2.3）。 */
    fun putFile(suffix: String, relPath: String, content: String)
}
