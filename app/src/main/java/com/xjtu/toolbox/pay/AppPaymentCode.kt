package com.xjtu.toolbox.pay

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.platform.keyValueStore

/** Android 侧的付款码适配。 */
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
