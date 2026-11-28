package com.xjtu.toolbox.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class StableLazyListKeysTest {
    @Test
    fun duplicateRecordsKeepDistinctKeysWhenOtherRowsMove() {
        val before = listOf("apply:a", "apply:b", "apply:a").stableLazyListKeys { it }
        val after = listOf("apply:b", "apply:a", "apply:a").stableLazyListKeys { it }

        assertEquals(3, before.toSet().size)
        assertEquals(listOf(before[1], before[0], before[2]), after)
    }

    @Test
    fun identitiesContainingSeparatorsStayDistinct() {
        val keys = listOf("record", "record", "record#1", "record#1#0").stableLazyListKeys { it }

        assertEquals(keys.size, keys.toSet().size)
    }
}
