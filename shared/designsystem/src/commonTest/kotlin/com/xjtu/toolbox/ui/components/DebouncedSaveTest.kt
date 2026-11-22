package com.xjtu.toolbox.ui.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「即改即存」表单的防抖保存（小岱设置页改名后马上返回，名字没存上）。 */
class DebouncedSaveTest {

    /**
     * 复现根因：Compose 先 forget 后 remember 的 `rememberCoroutineScope`，再跑 onDispose。
     * 旧写法在 onDispose 里看 `job.isActive`，此时它已经是 false ⇒ 补存被跳过。
     */
    @Test
    fun `旧写法：作用域先被取消，onDispose 里 isActive 已经是 false，补存被跳过`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        var saves = 0
        val pendingSaveJob: Job = scope.launch { delay(500); saves++ }
        scope.cancel() // rememberCoroutineScope 被 forget
        // 旧 onDispose：if (pendingSaveJob?.isActive == true) { cancel(); saveNow() }
        if (pendingSaveJob.isActive) saves++
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, saves, "旧写法一次都没存上 —— 这就是 bug")
    }

    @Test
    fun `离开页面时作用域先被取消，挂着的改动仍然补存`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val saved = mutableListOf<String>()
        var name = "小岱"
        val saver = DebouncedSave(scope, 500) { saved += name }

        name = "阿岱"
        saver.schedule()
        advanceTimeBy(100); runCurrent()  // 500ms 内就按了返回
        scope.cancel()                    // 与 Compose 的 forget 顺序一致：作用域先没
        saver.flush()                     // 再轮到 onDispose

        assertEquals(listOf("阿岱"), saved)
        assertFalse(saver.pending)
    }

    @Test
    fun `连续输入只写最后一次，写完再 flush 不重复写`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        var name = ""
        val saved = mutableListOf<String>()
        val saver = DebouncedSave(scope, 500) { saved += name }

        for (c in listOf("阿", "阿岱", "阿岱岱")) {
            name = c
            saver.schedule()
            advanceTimeBy(100); runCurrent()
        }
        assertTrue(saver.pending)
        advanceTimeBy(600); runCurrent()
        assertEquals(listOf("阿岱岱"), saved)

        saver.flush()
        assertEquals(1, saved.size, "没有新改动时离开页面不再写")
        scope.cancel()
    }

    @Test
    fun `立刻保存会撤掉挂着的那次`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        var saves = 0
        val saver = DebouncedSave(scope, 500) { saves++ }

        saver.schedule()
        saver.now()
        advanceTimeBy(1_000); runCurrent()
        saver.flush()
        assertEquals(1, saves)
        scope.cancel()
    }
}
