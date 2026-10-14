package com.xjtu.toolbox.web

import com.xjtu.toolbox.archive.ArchiveFile
import com.xjtu.toolbox.archive.ArchiveFixture
import com.xjtu.toolbox.archive.ArchiveImportTarget
import com.xjtu.toolbox.archive.ImportResult
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.StoreValue
import com.xjtu.toolbox.archive.archiveSourceOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A5（`docs/storage-layout.md` §7）：**同一份档案夹具在本端 `import` 一次，读出的值与另外两端同一批**。
 * 这是 D14「存档层互认测试」在本端的落点（工单 S8）。
 *
 * ## 夹具是同一份文件，不是各端抄一遍
 *
 * [ArchiveFixture] 住在 `:core` 的 commonTest（`core/src/commonTest/.../archive/ArchiveFixture.kt`），
 * 由 `:core:jvmTest`（`StoreArchiveImportTest` / `ArchiveCodecTest`）与这里的 wasmJsTest **同时**消费 ——
 * 规格明写它不能放 `:testkit`（那个模块是 `kotlin.jvm`，wasm 消费不了）。本端怎么拿到那份测试 klib
 * 见 `web/build.gradle.kts` 里 `wasmJsTestKlib` 那段与 `core/build.gradle.kts` 的只读出口。
 *
 * 三端口径如实记一笔：今天真在跑的是 **jvm（`:core` 自己）+ web（这里）** 两份；Android 那一端
 * 还要 Robolectric/instrumented（本批不做），桌面那一端用 `:data` 的落盘根另测
 * （`desktop/src/test/.../DesktopArchiveJvmTest.kt`）—— 见 `docs/storage-layout-plan.md` §7 的 A5 行。
 *
 * ## 断言对象是**档案层**，落点在内存里（这一条是有意为之）
 *
 * A5 要证的是「同一份字节被这一端的代码读懂、值逐字对上」，所以端内落点用一张内存表
 * （[MemoryTarget]，与 `:core` 那份 A6 用例同型）。
 *
 * 刻意**不**拿 `archiveStoreBackend()`（`localStorage` 投影）当断言对象，两个理由：
 *  1. 这一端**生产上的写侧还没接上** —— `WebArchiveHost` 的 KDoc 写着：拿到用户选的档案只做
 *     `StoreArchive.inspect` 自校验、**不写端内**（写侧是 D8 那一件还没拍的活）；
 *  2. `:web` 的端内键值缝只有 s/b/i/set 四档（`KeyValueStore.wasmJs.kt`），`l`（long）与 `f`（float）
 *     在这端怎么落尚无口径 —— 混进来会把「这一端还没有那两档槽位」读成「夹具错了」。
 *
 * 于是两个用例都在**内存端内**上跑：第一个断言「导进来之后的值与夹具逐条相同」，第二个断言
 * 「本端把导进来的那份再导出回去，`stores/` 与 `files/` 的字节与原来那份逐字相同」（同一批字节
 * 在这一端进出都认）。
 */
class ArchiveInteropTest {

    /** 与 `:core` 的 `StoreArchiveImportTest.sourceOfArchive` 同参：同一份夹具、同一个 `export` 调用。 */
    private fun archiveFromFixture(): Map<String, String> = StoreArchive.export(
        ArchiveFixture.accounts,
        "android",
        "5.1.1 (471)",
        ArchiveFixture.stores,
        ArchiveFixture.files,
        ArchiveFixture.fixedClock(),
    )

    @Test
    fun `A5 同一份夹具在本端 import 一次：三个 store 的键、值与 at 加上 files 层都落到本端`() {
        val target = MemoryTarget()

        val result = StoreArchive.import(archiveSourceOf(archiveFromFixture()), target)

        // 凭据族按 A8 不进档案 ⇒ 三份非凭据 store：app_settings 5 键 + schedule_cache 1 键 +
        // library_favorites 1 键 = 7 个键；files 那一层两条（账号档 + 全局档各一个）
        assertEquals(ImportResult.Ok(keysWritten = 7, keysKept = 0, filesWritten = 2), result)

        // A1：键集合相等（app_settings 是**全体键**，不只 `SettingsStore` 那 10 个）
        val appSettings = assertNotNull(target.store("app_settings"), "app_settings 应当落下来了")
        assertEquals(ArchiveFixture.appSettings.keys, appSettings.keys)

        // A2：值逐字相等（这份夹具覆盖 s / b / i / l / f 五种标签）
        assertEquals(StoreValue.S("dark"), appSettings.keys["dark_mode"])
        assertEquals(StoreValue.B(true), appSettings.keys["show_quick_actions"])
        assertEquals(StoreValue.I(4), appSettings.keys["eula_accepted_version"])
        assertEquals(StoreValue.L(ArchiveFixture.FIXED_NOW), appSettings.keys["last_auto_update_check_at"])
        assertEquals(StoreValue.F(12.5f), appSettings.keys["card_today_dinner_cache"])

        // 账号档：端内名带后缀（§2.0），旁挂时间戳以 `at` 的形状进来（§5.2 第 5 条）
        val scheduleCache = assertNotNull(
            target.store("schedule_cache${ArchiveFixture.SUFFIX}"),
            "带后缀的账号档应当落下来了",
        )
        assertEquals(ArchiveFixture.scheduleCache.keys, scheduleCache.keys)
        assertEquals(StoreValue.S("2026-2027-1"), scheduleCache.keys["schedule_current_term"])
        assertEquals(ArchiveFixture.FIXED_NOW, scheduleCache.writtenAt["schedule_current_term"])
        assertTrue(
            scheduleCache.keys.keys.none { it.endsWith("#written_at") },
            "`keys` 里永不出现旁挂时间戳键（A10）：${scheduleCache.keys.keys}",
        )

        // `library_favorites`：A1 那一半是键集合相等（不整张 map 比 —— `set` 的顺序与重复是 codec 的事）；
        // A2 那一半按**元素集合**比（夹具里是乱序 + 重复的 `B-2 / A-1 / A-1`，codec 去重升序）
        val favorites = assertNotNull(target.store("library_favorites"))
        assertEquals(ArchiveFixture.libraryFavorites.keys.keys, favorites.keys.keys)
        assertEquals(
            setOf("A-1", "B-2"),
            (favorites.keys["favorite_seats"] as StoreValue.SetValue).v.toSet(),
        )

        // A8 在导入侧的另一半：凭据族不在这份档案里 ⇒ 端上也不该凭空多出它
        assertNull(target.store("xjtu_credentials"), "凭据族不进档案（A8），导入也不该写它")

        // files 那一层：两条都带着自己的命名空间落下来（§2.3）
        assertEquals(ArchiveFixture.files.map { it.relPath }.sorted(), target.filePaths().sorted())
    }

