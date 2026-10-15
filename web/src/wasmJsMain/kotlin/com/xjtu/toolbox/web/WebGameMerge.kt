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

/**
 * 「合成西交大」在**浏览器**这一端的载体：浏览器本身就是那个 JS 引擎（D12 ⑤）。
 *
 * ## 三端各是什么形状（这一屏为什么只在 `:web` 里）
 *
 * | 端 | 载体 | 判据 |
 * |---|---|---|
 * | Android | `:app` 的 `MergeGameScreen` —— `assets/games/merge/` 跑在 WebView 里 | Android 有 WebView，且它是唯一有 JS 引擎的地方 |
 * | 桌面 | **仍卡载体**：落 `HostUnavailableScreen`（如实说明），本批**一行未改** | 桌面没有浏览器内核，也没有 JVM 上的 JS 引擎（JDK 21 已不带 nashorn，依赖里也没有 jcef / JavaFX WebView） |
 * | Web（本文件） | 同一份资源交给一个 `<iframe>` | 浏览器本来就在跑 JS ⇒ 缺的只是「这几个文件得在静态根下能被 GET 到」，见 `web/build.gradle.kts` 的 `mergeGameResources` |
 *
 * 与「自制探针 / 简化版」的区别：**跑的是同一份字节**（`app/src/main/assets/games/merge/`，
 * 构建期拷进本端产物），一行游戏逻辑都没有在 `:web` 里重写。所以「Android 怎么做就怎么做」
 * 这一条在浏览器上是逐字节成立的 —— 连物理引擎（本地 `matter.min.js`）都是同一份。
 *
 * ## 这一屏与 Android 的两个已知差异（都不在屏幕上画字，理由在后面）
 *
 * 1. **没有音效**：Android 走 JS 桥 `AndroidGameBridge.playSound` ⇒ `GameSound`（SoundPool）。
 *    浏览器里没有那座桥，游戏的 `sfx()` 直接静默。这一条不是本屏的缺口，而是**这一端所有小游戏
 *    的共同事实**（`:core` 的 `gameAudio()` 在 wasm 上是 `SilentWebGameAudio`，2048 / 方块 /
 *    跳一跳 / 棋类一个都不响）—— 所以屏上不多写一句，口径在 `GameAudio.wasmJs.kt` 的文件头。
 * 2. **成绩回传的时机**：Android 是「游戏结束那一刻」由桥回调（`submitScore` + `bestScore`），
 *    浏览器里没有跨文档的桥 ⇒ 反过来做：进这一屏时把 App 侧那份最高分**镜像**进游戏页自己的
 *    localStorage 键，离开这一屏时把游戏页记下的成绩**收回** App 侧那份（见 [MergeGameScoreBridge]）。
 *    两端**可见结果相同**（合集页那一行的最高分跟着涨），差的是「哪一刻落盘」。
 */
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

/**
 * 游戏页的入口 —— **相对本页**的路径。
 *
 * 相对而不是绝对（`/games/merge/index.html`）：这一份产物可能被挂在子路径下
 *（serve 从根吐、nginx 也可能挂在 `/toolbox/`），相对路径两种都成立。
 */
internal const val MERGE_GAME_PAGE: String = "games/merge/index.html"

/**
 * 游戏页**自己**那份最高分的 localStorage 键。
 *
 * 值必须与 `app/src/main/assets/games/merge/index.js` 里的 `STORAGE_KEY`
 *（`const STORAGE_KEY = 'merge-xjtu-highscore';`）**逐字相同** —— 那是这一份资源里的常量，
 * 游戏页写它、这一屏读它（见 [MergeGameScoreBridge]）。改游戏那份 JS 就得同时改这里。
 *
 * 为什么是「读那个键」而不是让游戏页回头问 App：浏览器里没有 Android 那种跨文档的桥
 *（`addJavascriptInterface` 是 WebView 独有的），而两份页面**同源** ⇒ localStorage 本来就是同一份，
 * 于是「App 侧那份记录」与「游戏页那份记录」靠这一个键对齐，双向都不需要新协议。
 */
internal const val MERGE_GAME_HIGHSCORE_KEY: String = "merge-xjtu-highscore"

