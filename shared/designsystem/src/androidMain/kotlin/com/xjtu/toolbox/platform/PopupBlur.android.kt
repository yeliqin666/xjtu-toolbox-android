package com.xjtu.toolbox.platform

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp

/** Android 12+ 的跨窗口模糊（FLAG_BLUR_BEHIND）：系统关了跨窗口模糊（省电、部分机型）就不糊。 */
@Composable
actual fun rememberPopupBlurBehind(radius: Dp, active: Boolean): Boolean {
    val view = LocalView.current
    val px = with(LocalDensity.current) { radius.roundToPx() }
    val supported = remember(view) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            view.context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
    }
    DisposableEffect(view, px, active, supported) {
        if (supported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val root = view.rootView
            val params = root.layoutParams as? WindowManager.LayoutParams
            val wm = view.context.getSystemService(WindowManager::class.java)
            if (params != null && wm != null) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                // 收起时先撤掉模糊，和面板淡出一起走
                params.blurBehindRadius = if (active) px else 0
                runCatching { wm.updateViewLayout(root, params) }
            }
        }
        onDispose { }
    }
    return supported
}
