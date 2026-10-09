package com.xjtu.toolbox.platform

/** Android：与原来的 `@Synchronized` 逐字同义。 */
actual inline fun <T> synchronizedBlock(lock: Any, block: () -> T): T = synchronized(lock) { block() }