/**
 * 把 App 侧那份最高分与游戏页那份对齐 —— Android 上这两件事由 JS 桥（`bestScore` /
 * `submitScore`）完成，浏览器里没有桥，靠**同一个 localStorage 键**完成。
 *
 * 两个方向的时机（与 Android 的差别写在 [WebGameMergeScreen] 的 KDoc 第 2 条）：
 *  - 进这一屏：App 侧那份更大就写进游戏页那个键（游戏页 `loadHighscore` 取
 *    `max(localStorage, 宿主)`，于是它一开局就显示同一份记录）；
 *  - 离开这一屏：游戏页那个键更大就收进 App 侧（`GameStore.submitScore` 只增不减，
 *    所以反复进出不会把记录改小；合集页那一行读的就是它）。
 */
internal object MergeGameScoreBridge {

    /** 游戏页那个键里的数字；没有 / 不是数字都当 0（游戏页自己也是这个口径）。 */
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

/**
 * Compose 的一格量出来的矩形，已换算成 **CSS px**（iframe 用的是 CSS px）。
 *
 * @param top 相对视口顶边的距离
 * @param left 相对视口左边的距离
 * @param width 宽（CSS px）
 * @param height 高（CSS px）
 */
internal data class MergeFrameRect(
    val top: Double,
    val left: Double,
    val width: Double,
    val height: Double,
)

/**
 * 把 Compose 的**布局坐标**（`LayoutCoordinates.positionInRoot` 那一套 px）换算成 CSS px。
 *
 * ## 为什么不能直接把布局坐标当 CSS px 用
 *
 * 「1 个布局 px 是不是 1 个 CSS px」取决于 Compose 给这一端定的 density，而这**不该由这一屏去猜**：
 *  - 若这一端是「density = 设备像素比」（Compose for Web 的常见形状）：布局 px 就是 CSS px；
 *  - 若是「density = 1」（画布 backing store 直接按设备像素开）：布局 px 是设备像素
 *    ⇒ 要除回设备像素比。
 *
 * 两种形状都能从**两个已知量**里推出来：这份画布在 Compose 坐标里的高度
 *（[containerHeightPx] = `LocalWindowInfo.containerSize.height`，单位是布局 px）与它在 CSS 里
 * 占的高度（[viewportHeightCssPx] = `window.innerHeight`）。倍数 = 后者 / 前者：
 *  - density = dpr：`innerHeight / innerHeight` = 1；
 *  - density = 1：`innerHeight / (innerHeight × dpr)` = 1 / dpr。
 *
 * 于是这一屏不猜 density 也不读 `LocalDensity`：**两个已知量一比**就得到「布局 px → CSS px」的倍数。
 * 也正因为这个道理，它必须写成纯函数（`WebGameMergeTest` 拿两档形状各钉一条）：
 * 一台机器上只能跑出一种形状，而真站点上的用户两种都可能碰上。
 *
 * 两个输入在组合的**第一帧**都可能是 0（画布还没量出来）：那时倍数取 1（退化成「按 CSS px 算」），
 * 随后 `onGloballyPositioned` 会带着真实尺寸再来一次。
 */
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

/**
 * 把游戏页挂成一个 `<iframe>` 摆在 [rect]（CSS px）上；已经挂着就只挪位置。
 *
 * 为什么是 iframe 而不是「同标签跳过去」：这一屏在 Android 上是**一屏**（顶栏与返回箭头都在），
 * 而跳走之后浏览器的后退键回不到这一屏（外壳的路由是 `history.replaceState` 写的，不产生新条目）
 * ⇒ 用户会被困在游戏页里。iframe 让外壳（顶栏 / 底栏 / 返回）原样留着，与 Android 的形状最近。
 *
 * 只有 `src` 真的变了才重设属性：位置每次布局都会重算（窗口缩放、底栏换侧栏），
 * 而无条件 `setAttribute('src', …)` 会把游戏重新加载一遍 —— 那是「拖一下窗口就重开一局」。
 */
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

/**
 * 视口高度（CSS px）—— [mergeFrameCssRect] 的换算分母。
 *
 * 用 `window.innerHeight` 而不是画布的 CSS 尺寸：`canvas-crisp.js` 把画布的 CSS 尺寸钉在
 * `backing / dpr` 上，与 `innerHeight` 的差在 1 个设备像素以内（那 0.1% 对一块游戏区域无意义），
 * 而 `innerHeight` 是这一层唯一不依赖 CMP 内部形状的量。
 */
@JsFun("() => window.innerHeight")
internal external fun mergeFrameViewportHeightCssPx(): Double
