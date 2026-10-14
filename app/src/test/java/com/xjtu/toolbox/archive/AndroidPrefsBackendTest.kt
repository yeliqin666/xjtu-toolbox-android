package com.xjtu.toolbox.archive

import com.xjtu.toolbox.data.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2：Android store 投影的验收（§7 A1 / A2）。
 *
 * Android 单测没有真 `Context`，但 `:data` 的 [InMemorySharedPreferences] 实现了
 * `android.content.SharedPreferences` 接口 ⇒ 把它注入 [AndroidPrefsBackend] 的 provider，
 * 就能跑「投影能枚举出 store」「带类型值读出来与端内一致」这两条断言（plan §1.4 / §6.3 A1、A2）。
 * 值类型逐字照 `SharedPreferences.getAll()`：`String/Boolean/Int/Long/Float/Set<String>`。
 */
class AndroidPrefsBackendTest {

    private fun backendOf(vararg names: String): Pair<Map<String, InMemorySharedPreferences>, AndroidPrefsBackend> {
        val prefs = names.associateWith { InMemorySharedPreferences() }
        return prefs to AndroidPrefsBackend(
            prefsOf = { name -> prefs[name] ?: InMemorySharedPreferences() },
            namesOf = { prefs.keys.toList() },
        )
    }

    @Test
    fun `A1 Android 投影能枚举 store 且 keys 等于端内键`() {
        val (prefs, backend) = backendOf("app_settings")
        prefs.getValue("app_settings").edit()
            .putString("dark_mode", "dark")
            .putBoolean("show_quick_actions", true)
            .putInt("eula_accepted_version", 4)
            .commit()

        assertTrue("storeNames() 应能枚举出端内 store", "app_settings" in backend.storeNames())
        val snap = backend.readStore("app_settings")!!
        assertEquals(setOf("dark_mode", "show_quick_actions", "eula_accepted_version"), snap.keys.keys)
        assertEquals("app_settings", snap.rawName)
    }

    @Test
    fun `A2 六种带类型值读出来与端内一致`() {
        val (prefs, backend) = backendOf("app_settings")
        prefs.getValue("app_settings").edit()
            .putString("dark_mode", "dark")
            .putBoolean("show_quick_actions", true)
            .putInt("eula_accepted_version", 4)
            .putLong("last_auto_update_check_at", 1_760_000_000_000L)
            .putFloat("card_today_dinner_cache", 12.5f)
            .putStringSet("favorite_seats", mutableSetOf("A-1", "B-2"))
            .commit()

        val snap = backend.readStore("app_settings")!!
        assertEquals(StoreValue.S("dark"), snap.keys["dark_mode"])
        assertEquals(StoreValue.B(true), snap.keys["show_quick_actions"])
        assertEquals(StoreValue.I(4), snap.keys["eula_accepted_version"])
        assertEquals(StoreValue.L(1_760_000_000_000L), snap.keys["last_auto_update_check_at"])
        assertEquals(StoreValue.F(12.5f), snap.keys["card_today_dinner_cache"])
        assertEquals(StoreValue.SetValue(listOf("A-1", "B-2")), snap.keys["favorite_seats"])
    }

    @Test
    fun `A2 set 空数组不等于键不存在`() {
        val (prefs, backend) = backendOf("venue_favorites")
        // 空集合是合法值（≠ 键不存在）：SharedPreferences 的 putStringSet(empty) 存下一个空 <set>
        prefs.getValue("venue_favorites").edit()
            .putStringSet("favorite_venue_ids", mutableSetOf())
            .commit()

        val snap = backend.readStore("venue_favorites")!!
        assertEquals(StoreValue.SetValue(emptyList()), snap.keys["favorite_venue_ids"])
        assertTrue("空集合 ≠ 键不存在：键必须还在 keys 里", snap.keys.containsKey("favorite_venue_ids"))

        // 键不存在 = 该键不在 keys 里
        val absent = backend.readStore("venue_favorites")!!
        assertFalse(absent.keys.containsKey("not_written_key"))
    }
}
