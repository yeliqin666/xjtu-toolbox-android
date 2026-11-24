@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** 网页：触屏设备交给 `tel:` 拨号，电脑浏览器复制号码并提示。 */
@Composable
actual fun rememberPhoneDialer(): (String) -> Unit {
    val copy = rememberCopyText()
    return remember(copy) { { number -> if (webIsTouchDevice()) webOpenTel(number) else copy(number) } }
}

@JsFun("() => (navigator.maxTouchPoints || 0) > 0 && window.matchMedia('(pointer: coarse)').matches")
private external fun webIsTouchDevice(): Boolean

@JsFun("(number) => { window.location.href = 'tel:' + number; }")
private external fun webOpenTel(number: String)
