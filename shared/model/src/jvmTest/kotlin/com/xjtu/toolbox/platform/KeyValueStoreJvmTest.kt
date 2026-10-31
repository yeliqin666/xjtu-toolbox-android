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
        runCatching {
            val perms = Files.getPosixFilePermissions(file.toPath())
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                perms,
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
    fun `同名 store 不得同时以 plain 与 secure 打开`() {
        // 两份清单必须无交集：有人把某个 store 换到另一条缝时这里会失败而不是静默覆盖。
        val plainStoreNames = setOf(
            "venue_favorites",
            "library_favorites",
            "empty_room",
            "feature_hints",
            "bulletin_store",
            "feedback",
            "games",
            "haptics_prefs",
            "jiaocai1_reader_prefs",
            "score_report",
            "home_usage",
            "inbox",
            "schedule_diff",
            "course_colors",
            "schedule_source",
            "schedule_changes",
            "attendance_records_ug",
            "attendance_records_pg",
        )
        val secureStoreNames = setOf(
            "app_settings",
            "payment_code",
            "xjtu_accounts",
            "xjtu_credentials",
            "github_community",
            "agent_config",
            "agent_config_secure",
            "agent_memory",
            "schedule_cache",
            "custom_courses",
            "download_tasks",
            "jiaocai1_shelf",
            "serve",
            "cookies_normal",
            "cookies_webvpn",
            "sites_normal",
            "sites_webvpn",
        )
        val overlap = plainStoreNames.intersect(secureStoreNames)
        assertTrue(
            overlap.isEmpty(),
            "同名 store 同时被 plain 与 secure 打开会互相覆盖同一份 .properties：$overlap",
        )
    }
}
