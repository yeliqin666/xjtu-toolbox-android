package com.xjtu.toolbox.archive

/** 端内 store 投影（§1.4 缝 A）。 */
interface ArchiveStoreBackend {
    /** 端上真实存在的 store 名（原样，含后缀，如 `schedule_cache_2253415556`）。 */
    fun storeNames(): List<String>

    /** 读一个 store 的全部键；不存在 ⇒ null。 */
    fun readStore(rawName: String): StoreSnapshot?

    /** 端上存在的 store 里，registry 标了「不进档案」的（§2.4）。 */
    fun excludedNames(): List<String> = emptyList()
}

/** 本端的 store 投影（三个 actual）。 */
expect fun archiveStoreBackend(): ArchiveStoreBackend

/** 本端「数据根目录」的绝对路径（§1.2）。 */
expect fun archiveDataRoot(): String?
