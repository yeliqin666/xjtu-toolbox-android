package com.xjtu.toolbox.platform

/** 桌面窗口的提示出口：外壳挂上 Snackbar 后设 [sink]；没挂（serve、单测）时打到标准输出。 */
object JvmBriefMessages {
    @Volatile
    var sink: ((String) -> Unit)? = null
}

actual fun showBriefMessage(message: String) {
    JvmBriefMessages.sink?.invoke(message) ?: println("(提示) $message")
}
