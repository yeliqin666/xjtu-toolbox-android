package com.xjtu.toolbox.platform

/** Web 端：交给页面根部的 Snackbar，`ToolboxWebApp` 负责挂上 [sink]。 */
object WebBriefMessages {
    var sink: ((String) -> Unit)? = null
}

actual fun showBriefMessage(message: String) {
    WebBriefMessages.sink?.invoke(message)
}
