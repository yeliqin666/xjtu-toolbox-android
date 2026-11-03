package com.xjtu.toolbox.home

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 编辑态的拖拽手势层，挂成**页面根 Box 自己的修饰符**（祖先，不是盖在上面的兄弟覆盖层——
 * 兄弟覆盖层带 pointerInput 会独占命中路径，把下面的滚动列 / 顶栏 / 卡片全挡掉）。
 *
 * 两条关键决定：
 *
 * 1. **长按才起手**。用官方的 `detectDragGesturesAfterLongPress`：手指按下后只要没到长按时长
 *    就移动，它自己放弃、事件照常给到 `verticalScroll`。于是在**任何地方（包括卡片上）都能
 *    滑动页面**；反过来，滑动也绝不会被误判成拖拽（之前「松手后滑一下，刚放的卡片又跟着动」
 *    就是因为在 down 那一刻就把手势抢走了）。缩放把手、组排序把手同样走长按，规则统一。
 *
 * 2. **坐标在起手那一刻现场问，不用缓存值**。组区左上角由
 *    `rootCoords.localPositionOf(boardCoords, Offset.Zero)` 现算——`LayoutCoordinates` 是
 *    活对象，读的时候就是当前值。之前把 `onGloballyPositioned` 回填的 Offset 存进 session 当
 *    基准，页面一滚动它就过期，命中全落空（「按住没反应」就是这个原因）。
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
    /** 长按命中拖拽目标：这一场开始。[viewport] 是页面根局部坐标。 */
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
        // 这一场是否真的进入了拖拽（长按落在 Pointed 目标上才算）
        var started = false
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                started = false
                // 已经有别的拖拽在跑（悬浮条的图标长按会先一步起手）：绝不顶掉它。
                // 图标自己的长按和这一层是**同帧**触发的，顺序靠深度决定（子先父后），
                // 所以进到这里时 session.active 往往已经是 PALETTE——放行即可。
                if (session.active) return@detectDragGesturesAfterLongPress
                // 悬浮条自己管长按拖出：落在它矩形里一律放行
                val palette = session.paletteRect
                if (palette != null && palette.contains(offset)) return@detectDragGesturesAfterLongPress
                // ← 现场把组区原点刷新成"此刻"的值（不用任何回填缓存）
                session.refreshBoardFromCoordinates()
                val hit = currentGeometry().hit(
                    offset - session.boardOrigin,
                    currentHandlePx,
                    currentRemovePx,
                )
                if (hit is HomeHit.Pass) return@detectDragGesturesAfterLongPress
                started = true
                currentOnDown(hit, offset)
            },
            onDrag = { change, _ ->
                if (!started) return@detectDragGesturesAfterLongPress
                change.consume()
                currentOnMove(change.position)
            },
            onDragEnd = {
                if (started) {
                    started = false
                    currentOnUp()
                }
            },
            onDragCancel = {
                if (started) {
                    started = false
                    currentOnCancel()
                }
            },
        )
    }
}
