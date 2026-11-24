package com.xjtu.toolbox.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrecomposeTargetTest {
    private val order = listOf("home", "pidai", "card", "profile")

    @Test
    fun 后台按底栏顺序做() {
        assertEquals("pidai", precomposeTarget(order, setOf("profile", "pidai"), selected = "home"))
    }

    @Test
    fun 当前页不进后台队列() {
        assertEquals("profile", precomposeTarget(order, setOf("pidai", "profile"), selected = "pidai"))
    }

    @Test
    fun 都组合过了就没有活() {
        assertNull(precomposeTarget(order, emptySet(), selected = "home"))
    }
}
