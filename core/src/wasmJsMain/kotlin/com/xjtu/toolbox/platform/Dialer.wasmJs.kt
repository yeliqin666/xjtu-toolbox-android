package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/**
 * Web（Kotlin/Wasm）端：空实现。
 *
 * 浏览器有 `tel:` 协议（`location.href = "tel:..."` 在手机上会拉起拨号盘），但桌面浏览器
 * 通常无处理程序。真正要做时只改这一个 actual，`rememberPhoneDialer()` 的调用点不动。
 */
@Composable
actual fun rememberPhoneDialer(): (String) -> Unit = {}
