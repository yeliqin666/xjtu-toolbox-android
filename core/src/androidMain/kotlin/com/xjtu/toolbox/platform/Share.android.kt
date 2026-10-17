package com.xjtu.toolbox.platform

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberShareLink(): (title: String, url: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { title, url ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, title)
                .putExtra(Intent.EXTRA_TEXT, "$title\n$url")
            context.startActivity(Intent.createChooser(send, "分享到"))
        }
    }
}
