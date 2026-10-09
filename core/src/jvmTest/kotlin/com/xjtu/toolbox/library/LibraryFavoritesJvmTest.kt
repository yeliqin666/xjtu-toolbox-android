package com.xjtu.toolbox.library

import com.xjtu.toolbox.platform.keyValueStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 座位收藏的**落盘口径**在 JVM 上也成立 —— 这条测试是「`:core` 的 `KeyValueStore` 补了集合那一档」
 * 那个改动的验收。
 *
 * 钉住三件事：
 *  1. 值类型是**字符串集合**（不是逗号串、不是 JSON），键名与存储名与搬迁前逐字相同
 *     （`library_favorites` / `favorite_seats`）—— Android 那份 `SharedPreferences` 里就是这么存的，
 *     老收藏因此不会丢；
 *  2. `LibraryFavorites.all()` 读的是同一份（三端共用一份实现，不再各端各写一遍）；
 *  3. 翻转语义与原 `AppLibrarySource.toggleFavorite` 的两行逐字一致（读 → `-`/`+` → 写回 → 返回新集合）。
 *
 * 放在 `jvmTest` 而不是 `commonTest`：jvm 的 `KeyValueStore` 是**进程内单例**（`MemoryKeyValueStore`），
 * 用例之间会互相看见，所以这里显式在前后清一次；commonTest 会在 wasm 上跑，
 * 而 wasm 那份落的是 `localStorage`（还需要一个浏览器），不适合放这里。
 */
class LibraryFavoritesJvmTest {

    private val raw = keyValueStore("library_favorites")

    private fun clear() {
        LibraryFavorites.all().forEach { LibraryFavorites.toggle(it) }
        raw.clear()
    }

    @AfterTest
    fun tearDown() = clear()

    @Test
    fun `收藏落在 library_favorites 的 favorite_seats 上，值是一组字符串`() {
        clear()
        assertEquals(emptySet(), LibraryFavorites.all(), "清空后应没有任何收藏")

        assertEquals(setOf("A01"), LibraryFavorites.toggle("A01"))
        assertEquals(setOf("A01", "B12"), LibraryFavorites.toggle("B12"))
        assertEquals(setOf("A01", "B12"), LibraryFavorites.all())

        // 直接按「Android 那边那个键」读：证明存的就是它、类型就是集合
        assertEquals(setOf("A01", "B12"), raw.getStringSet("favorite_seats"))
    }

    @Test
    fun `再翻一次是取消收藏`() {
        clear()
        LibraryFavorites.toggle("A01")
        assertEquals(emptySet(), LibraryFavorites.toggle("A01"))
        assertEquals(emptySet(), raw.getStringSet("favorite_seats") ?: emptySet())
    }

    @Test
    fun `读出来的是副本，改动它不会破坏落盘的那一份`() {
        clear()
        LibraryFavorites.toggle("A01")
        val copy = LibraryFavorites.all().toMutableSet()
        copy += "ZZZ"
        assertEquals(setOf("A01"), LibraryFavorites.all(), "改副本不该影响存储")
    }
}
