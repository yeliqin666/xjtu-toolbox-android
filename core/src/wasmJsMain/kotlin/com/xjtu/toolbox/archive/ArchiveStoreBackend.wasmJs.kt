package com.xjtu.toolbox.archive

import com.xjtu.toolbox.platform.PREFIX
import kotlinx.browser.localStorage

/**
 * web 侧的 store 投影（S2，只读）。
 *
 * 落点就是 `localStorage` 的 `xjtu-toolbox.<store>.<key>`（照 `KeyValueStore.wasmJs.kt` 的 [PREFIX]
 * 与 `k(key)` 拼法）—— 不新建端内格式。没有文件系统 ⇒ [archiveDataRoot] 返回 `null`（`files`
 * 那一层在 web 上不存在，§1.4 缝 B）。
 *
 * 类型反推逐字照 §6.3 的 web 行：`b`→`"true"/"false"`、`i`→十进制、`set`→**逗号** split
 * （照 `KeyValueStore.wasmJs.kt` 的 `getStringSet`，元素 `trim` + 去空），其余是 `s`。
 * `<key>#written_at` 由 [ArchiveCodec.normalizeWrittenAt] 折进 `writtenAt`（A10）。
 *
 * ⚠️ web 侧本批没有测试源集（D14 未做，`:core` 也未声明 wasm 测试环境），这条 actual 只能
 * `:core:compileKotlinWasmJs` 编译门禁，运行时证明排在 S8（见 `docs/storage-layout-plan.md` §6.3）。
 */
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

/** §6.3 web 行：`b`/`i`/`set`(逗号) 反推，其余是 `s`。与 `KeyValueStore.wasmJs.kt` 的读法同口径。 */
private fun inferValue(raw: String): StoreValue {
    raw.toBooleanStrictOrNull()?.let { return StoreValue.B(it) }
    raw.toIntOrNull()?.let { return StoreValue.I(it) }
    if (raw.contains(',')) {
        return StoreValue.SetValue(raw.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) })
    }
    return StoreValue.S(raw)
}
