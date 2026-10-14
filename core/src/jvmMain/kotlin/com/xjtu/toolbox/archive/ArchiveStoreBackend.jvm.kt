package com.xjtu.toolbox.archive

import com.xjtu.toolbox.platform.keyValueStoreRoot
import java.io.File
import java.util.Properties

/**
 * 桌面（jvm）侧的 store 投影（S2，只读）。
 *
 * 落点就是 `<数据根>/<name>.properties`（§1.2）—— 与 `:data` 的 `SecureStore.jvm.kt` 同一根、
 * 同一套 `java.util.Properties` 形状（首行时间戳注释、`\uXXXX` 转义、0600），不新建端内格式。
 * 数据根复用 `:core` 自己的 [keyValueStoreRoot]（override ?: 系统属性 ?: XDG 字面量），
 * 与 S0 落盘的 `keyValueStore(name)` 是同一个根 ⇒ 投影读到的就是端内真实键值。
 *
 * 类型**反推**逐字照 §6.3 的桌面行（Properties 只有字符串）：`b`/`i`/`l`/`f` 靠
 * `toBooleanStrictOrNull`/`toIntOrNull`/`toLongOrNull`/`toFloatOrNull`，`set` 靠 `\u0000` 分隔，
 * 其余是 `s`。`<key>#written_at` 由 [ArchiveCodec.normalizeWrittenAt] 折进 `writtenAt`（A10）。
 */
actual fun archiveDataRoot(): String? = keyValueStoreRoot().absolutePath

actual fun archiveStoreBackend(): ArchiveStoreBackend = JvmStoreBackend(keyValueStoreRoot())

internal class JvmStoreBackend(private val root: File) : ArchiveStoreBackend {

    override fun storeNames(): List<String> =
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".properties") }
            ?.map { it.name.removeSuffix(".properties") }
            ?.sorted()
            ?: emptyList()

    override fun readStore(rawName: String): StoreSnapshot? {
        val file = File(root, "$rawName.properties")
        if (!file.exists()) return null
        val props = Properties()
        runCatching { file.inputStream().use { props.load(it) } }
        val keys = linkedMapOf<String, StoreValue>()
        props.stringPropertyNames().forEach { key ->
            val value = props.getProperty(key) ?: return@forEach
            keys[key] = inferValue(value)
        }
        return ArchiveCodec.normalizeWrittenAt(rawName, keys)
    }
}

/** §6.3 桌面行：Properties 只有字符串，带标签值靠反推。`set` 用 `\u0000` 分隔（与 `SecureStore.jvm.kt` 同口径）。 */
private fun inferValue(raw: String): StoreValue {
    if (raw.contains('\u0000')) {
        return StoreValue.SetValue(raw.split('\u0000').filter { it.isNotEmpty() })
    }
    raw.toBooleanStrictOrNull()?.let { return StoreValue.B(it) }
    raw.toIntOrNull()?.let { return StoreValue.I(it) }
    raw.toLongOrNull()?.let { return StoreValue.L(it) }
    raw.toFloatOrNull()?.let { return StoreValue.F(it) }
    return StoreValue.S(raw)
}
