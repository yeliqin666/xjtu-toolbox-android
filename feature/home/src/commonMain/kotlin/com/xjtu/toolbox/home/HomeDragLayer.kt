package com.xjtu.toolbox.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
 * 四个关键决定：
 *
 * 1. **缩放「按住就能拖」，移动与组排序「长按拿起再拖」。** 两种起手方式不一样，因为手指
 *    落在卡片上时有两种意图：划过是想滚页面、按住是想搬走它。缩放把手是右下角一小块热区，
 *    落在那里几乎只有一个意图（改大小），所以按下越过 slop 就起手；卡片体与组排序把手则先
 *    等长按（`awaitLongPressOrCancellation`）——长按成立之前**一个事件都不消费**，普通上下滑
 *    因此照常交给下面的滚动列；长按一旦成立立刻吃掉后续事件，滚动列再也插不进来。
 *    两种方式都在 down 那一刻、在 `PointerEventPass.Initial` 上自己判定归属：这一层是滚动列的
 *    **祖先**，Initial pass 又是祖先先看到，所以「这一下归拖拽还是归滚动」一次定死。
 *    用官方 `detectDragGestures` 系列做不到这点：它们要等 touch slop，而 slop 判定会「发现
 *    变更已被别人消费过就取消」，偏偏滚动列就在这条路径上。
 *
 * 2. **slop / 长按之后才起手**：手指没真的动（或没按够时长）就不算拖，于是「点一下卡片」
 *    不会被当成拖拽（编辑态里卡片自己没有点击手势，但移除按钮那条热区走 Pass，不受影响）。
 *
 * 3. **坐标在起手那一刻现场问，不用缓存值**。组区左上角由
 *    `rootCoords.localPositionOf(boardCoords, Offset.Zero)` 现算——`LayoutCoordinates` 是
 *    活对象，读的时候就是当前值。之前把 `onGloballyPositioned` 回填的 Offset 存进 session 当
 *    基准，页面一滚动它就过期，命中全落空（「按住没反应」就是这个原因）。
 *
 * 4. **放行的落点一个事件都不碰**：移除按钮 / 删除组 / 添加 / 组名 / 空白都是
 *    [HomeHit.Pass]，down 原样留给下层点击节点与滚动。
 *
 * 编辑态里想**滚动页面**就从这些地方起手：组间空隙、网格里的空格、组标题行（把手右侧）、
 * 头卡与刊头、页面空白——它们全是 [HomeHit.Pass]；从卡片体上滑也行，只要别先按够长按时长。
 * 拖到屏幕上下边缘时有自动滚动兜底。
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
    /** 长按「拿起」成立（只有移动卡片 / 组排序走这条）：给一次触感反馈。 */
    onPickUp: () -> Unit = {},
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
    val currentOnPickUp by rememberUpdatedState(onPickUp)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnUp by rememberUpdatedState(onUp)
    val currentOnCancel by rememberUpdatedState(onCancel)

    return this.pointerInput("homeDragLayer") {
        awaitEachGesture {
            // 这里**不**要求「没被别人消费」：我们在 Initial pass 上是最先看到 down 的，
            // 而且下面还要用消费与否来决定这一下归谁
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

            // 已经有别的拖拽在跑（多指）：绝不顶掉它，整场放行
            if (session.active) return@awaitEachGesture

            // ← 现场把组区原点刷成"此刻"的值（不用任何回填缓存）
            session.refreshBoardFromCoordinates()
            val hit = currentGeometry().hit(
                down.position - session.boardOrigin,
                currentHandlePx,
                currentRemovePx,
            )
            // 不归拖拽管的落点：down 留给人家的点击节点 / 滚动列
            if (hit is HomeHit.Pass) return@awaitEachGesture

            // 缩放把手 = 按住就能拖；卡片体与组排序把手 = 先长按拿起（见类头第 1 条）
            val pressToDrag = hit is HomeHit.Resize
            var started = false
            var finished = false
            try {
                if (pressToDrag) {
                    // 先吃掉 down，滚动列就再也拿不到它
                    down.consume()
                    val slop = viewConfiguration.touchSlop
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
                } else {
                    // 长按之前一个事件都不消费：普通上下滑照常归页面滚动。
                    // 长按没成立（抬手 / 滑过了 slop / 被下层消费）就是这一下与我们无关。
                    val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    currentOnPickUp()
                    started = true
                    currentOnDown(hit, press.position)
                    press.consume()
                    // 拿起之后的每一帧都在 Initial pass 上吃掉：这一层是滚动列的祖先，
                    // 先于它看到事件，手指再快也不会被滚动列抢走
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial)
                            .changes.firstOrNull { it.id == press.id } ?: break
                        if (!change.pressed) {
                            finished = true
                            currentOnUp()
                            break
                        }
                        currentOnMove(change.position)
                        change.consume()
                    }
                }
            } finally {
                // 手势被系统抢走 / 组合被销毁：整场作废（拖动中从不写草稿，清会话即可）
                if (started && !finished) currentOnCancel()
            }
        }
    }
}
