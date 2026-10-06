package com.xjtu.toolbox.platform

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android 侧真实现 —— 与搬迁前 :app `YellowPageScreen` 里那段 `runCatching { context.startActivity(
 * Intent(ACTION_DIAL, Uri.parse("tel:$number"))) }` **逐字一致**（`runCatching` 吞掉的异常在这里
 * 也照旧吞掉：没有拨号盘或被策略拦下时，不崩、只是没反应）。
 */
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
