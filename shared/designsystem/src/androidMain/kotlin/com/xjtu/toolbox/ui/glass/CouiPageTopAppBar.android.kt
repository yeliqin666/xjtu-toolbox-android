package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Velocity
import com.xjtu.toolbox.ui.glass.coui.CouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.CouiCollapsingAppBarState
import com.xjtu.toolbox.ui.glass.coui.rememberCouiCollapsingAppBarState
import com.xjtu.toolbox.ui.glass.coui.CouiTextKind
import com.xjtu.toolbox.ui.glass.coui.CouiTopAppBarColors
import com.xjtu.toolbox.ui.glass.coui.CouiTopAppBarDefaults
import com.xjtu.toolbox.ui.glass.coui.CouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.glass.coui.LargeTopAppBar
import com.xjtu.toolbox.ui.glass.coui.LocalCouiTextStyle
import com.xjtu.toolbox.ui.glass.coui.LocalCouiToolbarBackdrop
import com.xjtu.toolbox.ui.glass.coui.Text
import com.xjtu.toolbox.ui.glass.coui.TopAppBar
import com.xjtu.toolbox.ui.glass.coui.couiBackdrop
import com.xjtu.toolbox.ui.glass.coui.rememberCouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.rememberCouiSystemTextStyle
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面级采样源：直接用 [AndroidCouiPageBackdrop] 包一层 `rememberCouiBackdrop()`，
 * 首页那条自定义顶栏也要 cast 回它取原始采样层，所以不能在旁边另造一个私有包装类。
 *
 * 包装对象必须记住：每次重组都新建的话，`rememberPageGlass` 里按它做键的登记会在页面重组后被撤掉，
 * 正文从此不再录进采样层，顶栏就没有模糊。
 */
@Composable
actual fun rememberCouiPageBackdrop(): CouiPageBackdrop? {
    val backdrop = rememberCouiBackdrop()
    return remember(backdrop) { AndroidCouiPageBackdrop(backdrop) }
}

/**
 * Android 的 [CouiTopBarScrollBehavior]：里面就是 COUI-Kit 原版 [CouiTopAppBarScrollBehavior]。
 *
 * 折叠不是缓动曲线，也不读 MIUIX 的 `collapsedFraction`：正负滚动增量、临界阻尼
 * `SpringAnimation`（收起响应 .2s / 展开 .1s）、松手吸附、`contentPadding` 修正全部由
 * COUI 的 `CouiCollapsingAppBarState` 处理，这里只把它的 `nestedScrollConnection` 转出去。
 *
 * [pinned] 为真（页面顶栏钉死成收起态，二级页统一这么用）时连接不再把滚动喂给折叠状态：
 * 顶栏本来就不折叠，正文也不该先被吃掉 58dp 才开始滚。
 */
private class AndroidCouiTopBarScrollBehavior(
    val behavior: CouiTopAppBarScrollBehavior,
) : CouiTopBarScrollBehavior {
    var pinned: Boolean = false

    /**
     * 页面把正文的实际位置报进来时，顺带把折叠状态摆正：程序化滚动（换会话、自动滚到底、恢复位置）
     * 不经过嵌套滚动，顶栏不会自己收；正文已经滚开却还展开着、或回到顶部却还收着，就用它对齐。
     */
    override fun reportScrolled(scrolled: Boolean) {
        if (!pinned) behavior.state.alignToContent(scrolled)
    }

    private val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (pinned) Offset.Zero else behavior.nestedScrollConnection.onPreScroll(available, source)

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (pinned) Offset.Zero else behavior.nestedScrollConnection.onPostScroll(consumed, available, source)

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            if (pinned) Velocity.Zero else behavior.nestedScrollConnection.onPostFling(consumed, available)
    }

    override val nestedScrollConnection: NestedScrollConnection get() = connection
}

