package com.xjtu.toolbox.attendance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WaterTypeTest {

    @Test
    fun `known codes map to their status`() {
        assertEquals(WaterType.NORMAL, WaterType.fromCode("NORMAL"))
        assertEquals(WaterType.LATE, WaterType.fromCode("LATE"))
        assertEquals(WaterType.ABSENCE, WaterType.fromCode("ABSENT"))
        assertEquals(WaterType.LEAVE, WaterType.fromCode("LEAVE"))
    }

    @Test
    fun `unknown or blank code must not silently count as normal`() {
        assertEquals(WaterType.UNKNOWN, WaterType.fromCode("NOT_REQUIRED"))
        assertEquals(WaterType.UNKNOWN, WaterType.fromCode(null))
        assertEquals(WaterType.UNKNOWN, WaterType.fromCode(""))
        assertEquals(WaterType.UNKNOWN, WaterType.fromCode("SOME_NEW_STATUS"))
        assertNotEquals(WaterType.NORMAL, WaterType.fromCode("SOME_NEW_STATUS"))
    }

    @Test
    fun `fromValue for unmapped int also falls back to unknown, not normal`() {
        assertEquals(WaterType.UNKNOWN, WaterType.fromValue(999))
        assertNotEquals(WaterType.NORMAL, WaterType.fromValue(999))
    }
}
