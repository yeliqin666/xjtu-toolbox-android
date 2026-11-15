package com.xjtu.toolbox.ui.glass.coui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.xjtu.toolbox.ui.glass.coui.CouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.couiBackdrop
import com.xjtu.toolbox.ui.glass.coui.rememberCouiBackdrop

internal val LocalCouiMenuBackdrop = staticCompositionLocalOf<CouiBackdrop?> { null }

/** 当前窗口已提供的采样源；不新建源或持有其消费引用，未提供时为 null。 */
@Composable
fun rememberCouiWindowBackdrop(): CouiBackdrop? = LocalCouiMenuBackdrop.current

/** 内容修饰符挂到所属窗口的内容根节点; Popup 菜单自身不进入采样树。 */
@Composable
fun ProvideCouiMenuBackdrop(content: @Composable (Modifier) -> Unit) {
    ProvideCouiMenuBackdrop(isolateContent = true, content = content)
}

/**
 * isolateContent 为 false 时, 空闲窗口保留绘制指令而不强制整窗离屏。
 * 菜单及材质弹窗在实际消费期间恢复原分辨率离屏, 并保留所有父窗口源。
 * 此模式要求内容中的 Clear、DstIn 等目标混合在其所属组件内隔离。
 * 优先用于已核对的不透明静态页面根; 透明内容、缩放或分数位移须独立验证栅格化结果。
 * 不确定内容的合成要求时, 使用保留隔离的单参数入口。
 */
@Composable
fun ProvideCouiMenuBackdrop(
    isolateContent: Boolean,
    content: @Composable (Modifier) -> Unit,
) {
    val backdrop = rememberCouiBackdrop(parent = LocalCouiMenuBackdrop.current, diagnosticName = "menu-window")
    SideEffect { backdrop.setIsolateWhenIdle(isolateContent) }
    CompositionLocalProvider(LocalCouiMenuBackdrop provides backdrop) {
        content(Modifier.couiBackdrop(backdrop))
    }
}
