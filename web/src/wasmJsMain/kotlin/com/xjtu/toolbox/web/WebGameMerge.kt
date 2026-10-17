@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import com.xjtu.toolbox.game.GameIds
import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.ui.components.BackButton
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlinx.browser.localStorage
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 「合成西交大」在浏览器这一端的载体：浏览器本身就是那个 JS 引擎。 */
@Composable
internal fun WebGameMergeScreen(onBack: () -> Unit) {
    val dark = LocalIsDarkTheme.current
    // 换算用的分母：这份画布在 Compose 坐标里有多高（见 [mergeFrameCssRect] 的 KDoc）。
    val containerHeightPx = LocalWindowInfo.current.containerSize.height
    var rect by remember { mutableStateOf<MergeFrameRect?>(null) }

    DisposableEffect(Unit) {
        MergeGameScoreBridge.mirrorAppBestToPage()
        onDispose {
            // 先摘掉 iframe（页面连同它的物理循环一起没了），再把成绩收回 App 侧。
            mergeFrameUnmount()
            MergeGameScoreBridge.collectPageBestIntoApp()
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "合成西交大",
                // 与 Android 那一屏同一句话、同一个位置（那一屏也是 SmallTopAppBar + BackButton）。
                color = MiuixTheme.colorScheme.surface,
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        // 这一格不去画任何东西：它的唯一作用是**量出**游戏该占的那块矩形，再让 JS 把 iframe
        // 摆在同一个位置上（Compose 画布与 DOM 是两层，屏里嵌不进 DOM 元素）。
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { coordinates ->
                    val at = coordinates.positionInRoot()
                    rect = mergeFrameCssRect(
                        topPx = at.x,
                        leftPx = at.y,
                        widthPx = coordinates.size.width.toFloat(),
                        heightPx = coordinates.size.height.toFloat(),
                        containerHeightPx = containerHeightPx,
                        viewportHeightCssPx = mergeFrameViewportHeightCssPx(),
                    )
                }
        )
    }

    // 位置变了（窗口缩放 / 底栏换侧栏 / 顶栏高度变化）就重新摆一次 —— JS 那边只在 `src`
    // 真的变了才重设属性，所以同一个 `src` 重复调用**不会重开一局**。
    LaunchedEffect(rect, dark) {
        rect?.let { mergeFrameMount(it, MERGE_GAME_PAGE, dark) }
    }
}

/** 游戏页的入口 —— 相对本页的路径。 */
internal const val MERGE_GAME_PAGE: String = "games/merge/index.html"

/** 游戏页自己那份最高分的 localStorage 键。 */
internal const val MERGE_GAME_HIGHSCORE_KEY: String = "merge-xjtu-highscore"

/** 把 App 侧那份最高分与游戏页那份对齐。 */
internal object MergeGameScoreBridge {

    /** 游戏页那个键里的数字；没有 / 不是数字都当 0。 */
    fun pageBest(): Int = localStorage.getItem(MERGE_GAME_HIGHSCORE_KEY)?.trim()?.toIntOrNull() ?: 0

    fun mirrorAppBestToPage() {
        val app = GameStore.bestScore(GameIds.MERGE)
        if (app > pageBest()) localStorage.setItem(MERGE_GAME_HIGHSCORE_KEY, app.toString())
    }

    fun collectPageBestIntoApp() {
        val page = pageBest()
        if (page > 0) GameStore.submitScore(GameIds.MERGE, page)
    }
}

/** Compose 的一格量出来的矩形，已换算成 CSS px（iframe 用的是 CSS px）。 */
internal data class MergeFrameRect(
    val top: Double,
    val left: Double,
    val width: Double,
    val height: Double,
)

/** 把 Compose 的布局坐标（`LayoutCoordinates.positionInRoot` 那一套 px）换算成 CSS px。 */
internal fun mergeFrameCssRect(
    topPx: Float,
    leftPx: Float,
    widthPx: Float,
    heightPx: Float,
    containerHeightPx: Int,
    viewportHeightCssPx: Double,
): MergeFrameRect {
    val scale = if (containerHeightPx > 0 && viewportHeightCssPx > 0.0) {
        viewportHeightCssPx / containerHeightPx
    } else {
        1.0
    }
    return MergeFrameRect(
        top = topPx * scale,
        left = leftPx * scale,
        width = widthPx * scale,
        height = heightPx * scale,
    )
}

/** 把游戏页挂成一个 `<iframe>` 摆在 [rect]（CSS px）上；已经挂着就只挪位置。 */
internal fun mergeFrameMount(rect: MergeFrameRect, src: String, dark: Boolean) {
    mergeFrameMountAt(rect.top, rect.left, rect.width, rect.height, src, dark)
}

/** 上面那一份的 JS 面：参数逐个传（跨边界只能传 JS 认识的值，数据类不行）。 */
@JsFun(
    """
(top, left, width, height, src, dark) => {
  let el = document.getElementById('xjtu-merge-frame');
  if (!el) {
    el = document.createElement('iframe');
    el.id = 'xjtu-merge-frame';
    el.title = '合成西交大';
    el.style.position = 'fixed';
    el.style.border = '0';
    el.style.margin = '0';
    el.style.padding = '0';
    el.style.zIndex = '2147483000';
    document.body.appendChild(el);
  }
  const want = dark ? src + '?dark=1' : src;
  if (el.getAttribute('src') !== want) el.setAttribute('src', want);
  el.style.top = top + 'px';
  el.style.left = left + 'px';
  el.style.width = width + 'px';
  el.style.height = height + 'px';
}
"""
)
private external fun mergeFrameMountAt(
    top: Double,
    left: Double,
    width: Double,
    height: Double,
    src: String,
    dark: Boolean,
)

/** 摘掉那个 `<iframe>`（离开这一屏时调一次：整局连同物理循环一起消失）。 */
@JsFun(
    """
() => {
  const el = document.getElementById('xjtu-merge-frame');
  if (el) el.remove();
}
"""
)
internal external fun mergeFrameUnmount()

/** 视口高度（CSS px）—— [mergeFrameCssRect] 的换算分母。 */
@JsFun("() => window.innerHeight")
internal external fun mergeFrameViewportHeightCssPx(): Double