    @Test
    fun `A5 本端把导进来的那份再导出回去，stores 与 files 的字节与另外两端读到的逐字相同`() {
        val target = MemoryTarget()
        val archive = archiveFromFixture()
        StoreArchive.import(archiveSourceOf(archive), target)

        // 再导出一遍（`createdBy` 换成 `web`：那是"谁导的"，本来就是各端自己写的，§2.1）
        val again = StoreArchive.export(
            ArchiveFixture.accounts,
            WebArchiveHost.CREATED_BY,
            WebArchiveHost.APP_VERSION,
            target.stores(),
            target.files(),
            ArchiveFixture.fixedClock(),
        )

        // manifest 里的 createdAt/createdBy/appVersion 是导的那一端自己的事 ⇒ 只比 stores/ 与 files/ 的字节。
        // 这一比顺带钉住 wasm 上的 JSON 转义与排序：`12.5f` 与 int64 的十进制写法、set 的去重升序、
        // 路径的码点升序 —— 任何一处与 JVM 端不同，这里就会红。
        // 路径集合（`set` 不比顺序：本端是按端内名排的，夹具那一份是按夹具自己的顺序）
        assertEquals(
            archive.keys.filterNot { it == "manifest.json" }.toSet(),
            again.keys.filterNot { it == "manifest.json" }.toSet(),
            "档案里的条目集合在本端往返之后应当逐字相同",
        )
        archive.filterKeys { it != "manifest.json" }.forEach { (path, bytes) ->
            assertEquals(bytes, again[path], "「$path」在本端往返之后应当逐字相同")
        }
    }
}

/**
 * 内存里的「本端」：A5 的落点（[ArchiveImportTarget]），也保有导入后的现状供断言。
 *
 * 与 `:core` 的 `StoreArchiveImportTest` 里那份 `MemoryTarget` 同一套语义：`putKey` 只动这一个键
 * （同 store 的其余键与旁挂时间戳原样留着）、`putFile` 按 `(suffix, relPath)` 落。
 * 为什么在本端用的是内存表而不是 `localStorage`：见文件头那段。
 */
private class MemoryTarget : ArchiveImportTarget {

    private val stores = linkedMapOf<String, StoreSnapshot>()
    private val files = linkedMapOf<Pair<String, String>, String>()

    override fun readStore(rawName: String): StoreSnapshot? = stores[rawName]

    override fun putKey(rawName: String, key: String, value: StoreValue, at: Long?) {
        val current = stores[rawName]
        stores[rawName] = StoreSnapshot(
            rawName = rawName,
            keys = (current?.keys.orEmpty()) + (key to value),
            writtenAt = when {
                at == null -> current?.writtenAt.orEmpty()
                else -> (current?.writtenAt.orEmpty()) + (key to at)
            },
        )
    }

    override fun readFile(suffix: String, relPath: String): String? = files[suffix to relPath]

    override fun putFile(suffix: String, relPath: String, content: String) {
        files[suffix to relPath] = content
    }

    /** 端上某个 store 的现状（读法同 [ArchiveImportTarget.readStore]）。 */
    fun store(rawName: String): StoreSnapshot? = readStore(rawName)

    /** 端内现状（供断言与再导出）：按端内名的码点升序。 */
    fun stores(): List<StoreSnapshot> = stores.values.sortedBy { it.rawName }
    /** 端内 `files/` 那一层（供再导出）：`(suffix, relPath)` → [ArchiveFile]。 */
    fun files(): List<ArchiveFile> = files.entries
        .map { (key, content) -> ArchiveFile(key.first, key.second, content) }
        .sortedBy { it.relPath }

    /** 端内现有文件的相对路径（断言用）。 */
    fun filePaths(): List<String> = files.keys.map { it.second }
}
