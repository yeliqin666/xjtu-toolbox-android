package com.xjtu.toolbox.library

import com.xjtu.toolbox.platform.keyValueStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 座位收藏的落盘口径在 JVM 上也成立。 */
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
