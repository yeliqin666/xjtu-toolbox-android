package com.xjtu.toolbox.platform

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android 侧真实现 —— 与搬迁前 :app `community/DiscussionModeration.kt` 里的
 * `shareLink(context, title, url)` **逐字一致**：同样的 `ACTION_SEND` / `text/plain`、
 * 同样的 `EXTRA_SUBJECT` + `EXTRA_TEXT="$title\n$url"`、同样的 `createChooser(send, "分享到")`，
 * 也照样不套 `runCatching`（分享面板由系统提供，打不开时该暴露出来，而不是静默）。
 */
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
