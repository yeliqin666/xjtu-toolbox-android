package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「即改即存」表单的防抖保存：连续输入只写最后一次，离开页面时把还没写的那次补上。
 *
 * 为什么单独抽出来：之前的写法是在 `DisposableEffect.onDispose` 里看「挂着的 job 还 `isActive` 吗」
 * 来决定补不补存。可 Compose 按 remember 的**倒序**逐个 forget：写在后面的
 * `rememberCoroutineScope()` 先被取消，挂着的 job 跟着进入 cancelling，等轮到 onDispose 时
 * `isActive` 已经是 false —— 补存永远被跳过。表现就是「改完小岱名字马上返回，名字没存上」。
 *
 * 这里用一个与协程作用域生死无关的 [pending] 标记判断「有没有没写的改动」，
 * 补存（[flush]）是同步调用，不需要作用域还活着。
 */
class DebouncedSave(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val save: () -> Unit,
) {
    private var job: Job? = null

    /** 有改动还没写盘。 */
    var pending: Boolean = false
        private set

    /** 记一笔改动，[delayMs] 内没有新改动就写盘。 */
    fun schedule() {
        pending = true
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            job = null
            flush()
        }
    }

    /** 立刻写盘（键盘「完成」、开关、下拉这类一次性操作），顺带撤掉挂着的那次。 */
    fun now() {
        job?.cancel()
        job = null
        pending = false
        save()
    }

    /** 有没写的改动才写；离开页面时调用。不依赖 [scope] 是否还活着。 */
    fun flush() {
        if (pending) now()
    }
}

/**
 * Compose 侧的 [DebouncedSave]：离开组合（返回、关掉浮层、切 tab）时自动 [DebouncedSave.flush]。
 * [save] 每次调用都取最新的那份闭包。
 */
@Composable
fun rememberDebouncedSave(delayMs: Long = 500L, save: () -> Unit): DebouncedSave {
    val latest = rememberUpdatedState(save)
    val scope = rememberCoroutineScope()
    val saver = remember(scope, delayMs) { DebouncedSave(scope, delayMs) { latest.value() } }
    DisposableEffect(saver) { onDispose { saver.flush() } }
    return saver
}
