package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * **不采样版的液态玻璃面**：与 `drawBackdrop` 同一组参数，只是不经过它的离屏层。
 *
 * 为什么不能干脆给 `drawBackdrop` 传一个空的采样源（库里有 `emptyBackdrop()`）：
 * `DrawBackdropNode` 不管有没有内容可采，**每帧都会 record + draw 一层卡片大小的离屏层**
 * （源为空也照录），卡片自己的内容还一直被放在 `CompositingStrategy.Offscreen` 的层里。
 * 也就是说「不采样」只省掉了采样那一趟与 `blur` / `lens` 两个 render effect，离屏层一分没省。
 * 而这一档（纯色 / 渐变背景，或壁纸已被模糊程度糊平）存在的理由**就是省性能**，
 * 省掉采样却留着离屏层等于白搭——所以这里不调 `drawBackdrop`。
 *
 * 与采样无关的只有两样，都用最便宜的画法补上，参数**直接从库里的 [Highlight] / [Shadow]
 * 对象上读**（不在这里另存一份数字）：
 * - 轮廓高光 → `border`，宽度取 [Highlight.width]、颜色取 [Highlight.style]`.color`、透明度再乘
 *   [Highlight.alpha]——与库里那圈描边同一个乘积；
 * - 环境光投影 → [dropShadow]，半径 / 偏移 / 颜色 / 透明度全部取自 [Shadow]。
 *
 * 于是两条路的观感只有一处来源：库改默认值，这边自动跟上，不会慢慢漂成两套材质。
 * 真正丢掉的只有 [Highlight] 的 0.25dp 描边模糊与 `BlendMode.Plus`（两者都要离屏层才画得出），
 * 0.5dp 的细边上看不出来。
 *
 * 调用方自己决定 [fill]（不采样时是那层「浅色更白 / 深色更深」的薄色）。
 */
fun Modifier.staticGlassSurface(
    highlight: Highlight,
    shadow: Shadow,
    shape: Shape,
    fill: Color,
): Modifier = this
    .dropShadow(shape) {
        // ShadowScope 继承自 Density：radius / offset 要像素 Float
        radius = shadow.radius.toPx()
        offset = Offset(shadow.offset.x.toPx(), shadow.offset.y.toPx())
        color = shadow.color
        alpha = shadow.alpha
    }
    .clip(shape)
    .background(fill, shape)
    // 库里那圈高光 = 描边色 × Highlight.alpha，这里照同一个乘积画，只是不模糊
    .border(
        width = highlight.width,
        color = highlight.style.color.copy(alpha = highlight.style.color.alpha * highlight.alpha),
        shape = shape,
    )
