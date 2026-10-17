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

/** 桌面（jvm）store 投影的验收（§7）。 */
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
