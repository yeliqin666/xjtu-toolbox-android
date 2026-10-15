package com.xjtu.toolbox.archive

import com.xjtu.toolbox.platform.PREFIX
import kotlinx.browser.localStorage

/** web 侧的 store 投影（只读）。 */
actual fun archiveDataRoot(): String? = null

actual fun archiveStoreBackend(): ArchiveStoreBackend = WebStoreBackend

private object WebStoreBackend : ArchiveStoreBackend {

    override fun storeNames(): List<String> {
        val names = linkedSetOf<String>()
        for (i in 0 until localStorage.length) {
            val key = localStorage.key(i) ?: continue
            if (!key.startsWith(PREFIX)) continue
            val store = key.removePrefix(PREFIX).substringBefore('.')
            if (store.isNotEmpty()) names += store
        }
        return names.sorted()
    }

    override fun readStore(rawName: String): StoreSnapshot? {
        val prefix = "$PREFIX$rawName."
        val keys = linkedMapOf<String, StoreValue>()
        var found = false
        for (i in 0 until localStorage.length) {
            val key = localStorage.key(i) ?: continue
            if (!key.startsWith(prefix)) continue
            found = true
            val k = key.removePrefix(prefix)
            val value = localStorage.getItem(key) ?: continue
            keys[k] = inferValue(value)
        }
        if (!found) return null
        return ArchiveCodec.normalizeWrittenAt(rawName, keys)
    }
}

/** §6.3 web 行：`b`/`i`/`set`(逗号) 反推，其余是 `s`（与 `KeyValueStore.wasmJs.kt` 一致）。 */
private fun inferValue(raw: String): StoreValue {
    raw.toBooleanStrictOrNull()?.let { return StoreValue.B(it) }
    raw.toIntOrNull()?.let { return StoreValue.I(it) }
    if (raw.contains(',')) {
        return StoreValue.SetValue(raw.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) })
    }
    return StoreValue.S(raw)
}
