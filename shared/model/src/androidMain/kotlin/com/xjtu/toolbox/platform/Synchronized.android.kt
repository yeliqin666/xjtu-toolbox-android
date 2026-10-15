package com.xjtu.toolbox.platform

actual inline fun <T> synchronizedBlock(lock: Any, block: () -> T): T = synchronized(lock) { block() }
