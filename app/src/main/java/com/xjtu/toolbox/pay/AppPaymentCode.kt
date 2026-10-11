package com.xjtu.toolbox.pay

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.platform.keyValueStore

/**
 * Android 侧的付款码**适配**（`:app` 的 `pay/` 只剩这一个文件）—— 屏与取数都搬走了：
 *  - 屏：[com.xjtu.toolbox.pay.PaymentCodeScreen]（`:core`，与桌面同一份）；
 *  - 取数：[com.xjtu.toolbox.pay.PaymentCodeApi]（`:data`，与桌面同一份，**同包同名** ⇒
 *    这个文件里连 import 都不需要）。
 *
 * 这里只剩两件**Android 专属**的事：
 *  1. 弹窗期间把窗口调到**满亮**（`WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL`，
 *     离开时恢复原值）—— 扫码枪对着手机屏扫，暗屏扫不出来。桌面没有这一档（窗口亮度归
 *     系统电源策略管），所以它不进 `:core`；
 *  2. 加餐券勾选的落盘接到 Android 的键值缝上（`keyValueStore("payment_code")` 就是原来的
 *     `getSharedPreferences("payment_code")`，同一个文件、同一批键 ⇒ 老用户勾过的券一张不丢）。
 *
 * 「屏幕常亮」那一半在共享屏里（`:core` 的 `KeepScreenOn`，Android 的 actual 就是搬迁前
 * 那个 `FLAG_KEEP_SCREEN_ON` 的等价物 `view.keepScreenOn`），所以这里不再碰它。
 */
@Composable
fun PaymentCodeDialog(
    site: SiteSession,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(Unit) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val orig = window.attributes.screenBrightness
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = orig }
        }
    }

    PaymentCodeScreen(
        source = remember(site) { PaymentCodeApi(site) },
        selections = remember { PaymentSelectionStore(keyValueStore("payment_code")) },
        onDismiss = onDismiss,
    )
}
