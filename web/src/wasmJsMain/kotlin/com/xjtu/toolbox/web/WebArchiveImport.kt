package com.xjtu.toolbox.web

import com.xjtu.toolbox.archive.ArchiveCodec
import com.xjtu.toolbox.archive.ArchiveImportTarget
import com.xjtu.toolbox.archive.ArchivePaths
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.StoreValue
import com.xjtu.toolbox.archive.archiveStoreBackend
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore

/** Web 这一端的导入写侧：`:core` 的 [ArchiveImportTarget] 在 wasm 上的落点。 */
internal class WebArchiveImportTarget(
    /** 写侧：按端内名取那个键值缝。 */
    private val storeOf: (String) -> KeyValueStore = { keyValueStore(it) },
) : ArchiveImportTarget {

    override fun readStore(rawName: String): StoreSnapshot? = archiveStoreBackend().readStore(rawName)

    override fun putKey(rawName: String, key: String, value: StoreValue, at: Long?) {
        val store = storeOf(rawName)
        when (value) {
            is StoreValue.S -> store.putString(key, value.v)
            is StoreValue.B -> store.putBoolean(key, value.v)
            is StoreValue.I -> store.putInt(key, value.v)
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

/** 把档案里 `files/` 那一层的条目滤掉（保留 `manifest.json` 与 `stores/`）—— 这一端没有文件系统，那些条目没有落点。 */
internal fun withoutFilesLayer(entries: Map<String, String>): Map<String, String> =
    entries.filterKeys { ArchivePaths.parseFilePath(it) == null }

/** 档案里 `files/` 那一层的条目数。 */
internal fun filesLayerCount(entries: Map<String, String>): Int =
    entries.keys.count { ArchivePaths.parseFilePath(it) != null }
