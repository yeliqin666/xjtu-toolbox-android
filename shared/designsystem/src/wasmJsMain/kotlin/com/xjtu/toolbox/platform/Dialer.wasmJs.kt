package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/** Web（Kotlin/Wasm）端：空实现。 */
@Composable
actual fun rememberPhoneDialer(): (String) -> Unit = {}
