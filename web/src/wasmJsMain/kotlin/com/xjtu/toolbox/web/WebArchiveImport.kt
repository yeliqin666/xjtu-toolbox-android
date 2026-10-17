package com.xjtu.toolbox.web

import com.xjtu.toolbox.archive.ArchiveCodec
import com.xjtu.toolbox.archive.ArchiveImportTarget
import com.xjtu.toolbox.archive.ArchivePaths
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.StoreValue
import com.xjtu.toolbox.archive.archiveStoreBackend
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore

/**
 * Web 这一端的**导入写侧**（S6 收尾）：`:core` 的 [ArchiveImportTarget] 在 wasm 上的落点。
 *
 * 合并语义一个字都不在这里 —— §6.4 的裁决（`at` 新者胜 / 任一方缺 `at` 本端胜 / 本端有档案没有 ⇒
 * 保持不变 / `set` 覆盖不并集 / `format`·`version` 不认识 ⇒ 明确失败）全在
 * [com.xjtu.toolbox.archive.StoreArchive.import]。
 *
 * ## 落点是端内**同一批键名**（`localStorage["xjtu-toolbox.<store>.<key>"]`，§1.3）
 *
 * 写回走的是 [keyValueStore]（`KeyValueStore.wasmJs.kt` 那一份 actual）—— 与端上各处读写同一个
 * `localStorage` 键，不新建端内格式、不新造前缀。类型逐字照 §6.3 的 web 行：
 * `b`→`"true"/"false"`、`i`→十进制、`set`→**逗号**串（[KeyValueStore.putStringSet] 的落地形状，
 * 与读侧 `inferValue` 的 `split(',')` 逐字对应）、其余是字符串。
 *
 * ⚠️ 这一端只有 `s`/`b`/`i`/`set` 四档槽位（见 `KeyValueStore.wasmJs.kt` 与
 * `ArchiveInteropTest` 的 KDoc）：档案里的 `l`/`f` 按**字符串**写进去，读回来会被反推成 `s`
 * —— 这是本端键值缝**今天**的形状，不是这次导入引入的（桌面那一端同一条：`l`/`f` 也写成字符串、
 * 靠 `toLongOrNull`/`toFloatOrNull` 反推；web 少的就是那两条反推）。
 *
 * ## 旁挂时间戳与文件层
 *
 *  - `at` 非空时同时写 `<key>#written_at`（§5.2 第 5 条）——Web 的课表族缓存就是靠它算年龄的
 *    （`KeyValueScheduleCacheStore`），不写的话导进来的那一份下次被判成「没有年龄」；
 *  - `files/` 那一层这一端**只有三族行数组里有落点的那一族**（自建日程，见 [WebArchiveRows]）：
 *    [putFile] 把它按 `:core` 的 [com.xjtu.toolbox.archive.ArchiveRowFamilies.merge] 合并进 `custom_courses` 那个键（§6.4 的
 *    「本端胜」在行这一级）；其余 `files/` 条目（另两族的行数组、`agent_sessions/…`）本端没有落点
 *    ⇒ [withoutUnlandedFiles] 在导入**之前**把它们滤掉，而不是「假装写进去」—— 这样
 *    `StoreArchive.import` 报的 `filesWritten` 就是真写进本端的条数，不是一句谎话。
 */
internal class WebArchiveImportTarget(
    /** 写侧：按**端内名逐字**取那个键值缝（与端上各处同一个 `localStorage` 命名空间）。 */
    private val storeOf: (String) -> KeyValueStore = { keyValueStore(it) },
) : ArchiveImportTarget {

    override fun readStore(rawName: String): StoreSnapshot? = archiveStoreBackend().readStore(rawName)

    override fun putKey(rawName: String, key: String, value: StoreValue, at: Long?) {
        val store = storeOf(rawName)
        when (value) {
            is StoreValue.S -> store.putString(key, value.v)
            is StoreValue.B -> store.putBoolean(key, value.v)
            is StoreValue.I -> store.putInt(key, value.v)
            // 本端没有 `l`/`f` 两档槽位：按字符串落地（与 §6.3 的 web 行同口径）
            is StoreValue.L -> store.putString(key, value.v.toString())
            is StoreValue.F -> store.putString(key, value.v.toString())
            is StoreValue.SetValue -> store.putStringSet(key, value.v.toSet())
        }
        if (at != null) store.putString(key + ArchiveCodec.WRITTEN_AT_SUFFIX, at.toString())
    }

    /**
     * 本端没有文件系统 ⇒ 这里恒为 `null`：`files/` 那一层里没有一样东西能说「端内已有一份、跳过」。
     * 三族行数组里有落点的那一族因此每次都走 [putFile] 的合并（合并本身是幂等的：已有主键不重复追加）。
     */
    override fun readFile(suffix: String, relPath: String): String? = null

    /**
     * 三族行数组里**有落点**的那一族（自建日程）⇒ 合并进端内那个键值店（§6.4 的「本端胜」在行这一级）。
     * 其余条目本端没有落点、导入前已被 [withoutUnlandedFiles] 滤掉 ⇒ 走到这里也只能什么都不写
     * （`filesWritten` 因此只数真落地的那些）。
     */
    override fun putFile(suffix: String, relPath: String, content: String) {
        WebArchiveRows.land(suffix, relPath, content, storeOf = storeOf)
    }
}

/**
 * 把档案里**本端没有落点**的 `files/` 条目滤掉（保留 `manifest.json`、`stores/` 与有落点的那一族行数组）——
 * 这一端没有文件系统，另两族的业务也不存在；滤掉之后「写了几笔」的计数才是真的。
 */
internal fun withoutUnlandedFiles(entries: Map<String, String>): Map<String, String> =
    entries.filterKeys { path ->
        val parsed = ArchivePaths.parseFilePath(path) ?: return@filterKeys true
        lands(parsed.first, parsed.second)
    }

/** 档案里**本端没有落点**的 `files/` 条目数（状态文本要如实说「几条没落地」）。 */
internal fun unlandedFilesCount(entries: Map<String, String>): Int =
    entries.keys.count { path ->
        val parsed = ArchivePaths.parseFilePath(path) ?: return@count false
        !lands(parsed.first, parsed.second)
    }

/** 这一条 `files/` 路径在本端有没有落点（三族行数组里本端有落点的那一族，且路径在 `global/` 下）。 */
private fun lands(relPath: String, suffix: String): Boolean =
    suffix.isEmpty() && WebArchiveRows.of(relPath) != null
