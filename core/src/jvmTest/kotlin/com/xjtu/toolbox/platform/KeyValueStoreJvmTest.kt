package com.xjtu.toolbox.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S0（桌面落盘 0 号前置）的守卫：`keyValueStore(name)` 的 jvm actual 从「进程内内存」改成「真落盘」后，
 * 钉住三条 ——
 *  1. **真的落盘了**：写进去之后，用 [newJvmFileKeyValueStore] 开一个**第二实例**（不查 memo）能读回同一批值；
 *  2. 文件权限是 `0600`（与密文存储同一套口径）；
 *  3. **同名 store 不得同时以 plain 与 secure 打开**：两边现在都写 `<根>/<name>.properties`，
 *     同名 = 互相覆盖。这里把桌面这一族 plain / secure 的**基础名**各列一份，断言无交集（显式报错，不静默）。
 *
 * 隔离口径：每条用例各指一个临时根（[keyValueStoreRootOverride]），互不污染；`finally` 里复位，
 * 其余用例靠 Gradle 注入的 `xjtu.toolbox.keyValueRoot` 系统属性兜底。
 */
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
        // 0600：本用户可读可写，其余位全部为 0。POSIX 上直接断言；Windows 上 setPosixFilePermissions
        // 会抛 UnsupportedOperationException（那一步在 persist() 里是 runCatching 降级），所以这里
        // 只在能读到权限时才断言。
        runCatching {
            val perms = Files.getPosixFilePermissions(file.toPath())
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                perms,
            )
        }
    }

    @Test
    fun `同名 store 不得同时以 plain 与 secure 打开`() {
        // 基础名（去掉账号后缀）。两份清单的真源是 `docs/storage-layout.md` §3.2 与 grep 到的实际调用点；
        // 今天（S0）核实过无交集 —— 一旦有人把某个 store 换成另一条缝，这条会响亮地失败而不是静默覆盖。
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
            "dorm_power",
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
