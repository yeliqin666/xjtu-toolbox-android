package com.xjtu.toolbox.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 编辑态的拖拽手势层（同 AOSP Launcher3 的 `DragLayer`）。
 *
 * 它挂成**页面根 Box 自己的一个修饰符**（那个 Box 铺满整页、自己不滚动）。所有拖拽手势只
 * 在这一个从不销毁的节点上，卡片、组标题、缩放把手都不再各自挂 `pointerInput`——这一条直接
 * 消灭了旧实现里最难缠的那类 bug：被拖的块一换组，它自己的手势协程就跟着节点被销毁，拖到
 * 一半手势断掉，只好把块钉死在源组画「残影」，结果就是跨组拖不动。
 *
 * **坐标系**：这一层是**页面根 Box 自己的修饰符**（不是盖在上面的兄弟覆盖层），
 * 所以 `PointerInputChange.position` 就是页面根局部坐标。板内坐标 = 它 − 组区容器左上角
 * （实测值，随滚动一起动），只做这一处减法；不存在旧实现「每块登记 boundsInRoot、再拿
 * 弹簧动画的中间帧当基准」那种被自己动画污染的坐标。
 *
 * **为什么必须是修饰符而不是兄弟覆盖层**：兄弟覆盖层一旦带 `pointerInput` 就会独占整条
 * 命中路径，把下面的滚动列 / 顶栏 / 卡片全挡掉（实测：除挂在它上层的悬浮条，其余什么都
 * 点不动）。挂在根 Box 上是「祖先」，子节点照样先收到 Main pass，我们只在 Initial pass
 * 决定抢不抢那一下 down。
 *
 * **与页面滚动、与下层点击的分工**（都在 [PointerEventPass.Initial] 上决断，比 `verticalScroll`
 * 的 Main pass 早一步；这一层盖在最上层，命中路径里也排在前面）：
 * - 落点是拖拽目标（卡片体 / 缩放把手 / 组排序把手）→ 消费掉 down，从滚动手里抢过来，
 *   之后的 move 也逐个消费，页面于是不跟手滚，改为顶到屏幕边缘时自动滚（见 [HomeScreen]）。
 * - 落点是 [HomeHit.Pass]（移除按钮、删除组按钮、组名、空白、悬浮条）→ **不消费**，
 *   down 原样留给下层的点击节点与 `verticalScroll`，它们照常工作。
 */
@Composable
internal fun Modifier.homeDragLayer(
    session: HomeDragSession,
    /** 起手快照推出的几何表：命中测试的唯一依据（读快照，不回读任何节点）。 */
    geometry: () -> HomeBoardGeometry,
    /** 缩放把手热区边长（px）。 */
    handlePx: Float,
    /** 移除按钮热区边长（px）。 */
    removePx: Float,
    /** 手势落点是拖拽目标：这一场开始。[viewport] 是页面根局部坐标。 */
    onDown: (hit: HomeHit, viewport: Offset) -> Unit,
    /** 手指移动：[viewport] 供自动滚动判断。 */
    onMove: (viewport: Offset) -> Unit,
    /** 手指抬起（一次正常结束）。 */
    onUp: () -> Unit,
    /** 手势被系统抢走：整场作废，布局回退到快照。 */
    onCancel: () -> Unit,
): Modifier {
    // 手势协程只该启动一次（key = Unit），但回调每次重组都是新的——经 rememberUpdatedState
    // 读最新值，协程里读到的永远是这一帧的回调，不会被首次组合的旧闭包困住
    val currentGeometry by rememberUpdatedState(geometry)
    val currentHandlePx by rememberUpdatedState(handlePx)
    val currentRemovePx by rememberUpdatedState(removePx)
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnUp by rememberUpdatedState(onUp)
    val currentOnCancel by rememberUpdatedState(onCancel)

    return this.pointerInput(Unit) {
        awaitEachGesture {
            // Initial pass：比 verticalScroll（Main pass）早看到 down，才能决定抢不抢
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val viewport = down.position
            // 板内坐标 = 页面坐标 − 组区容器左上角（实测、随滚动一起动）
            val board = viewport - session.boardOrigin

            // 悬浮条（球 / 展开的长条）自己管长按拖出：落在它矩形里一律放行
            val palette = session.paletteRect
            if (palette != null && palette.contains(viewport)) return@awaitEachGesture

            val hit = currentGeometry().hit(board, currentHandlePx, currentRemovePx)
            if (hit is HomeHit.Pass) return@awaitEachGesture // 不消费：下层点击与滚动照常

            down.consume() // 抢过来：拖动期间页面不跟手滚
            currentOnDown(hit, viewport)

            val id = down.id
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == id } ?: break
                    if (!change.pressed) {
                        released = true
                        break
                    }
                    change.consume()
                    currentOnMove(change.position)
                }
            } finally {
                // 抬起 = 正常结束；协程被取消（系统抢走 / 离开组合）= 作废回退
                if (released) currentOnUp() else currentOnCancel()
            }
        }
    }
}
