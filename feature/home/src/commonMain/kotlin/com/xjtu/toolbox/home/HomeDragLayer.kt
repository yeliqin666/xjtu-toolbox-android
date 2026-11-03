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
 * 编辑态的拖拽手势层，挂成**页面根 Box 自己的修饰符**（祖先，不是盖在上面的兄弟覆盖层——
 * 兄弟覆盖层带 pointerInput 会独占命中路径，把下面的滚动列 / 顶栏 / 卡片全挡掉）。
 *
 * 三条关键决定：
 *
 * 1. **按下即拖，不需要长按**（2026-11 复核按真机反馈改回）。用 `awaitEachGesture` 在
 *    `PointerEventPass.Initial` 上自己收 down：这一层是滚动列的**祖先**，Initial pass 又是
 *    祖先先看到，所以「这一下归拖拽还是归滚动」由我们在 down 那一刻一次性定死——
 *    落点是拖拽目标就 `consume` 吃掉，后到的 `verticalScroll`（Main pass）根本看不到它；
 *    落点是 [HomeHit.Pass]（移除按钮 / 删除组 / 组名 / 空白 / 悬浮条）就**不消费**，
 *    down 原样留给下层点击节点与滚动。用官方 `detectDragGestures` 系列的现成实现做不到这点：
 *    它们要等 touch slop，而 slop 判定会「发现变更已被别人消费过就取消」，偏偏滚动列就
 *    在这条路径上——所以这里自己判 slop。
 *
 * 2. **slop 之后才起手**：按下到移动超过 slop 之间什么都不做，于是「点一下卡片」不会被当成
 *    拖拽（编辑态里卡片自己没有点击手势，但移除按钮那条热区走 Pass，不受影响）。
 *
 * 3. **坐标在起手那一刻现场问，不用缓存值**。组区左上角由
 *    `rootCoords.localPositionOf(boardCoords, Offset.Zero)` 现算——`LayoutCoordinates` 是
 *    活对象，读的时候就是当前值。之前把 `onGloballyPositioned` 回填的 Offset 存进 session 当
 *    基准，页面一滚动它就过期，命中全落空（「按住没反应」就是这个原因）。
 *
 * 编辑态里想**滚动页面**就从这些地方起手：组间空隙、网格里的空格、组标题行（把手右侧）、
 * 头卡与刊头、页面空白——它们全是 [HomeHit.Pass]。拖到屏幕上下边缘时有自动滚动兜底。
 */
@Composable
internal fun Modifier.homeDragLayer(
    session: HomeDragSession,
    /** 起手快照推出的几何表：命中测试的唯一依据。 */
    geometry: () -> HomeBoardGeometry,
    /** 缩放把手热区边长（px）。 */
    handlePx: Float,
    /** 移除按钮热区边长（px）。 */
    removePx: Float,
    /** 命中拖拽目标：这一场开始。[viewport] 是页面根局部坐标。 */
    onDown: (hit: HomeHit, viewport: Offset) -> Unit,
    /** 手指移动：[viewport] 是页面根局部坐标。 */
    onMove: (viewport: Offset) -> Unit,
    onUp: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    // 手势协程只启动一次，但回调每次重组都是新的——经 rememberUpdatedState 读最新值
    val currentGeometry by rememberUpdatedState(geometry)
    val currentHandlePx by rememberUpdatedState(handlePx)
    val currentRemovePx by rememberUpdatedState(removePx)
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnUp by rememberUpdatedState(onUp)
    val currentOnCancel by rememberUpdatedState(onCancel)

    return this.pointerInput("homeDragLayer") {
        awaitEachGesture {
            // 这里**不**要求「没被别人消费」：我们在 Initial pass 上是最先看到 down 的，
            // 而且下面还要用消费与否来决定这一下归谁
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

            // 已经有别的拖拽在跑（悬浮条图标先一步起了手）：绝不顶掉它，整场放行
            if (session.active) return@awaitEachGesture
            // 落在悬浮条自己的矩形里：那是它的长按面板与拖出，放行
            val palette = session.paletteRect
            if (palette != null && palette.contains(down.position)) return@awaitEachGesture

            // ← 现场把组区原点刷成"此刻"的值（不用任何回填缓存）
            session.refreshBoardFromCoordinates()
            val hit = currentGeometry().hit(
                down.position - session.boardOrigin,
                currentHandlePx,
                currentRemovePx,
            )
            // 不归拖拽管的落点：down 留给人家的点击节点 / 滚动列
            if (hit is HomeHit.Pass) return@awaitEachGesture

            // 从这里开始这一场归拖拽：先吃掉 down，滚动列就再也拿不到它
            down.consume()

            val slop = viewConfiguration.touchSlop
            var started = false
            var finished = false
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial)
                        .changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        // 抬手：只有真的拖起来过才结算（没到 slop 就是一次点击）
                        if (started) {
                            finished = true
                            currentOnUp()
                        }
                        break
                    }
                    if (!started) {
                        if ((change.position - down.position).getDistance() > slop) {
                            started = true
                            // 起手位置用**此刻**的手指位置：grab 按它算，幽灵块正好在手指下
                            currentOnDown(hit, change.position)
                        }
                    } else {
                        currentOnMove(change.position)
                    }
                    change.consume()
                }
            } finally {
                // 手势被系统抢走 / 组合被销毁：整场作废（拖动中从不写草稿，清会话即可）
                if (started && !finished) currentOnCancel()
            }
        }
    }
}
