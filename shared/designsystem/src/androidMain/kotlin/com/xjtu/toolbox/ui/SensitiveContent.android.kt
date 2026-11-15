package com.xjtu.toolbox.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.sensitiveContent

actual fun Modifier.sensitive(): Modifier = sensitiveContent()
