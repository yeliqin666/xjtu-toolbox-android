package com.xjtu.toolbox.ui.glass.coui

import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

@Stable
class CouiTopAppBarSearchState internal constructor(
    internal val renderer: CouiFloatingRenderer,
    expandedState: State<Boolean>,
) {
    internal val motion = CouiSearchMotion(true, expandedState)
    internal var sourceCenter by mutableStateOf<Offset?>(null)
    internal var expandedWidth by mutableFloatStateOf(0f)
    /** 0 = 收起成圆钮，1 = 展开成搜索框；取消胶囊的融球分裂用同一条进度。 */
    val progress: Float get() = motion.progress
    /** 普通顶栏动作随搜索展开淡出/缩小用的进度。 */
    val toolbarAlpha: Float get() = motion.toolbarAlpha
}

@RequiresApi(34)
@Composable
fun rememberCouiTopAppBarSearchState(
    expanded: Boolean,
    renderer: CouiFloatingRenderer = remember { CouiFloatingRenderer.standalone() },
): CouiTopAppBarSearchState {
    val expandedState = rememberUpdatedState(expanded)
    val state = remember(renderer) { CouiTopAppBarSearchState(renderer, expandedState) }
    SideEffect { state.motion.updateTarget(expanded) }
    DisposableEffect(state) { onDispose { state.motion.dispose() } }
    return state
}
