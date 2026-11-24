package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageSignalTest {

    @Test
    fun `同一个信号对象跟着状态变`() {
        val shown = mutableStateOf(true)
        val signal = PageSignal { shown.value }
        assertTrue(signal.value)
        shown.value = false
        assertFalse(signal.value)
        assertFalse(signal.peek())
    }

    @Test
    fun `value 读会登记订阅，peek 不会`() {
        val shown = mutableStateOf(true)
        val signal = PageSignal { shown.value }
        val reads = mutableListOf<Any>()
        Snapshot.observe(readObserver = { reads += it }) { signal.peek() }
        assertEquals(0, reads.size)
        Snapshot.observe(readObserver = { reads += it }) { signal.value }
        assertTrue(reads.isNotEmpty())
    }
}
