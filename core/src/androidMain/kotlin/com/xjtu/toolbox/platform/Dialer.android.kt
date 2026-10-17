package com.xjtu.toolbox.platform

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberPhoneDialer(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { number: String ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            }
            Unit
        }
    }
}
