package com.xjtu.toolbox.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

/**
 * 【临时诊断读数 —— 定位完就删】编辑态显示在顶部，无手势、不参与命中。
 *
 * 目的：把拖拽链上每个量的**实测值**摊出来，一次就能判断是哪一环错了：
 * - `P` 手指（页面坐标）、`B` 手指（板内坐标）、`G` 幽灵左上角（板内坐标）
 * - `O` 组区原点、`W` 组区宽
 * - `mode`/`tile` 当前拖拽身份；`target`/`cell` 手指当前认定的目标组与落点格
 * - `dragTile` 被拖那块在**实时预览布局**里的「组 @格位」（`--` = 预览里没它了，
 *   那就是模型把块弄丢）
 * - `base` 是否还持有起手快照；`settle` 是否在结算动画
 *
 * 判读：`P` 与 `G` 应只差一个固定的 `grab`；`dragTile` 若在拖动中变成 `--`，说明预览把
 * 块弄丢了；松手后看 `dragTile` 落到哪一格、和预期是否一致、是否消失。
 */
internal const val HOME_DRAG_DEBUG = true

@Composable
internal fun HomeDragDebugOverlay(
    session: HomeDragSession,
    geometry: HomeBoardGeometry,
    pointerBoard: Offset,
    liveLayout: HomeLayout,
    targetGroup: String?,
) {
    if (!HOME_DRAG_DEBUG) return
    val board = session.boardOrigin
    val gBoard = pointerBoard - session.grab
    val hitTile = session.tileId
    val inPreview = hitTile
        ?.let { liveLayout.locate(it) }
        ?.let { (g, t) -> "${g.id} @(${t.x.round1()},${t.y.round1()})" }
        ?: "--"
    val cell = targetGroup?.let { geometry.cellAt(it, gBoard) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 84.dp, start = 8.dp, end = 8.dp)
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        listOf(
            "P=(${session.pointer.x.round1()},${session.pointer.y.round1()})  B=(${pointerBoard.x.round1()},${pointerBoard.y.round1()})",
            "G=(${gBoard.x.round1()},${gBoard.y.round1()})  grab=(${session.grab.x.round1()},${session.grab.y.round1()})",
            "O=(${board.x.round1()},${board.y.round1()})  W=${session.boardWidth.round1()}  ready=${geometry.ready}",
            "mode=${session.mode}  tile=${hitTile ?: "-"}  fromPalette=${session.fromPalette}",
            "target=${targetGroup ?: "-"}  cell=${cell?.let { "(${it.x.round1()},${it.y.round1()})" } ?: "-"}",
            "dragTile in preview = $inPreview",
            "base=${if (session.base != null) "Y" else "N"}  settle=${session.settling}",
        ).forEach { Text(it, color = Color.White, fontSize = 11.sp) }
    }
}

private fun Float.round1(): String {
    val v = kotlin.math.round(this * 10f) / 10f
    return if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
}