@Composable
actual fun rememberCouiTopAppBarScrollBehavior(): CouiTopBarScrollBehavior =
    rememberCouiTopAppBarScrollBehavior(rememberCouiCollapsingAppBarState())

/** 页面自己持有折叠状态时（带搜索的 `CollapsingTopAppBar`）用这个：位置上报与其它页同一套。 */
@Composable
fun rememberCouiTopAppBarScrollBehavior(state: CouiCollapsingAppBarState): CouiTopBarScrollBehavior =
    remember(state) { AndroidCouiTopBarScrollBehavior(CouiTopAppBarScrollBehavior(state)) }

/**
 * 双轨滚动：顶栏可见高度由弹簧追逻辑滚动，快速滑动时慢半拍。对外报的高度按逻辑位置算
 *（可见高度 + `contentTranslationY`），页面按它排正文，正文跟手、顶栏自己追。挂在顶栏最外层。
 */
fun Modifier.couiContentTrack(state: CouiCollapsingAppBarState?): Modifier =
    if (state == null) this else layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height + state.contentTranslationY.roundToInt()).coerceAtLeast(0)
        layout(placeable.width, height) { placeable.place(0, 0) }
    }

/**
 * COUI 大标题折叠顶栏的 Android 实现。
 *
 * 就是 COUI-Kit 的组件本身，没有一行自写版式或弹簧：
 * - `pinned = true` → 原版 `TopAppBar`（52dp 小标题）；
 * - 否则 → 原版 `LargeTopAppBar`（110dp → 52dp、原版 `AppBarLayout` 测量 + 原版
 *   `CouiTopAppBarSurface` 渐变模糊 + `CouiCollapsingAppBarState` 弹簧）；
 * - 折叠进度由 [CouiTopBarScrollBehavior] 驱动，页面把它接到滚动容器祖先的 `nestedScroll`。
 *
 * 集成侧的三个包装：
 * - `bottomContent`：COUI 顶栏没有底部槽位，放在原版组件下面的 `Column` 里；
 * - `subtitle`：COUI 没有副标题槽位，放进 `title` 槽、在原版标题下面多渲一行小字；
 * - `color`：玻璃页传进来的是 Transparent（表面自己铺模糊），用页面底色当原版渐隐色；
 *   经典风格传进来的是不透明底色，正好就是它的背景色。
 *
 * [pageBackdrop] 为 null（经典风格 / 非玻璃）时保留实色底兜底：COUI 组件照原版排版，
 * 但模糊层空采样（`CouiBackdrop` 未录制时本来就不画），背后垫一层实色。
 */
