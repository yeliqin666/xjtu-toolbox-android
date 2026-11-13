package com.xjtu.toolbox.ui

import androidx.compose.ui.Modifier

/** 密码、密钥这类内容：Android 上录屏、投屏、共享屏幕时由系统遮住；其他端原样返回。 */
expect fun Modifier.sensitive(): Modifier
