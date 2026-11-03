package com.xjtu.toolbox.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize

/**
 * 【临时诊断层 —— 定位完就删】只在编辑态显示，无手势、不参与命中。
 *
 * 为什么要有它：拖拽链上现在有 `pointer`、`boardOrigin`、`pointerBoard`、几何表、
 * `grab`/`ghostOrigin` 多个量，只靠读代码已经无法判断到底是哪一环错了。这一层把**实测值**
 * 摊在屏幕上，并对几何表画框：
 * - 红框 = 几何表认为的组区矩形
 * - 黄框 = 每个组的矩形
 * - 青框 = 每一块（命中测试用的矩形）
 * - 绿十字 = 手指位置；品红十字 = 幽灵块左上角
 *
 * 判读方法：若青框**没有**正好压在真实卡片上，就是 `boardOrigin` 或几何表的问题，偏移量
 * 直接就是差值；若青框压得很准但幽灵不在手指下，就是 `grab` 算错。
 */
internal const val HOME_DRAG_DEBUG = true

@Composable
internal fun HomeDragDebugOverlay(
    session: HomeDragSession,
    geometry: HomeBoardGeometry,
    pointerBoard: Offset,
    handlePx: Float,
    removePx: Float,
    groupGapPx: Float,
    firstGapPx: Float,
) {
    if (!HOME_DRAG_DEBUG) return
    val board = session.boardOrigin
    val ready = geometry.ready
    val hit = if (ready) geometry.hit(pointerBoard, handlePx, removePx) else HomeHit.Pass
    val hitText = when (hit) {
        is HomeHit.Tile -> "Tile ${hit.groupId}/${hit.tileId}"
        is HomeHit.Resize -> "Resize ${hit.groupId}/${hit.tileId}"
        is HomeHit.GroupHandle -> "Handle ${hit.groupId}#${hit.index}"
        HomeHit.Pass -> "Pass"
    }
    val ghost = session.ghostOrigin

    // fillMaxSize：本函数不是 BoxScope 扩展，拿不到 matchParentSize
    Canvas(Modifier.fillMaxSize()) {
        if (!ready) return@Canvas
        val total = geometry.totalHeight(groupGapPx, firstGapPx)
        // 组区矩形（红）
        drawRect(
            Color.Red,
            topLeft = board,
            size = Size(geometry.width, total),
            style = Stroke(2f),
        )
        geometry.slots.forEach { slot ->
            // 组矩形（黄）
            drawRect(
                Color.Yellow,
                topLeft = Offset(board.x, board.y + slot.top),
                size = Size(geometry.width, slot.height),
                style = Stroke(1.5f),
            )
            slot.tiles.forEach { tile ->
                // 块矩形（青）：命中测试就是按这些矩形判的
                drawRect(
                    Color.Cyan,
                    topLeft = Offset(
                        board.x + tile.x * geometry.colUnit,
                        board.y + slot.gridTop + tile.y * geometry.rowUnit,
                    ),
                    // 与 hit() 判定的矩形逐字一致（不减 gap：命中区比视觉块大一圈）
                    size = Size(tile.w * geometry.colUnit, tile.h * geometry.rowUnit),
                    style = Stroke(1.5f),
                )
            }
        }
        // 手指（绿）
        if (session.active) {
            val p = session.pointer
            drawLine(Color.Green, Offset(p.x - 24f, p.y), Offset(p.x + 24f, p.y), 3f)
            drawLine(Color.Green, Offset(p.x, p.y - 24f), Offset(p.x, p.y + 24f), 3f)
            // 幽灵左上角（品红）
            drawLine(Color.Magenta, Offset(ghost.x - 16f, ghost.y), Offset(ghost.x + 16f, ghost.y), 3f)
            drawLine(Color.Magenta, Offset(ghost.x, ghost.y - 16f), Offset(ghost.x, ghost.y + 16f), 3f)
        }
    }

    Column(
        Modifier
            .padding(top = 72.dp, start = 8.dp)
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
            .padding(6.dp),
    ) {
        val lines = listOf(
            "O=(${board.x.round1()},${board.y.round1()}) W=${geometry.width.round1()}",
            "P=(${session.pointer.x.round1()},${session.pointer.y.round1()})",
            "B=(${pointerBoard.x.round1()},${pointerBoard.y.round1()})",
            "hit=$hitText",
            "mode=${session.mode} tile=${session.tileId ?: "-"}",
            "grab=(${session.grab.x.round1()},${session.grab.y.round1()})",
            "gO=(${ghost.x.round1()},${ghost.y.round1()})",
        )
        lines.forEach { Text(it, color = Color.White, fontSize = 10.sp) }
    }
}

private fun Float.round1(): String {
    val v = kotlin.math.round(this * 10f) / 10f
    return if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
}
