package com.xjtu.toolbox.archive

import android.content.Context
import android.content.SharedPreferences
import com.xjtu.toolbox.platform.androidPlatformContext
import java.io.File

/**
 * Android 侧的 store 投影（S2，只读）。
 *
 * 落点就是 `getSharedPreferences(name, MODE_PRIVATE)` 的 `prefs.all` —— **同一批文件名、同一批键、
 * 同一批值类型**，一个字不改（§8.1）；不新建任何 archive 专属 prefs 文件。
 *
 * 类型投影逐字照 `SharedPreferences` 的 `getAll()` 返回类型（§6.3 的 Android 行）：
 * `String→s` / `Boolean→b` / `Int→i` / `Long→l` / `Float→f` / `Set<String>→set`。
 * Android 普通 prefs 没有 mtime ⇒ `writtenAt` 恒为空（§2.2 缺省）。
 *
 * 设计成「注入 provider」的形状（plan §1.4）：`:app:testDebugUnitTest` 没有真 `Context`，
 * 但 `:data` 的 [com.xjtu.toolbox.data.InMemorySharedPreferences]（实现 `SharedPreferences` 接口）
 * 可以直接注入进来做 A1/A2 断言。[archiveStoreBackend] 只是默认装配。
 */
class AndroidPrefsBackend(
    private val prefsOf: (String) -> SharedPreferences,
    private val namesOf: () -> List<String>,
) : ArchiveStoreBackend {

    override fun storeNames(): List<String> = namesOf().sorted()

    override fun readStore(rawName: String): StoreSnapshot? {
        val all = prefsOf(rawName).all
        val keys = linkedMapOf<String, StoreValue>()
        all.forEach { (key, value) ->
            keys[key] = when (value) {
                is String -> StoreValue.S(value)
                is Boolean -> StoreValue.B(value)
                is Int -> StoreValue.I(value)
                is Long -> StoreValue.L(value)
                is Float -> StoreValue.F(value)
                is Set<*> -> StoreValue.SetValue(value.map { it as String })
                // §2.2：v 不可为 null，三端 putString(key, null) 都等于删键 ⇒ null 值不会出现在 keys 里
                else -> return@forEach
            }
        }
        return StoreSnapshot(rawName, keys)
    }
}

actual fun archiveDataRoot(): String? = androidPlatformContext()?.filesDir?.absolutePath

actual fun archiveStoreBackend(): ArchiveStoreBackend = AndroidPrefsBackend(
    prefsOf = { name ->
        val context = androidPlatformContext()
            ?: error("initAndroidPlatform(context) 还没被调用 —— 见 :core 的 platform/KeyValueStore.android.kt")
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    },
    namesOf = {
        val context = androidPlatformContext()
        if (context == null) emptyList() else context.sharedPrefsFileNames()
    },
)

/** `shared_prefs/<name>.xml` → store 名。`<name>.xml.bak` 这类备份残片不参与（`endsWith(".xml")` 已排除）。 */
private fun Context.sharedPrefsFileNames(): List<String> {
    val dir = File(applicationInfo.dataDir, "shared_prefs")
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return emptyList()
    return files.map { it.name.removeSuffix(".xml") }.sorted()
}
