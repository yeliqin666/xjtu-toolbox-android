package com.xjtu.toolbox.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `keyValueStore(name)` 的 jvm actual 真落盘的守卫。 */
class KeyValueStoreJvmTest {

    private fun withTempRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("xjtu-kv-root").toFile()
        keyValueStoreRootOverride = root
        try {
            block(root)
        } finally {
            keyValueStoreRootOverride = null
        }
    }

    @Test
    fun `第二实例从磁盘读回：写进去之后新开一份能读到同一个值`() = withTempRoot { root ->
        val name = "second_instance_roundtrip"
        val first = keyValueStore(name)
        first.putString("k", "v1")
        first.putInt("i", 42)
        first.putBoolean("b", true)
        first.putStringSet("s", setOf("a", "b"))

        val second = newJvmFileKeyValueStore(name)
        assertEquals("v1", second.getString("k"))
        assertEquals(42, second.getInt("i", -1))
        assertEquals(true, second.getBoolean("b", false))
        assertEquals(setOf("a", "b"), second.getStringSet("s"))
    }

    @Test
    fun `落盘文件确实存在且权限是 0600`() = withTempRoot { root ->
        val name = "permission_check"
        keyValueStore(name).putString("k", "v")

        val file = File(root, "$name.properties")
        assertTrue(file.exists(), "应当真的在临时根里写出 $name.properties")
        // 0600 只在能读到权限时断言：Windows 上 setPosixFilePermissions 抛 UnsupportedOperationException。
        if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()),
            )
        }
    }

    @Test
    fun `keys 列出本 store 的键，clear 之后为空`() = withTempRoot {
        val store = keyValueStore("keys_listing")
        store.clear()
        store.putString("a", "1")
        store.putInt("b", 2)
        store.putBoolean("c", true)
        assertEquals(setOf("a", "b", "c"), store.keys())
        store.remove("b")
        assertEquals(setOf("a", "c"), store.keys())
        store.clear()
        assertTrue(store.keys().isEmpty(), "清空之后不该还能列出键")
    }

    @Test
    fun failedReplacementKeepsCommittedMemory() = withTempRoot { root ->
        val store = keyValueStore("failed_write")
        store.putString("k", "old")
        val file = File(root, "failed_write.properties")
        val backup = File(root, "backup")
        assertTrue(file.renameTo(backup))
        assertTrue(file.mkdir())
        File(file, "sentinel").writeText("keep")
        kotlin.test.assertFailsWith<java.io.IOException> { store.putString("k", "new") }
        assertEquals("old", store.getString("k"), "S2_UNCOMMITTED_MEMORY: failed write changed the memory snapshot")
        assertEquals("keep", File(file, "sentinel").readText())
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        file.deleteRecursively()
        assertTrue(backup.renameTo(file))
        assertEquals("old", newJvmFileKeyValueStore("failed_write").getString("k"))
    }
}
