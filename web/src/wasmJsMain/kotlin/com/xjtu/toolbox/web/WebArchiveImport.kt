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
 *  - `files/` 那一层这一端**不存在**（浏览器没有文件系统，[com.xjtu.toolbox.archive.archiveFileBackend]
 *    在 wasm 上返回 `null`）：[withoutFilesLayer] 在导入**之前**把那些条目滤掉，而不是「假装写进去」
 *    —— 这样 `StoreArchive.import` 报的 `filesWritten` 就是真写进本端的条数（0），不是一句谎话。
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

    /** 本端没有文件系统 ⇒ 任何 `files/` 条目都「本来就没有」（也正因如此，导入前会先滤掉它们）。 */
    override fun readFile(suffix: String, relPath: String): String? = null

    /** 同上：`files/` 那一层在 web 上没有落点，什么都不写（导入前已滤掉，正常不会走到这里）。 */
    override fun putFile(suffix: String, relPath: String, content: String) = Unit
}

/**
 * 把档案里 `files/` 那一层的条目滤掉（保留 `manifest.json` 与 `stores/`）——
 * 这一端没有文件系统，那些条目没有落点；滤掉之后「写了几笔」的计数才是真的。
 */
internal fun withoutFilesLayer(entries: Map<String, String>): Map<String, String> =
    entries.filterKeys { ArchivePaths.parseFilePath(it) == null }

/** 档案里 `files/` 那一层的条目数（状态文本要如实说「几条没落地」）。 */
internal fun filesLayerCount(entries: Map<String, String>): Int =
    entries.keys.count { ArchivePaths.parseFilePath(it) != null }
