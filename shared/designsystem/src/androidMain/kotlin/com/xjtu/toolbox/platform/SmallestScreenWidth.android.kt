package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Android：直接读 Configuration。 */
@Composable
actual fun smallestScreenWidthDp(): Int =
    LocalConfiguration.current.smallestScreenWidthDp
