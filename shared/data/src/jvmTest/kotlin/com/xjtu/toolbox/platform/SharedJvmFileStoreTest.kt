package com.xjtu.toolbox.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class SharedJvmFileStoreTest {
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("shared-store").toFile()
        val previousPlain = keyValueStoreRootOverride
        val previousSecure = dataRootOverride
        try {
            keyValueStoreRootOverride = root
            dataRootOverride = File(root, ".")
            block(root)
        } finally {
            keyValueStoreRootOverride = previousPlain
            dataRootOverride = previousSecure
            root.deleteRecursively()
        }
    }

    @Test
    fun ordinaryAndSecurePreserveWrites() = withRoot { root ->
        val plain = keyValueStore("score_report")
        val secure = secureKeyValueStore("score_report")
        plain.putString("a", "one")
        secure.putString("b", "two")
        plain.putString("c", "three")
        val disk = java.util.Properties().apply {
            File(root, "score_report.properties").inputStream().use { load(it) }
        }
        assertEquals("one", disk.getProperty("a"))
        assertEquals("two", disk.getProperty("b"), "S2_LOST_UPDATE: second factory write disappeared")
        assertEquals("three", disk.getProperty("c"))
        assertSame(plain, secure)
        assertEquals(setOf("a", "b", "c"), secure.keys())
    }

    @Test
    fun wipeClearsOldHandles() = withRoot { root ->
        val plain = keyValueStore("wipe")
        val secure = secureKeyValueStore("wipe")
        plain.putString("old", "value")
        wipeSecureStore("wipe")
        assertFalse(File(root, "wipe.properties").exists())
        assertNull(plain.getString("old"), "S2_STALE_HANDLE: wipe left old handle populated")
        assertSame(secure, secureKeyValueStore("wipe"))
        secure.putString("new", "value")
        assertNull(plain.getString("old"))
        assertEquals("value", plain.getString("new"))
    }

    @Test
    fun differentRootsRemainIsolated() = withRoot { root ->
        val first = secureKeyValueStore("same")
        first.putString("k", "first")
        dataRootOverride = File(root, "another")
        val second = secureKeyValueStore("same")
        assertNull(second.getString("k"), "S2_ROOT_ALIAS: different roots share cached data")
        second.putString("k", "second")
        assertEquals("first", first.getString("k"))
        assertEquals("second", second.getString("k"))
    }
    @Test
    fun corruptFileIsKeptAsideAndCanBeWiped() = withRoot { root ->
        val file = File(root, "broken.properties")
        file.writeText("key=\\uZZZZ")
        // 读不出来不崩，但原件必须原样留底，不能悄悄丢掉
        assertNull(secureKeyValueStore("broken").getString("key"))
        assertEquals("key=\\uZZZZ", File(root, "broken.properties.corrupt").readText(), "S2_CORRUPT_READ: corrupt data was discarded")
        wipeSecureStore("broken")
        assertFalse(file.exists())
        assertNull(secureKeyValueStore("broken").getString("key"))
    }
}
