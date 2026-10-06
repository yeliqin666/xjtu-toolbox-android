package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** jvm 侧的降级：线性渐变。见 commonMain 的 [FallbackMeshBackground]。 */
@Composable
internal actual fun PlatformMeshBackground(
    modifier: Modifier,
    vertexColors: List<List<Color>>,
    periodMillis: Int,
    animated: Boolean,
    runForMillis: Long,
) {
    FallbackMeshBackground(modifier = modifier, vertexColors = vertexColors)
}
