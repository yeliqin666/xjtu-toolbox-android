package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 盖满整屏的浮层（搜索、扫一扫），放在主界面最外层用。画在主窗口里，跟着主窗口铺到状态栏和小白条后面；
 * 拦住触摸，点击不会漏到下面的页面。返回键由内容自己的 BackHandler 接。
 *
 * 不用 Dialog：Dialog 是独立窗口，系统按默认给它画黑色状态栏、白色小白条，内容怎么铺都铺不过去。
 */
@Composable
fun FullScreenOverlay(content: @Composable BoxScope.() -> Unit) {
    // 空的 pointerInput 就够：命中测试停在最上面这一层，下面的兄弟节点收不到
    Box(Modifier.fillMaxSize().pointerInput(Unit) {}, content = content)
}
