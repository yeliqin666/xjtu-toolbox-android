package com.xjtu.toolbox.platform

/** Web：单线程事件循环，直接执行（见 commonMain 的说明）。 */
actual inline fun <T> synchronizedBlock(lock: Any, block: () -> T): T = block()
