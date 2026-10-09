package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Android：直接读 Configuration 的真值 —— **与搬迁前逐字一致**，行为零变化。 */
@Composable
actual fun smallestScreenWidthDp(): Int =
    LocalConfiguration.current.smallestScreenWidthDp
