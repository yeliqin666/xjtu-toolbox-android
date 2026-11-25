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
    fun `ordinary and secure factories preserve each others writes`() = withRoot { root ->
        val plain = keyValueStore("score_report")
        val secure = secureKeyValueStore("score_report")
        assertSame(plain, secure)
        plain.putString("a", "one")
        secure.putString("b", "two")
        plain.putString("c", "three")
        val disk = java.util.Properties().apply {
            File(root, "score_report.properties").inputStream().use { load(it) }
        }
        assertEquals("one", disk.getProperty("a"))
        assertEquals("two", disk.getProperty("b"))
        assertEquals("three", disk.getProperty("c"))
        assertEquals(setOf("a", "b", "c"), secure.keys())
    }

    @Test
    fun `wipe also clears old handles without creating a second snapshot`() = withRoot { root ->
        val plain = keyValueStore("wipe")
        val secure = secureKeyValueStore("wipe")
        plain.putString("old", "value")
        wipeSecureStore("wipe")
        assertFalse(File(root, "wipe.properties").exists())
        assertNull(plain.getString("old"))
        assertSame(secure, secureKeyValueStore("wipe"))
        secure.putString("new", "value")
        assertNull(plain.getString("old"))
        assertEquals("value", plain.getString("new"))
    }

    @Test
    fun `same name in different roots remains isolated`() = withRoot { root ->
        val first = secureKeyValueStore("same")
        first.putString("k", "first")
        dataRootOverride = File(root, "another")
        val second = secureKeyValueStore("same")
        assertNull(second.getString("k"))
        second.putString("k", "second")
        assertEquals("first", first.getString("k"))
        assertEquals("second", second.getString("k"))
    }
    @Test
    fun `explicit wipe can remove corrupt unopened properties`() = withRoot { root ->
        val file = File(root, "broken.properties")
        file.writeText("key=\\uZZZZ")
        kotlin.test.assertFailsWith<IllegalArgumentException> { secureKeyValueStore("broken") }
        wipeSecureStore("broken")
        assertFalse(file.exists())
        assertNull(secureKeyValueStore("broken").getString("key"))
    }
}
