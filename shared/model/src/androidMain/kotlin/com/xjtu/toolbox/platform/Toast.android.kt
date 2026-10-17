package com.xjtu.toolbox.platform

import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Android 侧真实现：`android.widget.Toast` 短提示。调用方可能在后台线程，统一切回主线程再弹。 */
actual fun showBriefMessage(message: String) {
    val context = androidPlatformContext() ?: return
    val show = { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    if (Looper.myLooper() == Looper.getMainLooper()) show() else Handler(Looper.getMainLooper()).post(show)
}
