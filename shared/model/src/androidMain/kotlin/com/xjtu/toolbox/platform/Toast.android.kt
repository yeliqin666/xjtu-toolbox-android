package com.xjtu.toolbox.platform

import android.widget.Toast

/** Android 侧真实现：`android.widget.Toast` 短提示。 */
actual fun showBriefMessage(message: String) {
    androidPlatformContext()?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
}
