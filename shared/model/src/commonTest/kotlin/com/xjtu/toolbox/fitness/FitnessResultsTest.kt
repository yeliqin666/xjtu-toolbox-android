package com.xjtu.toolbox.fitness

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FitnessResultsTest {
    private fun score(items: List<FitnessItem>) = FitnessScore(
        "", "", "--", "未测", "", "", "", "", items,
    )

    @Test
    fun partialResultsAreVisibleBeforeTotalIsCalculated() {
        assertTrue(score(listOf(FitnessItem("50 米", "8.1", "良好", ""))).hasResults())
        assertFalse(score(listOf(FitnessItem("50 米", "未测", "缺项", ""))).hasResults())
        assertFalse(score(emptyList()).hasResults())
    }
}