@Composable
actual fun CouiPageTopAppBar(
    title: String,
    largeTitle: String,
    modifier: Modifier,
    color: Color,
    scrollBehavior: CouiTopBarScrollBehavior?,
    pageBackdrop: CouiPageBackdrop?,
    subtitle: String?,
    titleColor: Color,
    pinned: Boolean,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    bottomContent: @Composable () -> Unit,
    titleSize: TextUnit?,
) {
    val dark = LocalIsDarkTheme.current
    val backdrop = (pageBackdrop as? AndroidCouiPageBackdrop)?.backdrop
    val behavior = (scrollBehavior as? AndroidCouiTopBarScrollBehavior)?.behavior
    // 钉死小标题：页面照旧把 behavior 的连接挂在正文滚动上，但顶栏不折叠时不该再吃那 58dp。
    // 页内顶栏要么都钉死、要么都折叠，同一份 behavior 不会被两种顶栏同时用。
    (scrollBehavior as? AndroidCouiTopBarScrollBehavior)?.pinned = pinned
    // 双轨滚动挂在最外层，经典与玻璃两个分支都吃到
    val trackedModifier = Modifier.couiContentTrack(behavior?.state.takeUnless { pinned }).then(modifier)
    val resolvedTitle = if (pinned) title.ifBlank { largeTitle } else largeTitle.ifBlank { title }
    val resolvedSubtitle = subtitle?.takeIf { it.isNotBlank() }
    val resolvedTitleColor = titleColor.takeOrElse { MiuixTheme.colorScheme.onSurface }
    val subtitleColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    // COUI 顶栏的渐隐色：玻璃页用页面底色（玻璃页传进来的 color 是 Transparent），
    // 经典风格用它自己的不透明底色，两者都落在原版 `backgroundColor` 参数上。
    val scrimColor = if (color == Color.Unspecified || color.alpha == 0f) {
        MiuixTheme.colorScheme.surface
    } else {
        color
    }
    val titleSlot: @Composable () -> Unit = {
        CouiPageTitle(
            title = resolvedTitle,
            subtitle = resolvedSubtitle,
            subtitleColor = subtitleColor,
            fontSize = titleSize,
            collapsedFraction = { if (pinned) 0f else behavior?.state?.collapsedFraction ?: 0f },
        )
    }
    // 副标题在大标题下面占一行：展开高度跟着加高，不然会压到正文上
    val expandedHeight = CouiTopAppBarDefaults.expandedHeight + if (resolvedSubtitle != null) SubtitleRoom else 0.dp
    if (backdrop == null) {
        // 经典风格：没有页面采样源。COUI 组件照原版排版与折叠，只是背景换成实色。
        // 顶栏按钮仍然需要一层采样源（COUI 原版 `TopAppBarButton` 会 checkNotNull），
        // 所以把这块实色底也录进采样层：材质照原版画，只是采到的是顶栏底色而不是背后的画面
        // （和首页经典风格的 `CouiHomeTopBarSurface` 同一个做法）。
        val solidBackdrop = rememberCouiBackdrop()
        val solidColor = color.takeOrElse { MiuixTheme.colorScheme.surface }
        // 透明底色（页顶栏本身不画底）也要有东西可采，用表面色顶上
        val sampleColor = if (solidColor.alpha == 0f) MiuixTheme.colorScheme.surface else solidColor
        val solidColors = CouiTopAppBarDefaults.colors(
            backgroundColor = Color.Transparent,
            titleColor = resolvedTitleColor,
            dark = dark,
        )
        Box(trackedModifier.fillMaxWidth()) {
            // 只录这一层实色底，不录顶栏自己的内容（避免按钮采样到旁边的图标）
            Box(Modifier.matchParentSize().couiBackdrop(solidBackdrop).background(sampleColor))
            CouiPageBar(
                pinned = pinned,
                backdrop = solidBackdrop,
                colors = solidColors,
                behavior = behavior,
                dark = dark,
                expandedHeight = expandedHeight,
                title = titleSlot,
                navigationIcon = navigationIcon,
                actions = actions,
                bottomContent = bottomContent,
            )
        }
        return
    }
    CouiPageBar(
        pinned = pinned,
        backdrop = backdrop,
        colors = CouiTopAppBarDefaults.colors(
            backgroundColor = scrimColor,
            titleColor = resolvedTitleColor,
            dark = dark,
        ),
        behavior = behavior,
        dark = dark,
        modifier = trackedModifier,
        expandedHeight = expandedHeight,
        title = titleSlot,
        navigationIcon = navigationIcon,
        actions = actions,
        bottomContent = bottomContent,
    )
}

/**
 * 原版 COUI 顶栏 + 集成侧槽位。两个分支都直接调用 COUI-Kit 的组件，不在这里做任何测量或动画。
 */
