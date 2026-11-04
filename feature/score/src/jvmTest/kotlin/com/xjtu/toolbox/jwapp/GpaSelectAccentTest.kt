package com.xjtu.toolbox.jwapp

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class GpaSelectAccentTest {
    private val amber = Color(0xFFF59E0B)
    private val teal = Color(0xFF00897B)

    @Test fun usesAmberOnBlueAndGreyThemes() {
        assertEquals(amber, gpaSelectAccentFor(Color(0xFF3482FF)))
        assertEquals(amber, gpaSelectAccentFor(Color(0xFF808080)))
    }

    @Test fun avoidsCollidingWithOrangeThemes() {
        assertEquals(teal, gpaSelectAccentFor(Color(0xFFFF8A2B)))
    }
}
