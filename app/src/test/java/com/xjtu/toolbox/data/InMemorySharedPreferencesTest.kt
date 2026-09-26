package com.xjtu.toolbox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
class InMemorySharedPreferencesTest {

    @Test
    fun removeDeletesCommittedValue() {
        val p = InMemorySharedPreferences()
        p.edit().putString("k", "v").commit()
        p.edit().remove("k").commit()
        assertFalse(p.contains("k"))
        assertNull(p.getString("k", null))
    }

    @Test
    fun putNullRemovesAndStringSetRoundTrips() {
        val p = InMemorySharedPreferences()
        p.edit().putString("k", "v").putStringSet("s", mutableSetOf("a", "b")).commit()
        p.edit().putString("k", null).commit()
        assertFalse(p.contains("k"))
        assertEquals(setOf("a", "b"), p.getStringSet("s", null))
    }

    @Test
    fun clearThenPutInSameEditKeepsNewValue() {
        val p = InMemorySharedPreferences()
        p.edit().putInt("old", 1).commit()
        p.edit().clear().putInt("new", 2).commit()
        assertFalse(p.contains("old"))
        assertEquals(2, p.getInt("new", 0))
    }
}
