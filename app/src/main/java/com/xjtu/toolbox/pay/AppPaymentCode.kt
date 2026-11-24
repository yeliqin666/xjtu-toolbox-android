package com.xjtu.toolbox.pay

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.platform.keyValueStore

/** Android 侧的付款码适配。 */
@Composable
fun PaymentCodeDialog(
    site: SiteSession,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 录屏、投屏、共享屏幕时系统把付款码这块遮掉（不影响自己截图）
    val view = LocalView.current
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) DisposableEffect(view) {
        val old = view.contentSensitivity
        view.contentSensitivity = View.CONTENT_SENSITIVITY_SENSITIVE
        onDispose { view.contentSensitivity = old }
    }
    // 调亮付款码所在的那个窗口：放在 Compose Dialog 里时是 Dialog 自己的窗口
    // （那里的 LocalContext 是包了一层的 ContextThemeWrapper，强转 Activity 会落空，亮度就一直没变）
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: (context as? Activity)?.window
            ?: return@DisposableEffect onDispose {}
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
        // 券是各人自己的，按账号分开存；不然切了账号会把上一个人勾的券同步到新账号上
        selections = remember(com.xjtu.toolbox.account.AccountContext.safeSuffix()) {
            PaymentSelectionStore(keyValueStore("payment_code" + com.xjtu.toolbox.account.AccountContext.safeSuffix()))
        },
        onDismiss = onDismiss,
    )
}
