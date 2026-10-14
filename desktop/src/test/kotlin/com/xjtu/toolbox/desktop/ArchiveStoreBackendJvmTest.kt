package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.archive.StoreValue
import com.xjtu.toolbox.archive.archiveDataRoot
import com.xjtu.toolbox.archive.archiveStoreBackend
import com.xjtu.toolbox.platform.dataFilesDir
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.keyValueStoreRootOverride
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S2：桌面（jvm）store 投影的验收（§7 A1 / A2 / A13 前半）。
 *
 *  - **A1** 键集合相等：`archiveStoreBackend().storeNames()` 能枚举出端内 store，`readStore` 的
 *    `keys == 端内键`；
 *  - **A2** 带类型值读出来与端内一致：`s`/`b`/`i`/`set` 走端内 `keyValueStore(name)` 真写、投影真读；
 *    `l`/`f` 走 §6.3 的反推（Properties 只有字符串），并顺带钉 `#written_at` 折进 `at`（A10 的投影半）；
 *  - **A13 前半** `archiveDataRoot()` 与 `:data` 的 `dataFilesDir()` 同值。
 *
 * 隔离口径：每条用例各指一个临时根（[keyValueStoreRootOverride] / [dataRootOverride]），
 * `finally` 复位；store 名按用例唯一，避开 `keyValueStore(name)` 的进程内 memo 跨用例串根。
 */
class ArchiveStoreBackendJvmTest {

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("xjtu-archive-root").toFile()
        keyValueStoreRootOverride = root
        dataRootOverride = root
        try {
            block(root)
        } finally {
            keyValueStoreRootOverride = null
            dataRootOverride = null
        }
    }

    @Test
    fun `A1 jvm 投影能枚举 store 且 keys 等于端内键`() = withRoot { root ->
        val store = keyValueStore("s2_a1_enum")
        store.putString("dark_mode", "dark")
        store.putBoolean("show_quick_actions", true)
        store.putInt("eula_accepted_version", 4)

        val backend = archiveStoreBackend()
        assertTrue("s2_a1_enum" in backend.storeNames(), "storeNames() 应能枚举出端内 store")
        val snap = backend.readStore("s2_a1_enum")
        assertEquals(setOf("dark_mode", "show_quick_actions", "eula_accepted_version"), snap?.keys?.keys)
        assertEquals("s2_a1_enum", snap?.rawName)
    }

    @Test
    fun `A2 带类型值读出来与端内一致：s b i set`() = withRoot {
        val store = keyValueStore("s2_a2_typed")
        store.putString("dark_mode", "dark")
        store.putBoolean("show_quick_actions", true)
        store.putInt("eula_accepted_version", 4)
        store.putStringSet("favorite_seats", setOf("A-1", "B-2"))

        val snap = archiveStoreBackend().readStore("s2_a2_typed")!!
        assertEquals(StoreValue.S("dark"), snap.keys["dark_mode"])
        assertEquals(StoreValue.B(true), snap.keys["show_quick_actions"])
        assertEquals(StoreValue.I(4), snap.keys["eula_accepted_version"])
        assertEquals(StoreValue.SetValue(listOf("A-1", "B-2")), snap.keys["favorite_seats"])
    }

    @Test
    fun `A2 l f 靠反推，written_at 折进 at 不出现在 keys`() = withRoot { root ->
        // l/f 在 jvm 的 KeyValueStore 上没有写入口（Properties 只有字符串），按 §6.3 直接写一份
        // .properties 钉「反推」；顺带把 KeyValueScheduleCacheStore 的旁挂时间戳键折进 at（A10 投影半）。
        File(root, "s2_a2_infer.properties").outputStream().use { out ->
            Properties().apply {
                setProperty("last_auto_update_check_at", "1760000000000")
                setProperty("card_today_dinner_cache", "12.5")
                setProperty("schedule_current_term", "2026-2027-1")
                setProperty("schedule_current_term#written_at", "1760000000000")
            }.store(out, null)
        }

        val snap = archiveStoreBackend().readStore("s2_a2_infer")!!
        assertEquals(StoreValue.L(1_760_000_000_000L), snap.keys["last_auto_update_check_at"])
        assertEquals(StoreValue.F(12.5f), snap.keys["card_today_dinner_cache"])
        assertEquals(StoreValue.S("2026-2027-1"), snap.keys["schedule_current_term"])
        assertFalse(snap.keys.containsKey("schedule_current_term#written_at"), "#written_at 不该出现在 keys 里")
        assertEquals(1_760_000_000_000L, snap.writtenAt["schedule_current_term"])
    }

    @Test
    fun `A13 前半 archiveDataRoot 与 dataFilesDir 同值`() = withRoot { root ->
        assertEquals(root.absolutePath, archiveDataRoot())
        assertEquals(dataFilesDir().absolutePath, archiveDataRoot())
    }
}