@Composable
private fun CouiPageBar(
    pinned: Boolean,
    backdrop: CouiBackdrop,
    colors: CouiTopAppBarColors,
    behavior: CouiTopAppBarScrollBehavior?,
    dark: Boolean,
    modifier: Modifier = Modifier,
    expandedHeight: Dp = CouiTopAppBarDefaults.expandedHeight,
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    bottomContent: @Composable () -> Unit,
) {
    Column {
        if (pinned) {
            TopAppBar(
                modifier = modifier,
                title = title,
                navigationIcon = navigationIcon,
                actions = actions,
                dark = dark,
                colors = colors,
                backdrop = backdrop,
            )
        } else {
            LargeTopAppBar(
                modifier = modifier,
                title = title,
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = behavior,
                dark = dark,
                colors = colors,
                expandedHeight = expandedHeight,
                backdrop = backdrop,
            )
        }
        // bottomContent 在 COUI 表面之外，但常放搜索胶囊 / 顶栏按钮这类顶栏部件：
        // 把这一页的采样源交给它，组件不必自己再录一层（COUI 原版 TopAppBarButton 会 checkNotNull）。
        CompositionLocalProvider(LocalCouiToolbarBackdrop provides backdrop) { bottomContent() }
    }
}

/** 副标题一行要让出的高度。 */
private val SubtitleRoom = 24.dp

/**
 * 标题槽：原版 COUI `AppBarLayout` 通过 `ProvideCouiContent` 提供展开/收起用的标题样式，
 * 按 COUI 的约定「title 槽位须继承组件提供的样式」直接消费 [LocalCouiTextStyle]，
 * 字号、字重、行高、颜色全部来自原厂（含展开 40sp → 收起缩放的基准）。
 * 副标题是集成侧补的一行小字，用原版 `CouiTextKind.ItemSummary` 的语义排版。
 */
@Composable
private fun CouiPageTitle(
    title: String,
    subtitle: String?,
    subtitleColor: Color,
    fontSize: TextUnit? = null,
    /** 顶栏折叠进度：副标题随它淡出并收起高度，收起后只剩居中的小标题（布局阶段读）。 */
    collapsedFraction: () -> Float = { 0f },
) {
    // 覆盖字号时按 COUI 的 HomeTitle 排版重新算一遍（字号变了行高要跟着变），
    // 这样和「大标题收起后」的标题字形、字重完全一致。
    //
    // 重新算出来的样式**不带颜色**（COUI 的排版预设只管字形），而标题色是顶栏通过
    // `LocalCouiTextStyle` 交下来的原厂 `titleColor`。漏掉这一步，BasicText 会退回默认黑字：
    // 浅色下碰巧是对的，暗色下「日程」这种钉死小标题就一直是黑的。把颜色补回去。
    val providedStyle = LocalCouiTextStyle.current
    val titleStyle = if (fontSize == null) {
        providedStyle
    } else {
        rememberCouiSystemTextStyle(CouiTextKind.HomeTitle, size = fontSize.value).let { style ->
            if (providedStyle.color.isSpecified) style.copy(color = providedStyle.color) else style
        }
    }
    // 长标题（课程名等）先缩字号塞进一行，缩到六成还放不下才省略。
    // 顶栏收起时按缩放比放宽了标题的宽度上限，所以收起后仍是原定的小标题字号。
    val maxSize = titleStyle.fontSize
    val autoSize = if (maxSize.isSp) {
        TextAutoSize.StepBased(minFontSize = (maxSize.value * 0.6f).sp, maxFontSize = maxSize, stepSize = 1.sp)
    } else null
    val titleText: @Composable () -> Unit = {
        BasicText(
            text = title,
            style = titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = autoSize,
        )
    }
    if (subtitle == null) {
        titleText()
        return
    }
    Column {
        titleText()
        // 副标题不跟着标题一起缩小（那样收起后是一行看不清的小字）：折叠前半程淡掉，高度同步收起
        Box(
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val f = collapsedFraction().coerceIn(0f, 1f)
                layout(placeable.width, (placeable.height * (1f - f)).roundToInt()) {
                    placeable.placeWithLayer(0, 0) { alpha = (1f - 2f * f).coerceAtLeast(0f) }
                }
            },
        ) {
            Text(
                text = subtitle,
                kind = CouiTextKind.ItemSummary,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
            )
        }
    }
}
