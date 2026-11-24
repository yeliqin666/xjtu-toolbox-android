@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** 网页：有 Web Share 就弹系统分享，没有（多数电脑浏览器）就复制链接并提示。 */
@Composable
actual fun rememberShareLink(): (title: String, url: String) -> Unit {
    val copy = rememberCopyText()
    return remember(copy) { { title, url -> if (!webShare(title, url)) copy(url) } }
}

@JsFun(
    """
(title, url) => {
  if (!navigator.share) return false;
  navigator.share({ title: title, url: url }).catch(() => {});
  return true;
}
""",
)
private external fun webShare(title: String, url: String): Boolean
