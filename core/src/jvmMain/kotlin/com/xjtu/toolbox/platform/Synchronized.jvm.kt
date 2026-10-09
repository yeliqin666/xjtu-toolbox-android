package com.xjtu.toolbox.platform

/** JVM：与 Android 同一份实现。 */
actual inline fun <T> synchronizedBlock(lock: Any, block: () -> T): T = synchronized(lock) { block() }
