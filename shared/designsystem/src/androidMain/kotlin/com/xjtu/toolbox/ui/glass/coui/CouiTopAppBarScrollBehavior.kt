package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.runtime.Stable
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection

/** 先折叠顶栏，正文到顶后再展开；连接只挂到实际滚动容器的一个祖先。 */
@Stable
class CouiTopAppBarScrollBehavior internal constructor(val state: CouiCollapsingAppBarState) {
    val nestedScrollConnection: NestedScrollConnection get() = state.nestedScrollConnection
}
