package com.xjtu.toolbox.platform

/** jvm / 桌面端：打到标准输出。 */
actual fun showBriefMessage(message: String) {
    println("(提示) $message")
}
