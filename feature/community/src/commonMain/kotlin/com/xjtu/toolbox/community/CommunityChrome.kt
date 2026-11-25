package com.xjtu.toolbox.community

import com.xjtu.toolbox.ui.glass.CouiToolbarActionButton
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.LocalTabRoot
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import com.xjtu.toolbox.ui.markdown.IssueRefs
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.xjtu.toolbox.util.CampusTimeZone
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior

/**
 * 社区各页的外壳：和其他二级页同一套可折叠大标题（玻璃风格下内容铺到顶栏下面做采样源）。
 * [content] 拿到的 top 是要放进滚动内容里的顶部留白。
 *
 * [root] = 社区首页本身。社区被放到底栏上时只有它没有上一级；帖子、发帖、编辑这些
 * 往里走的页面永远要有返回键，所以对它们把 [LocalTabRoot] 摘掉。
 */
@Composable
fun CommunityPage(
    title: String,
    onBack: () -> Unit,
    root: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (top: Dp) -> Unit,
) {
    val glass = rememberPageGlass()
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    Scaffold(
        topBar = {
            CompositionLocalProvider(LocalTabRoot provides (root && LocalTabRoot.current)) {
                GlassTopAppBar(
                    title = title,
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    actions = actions,
                )
            }
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
    ) { padding ->
        val top = padding.glassTop(glass)
        val handled = padding.withoutTop(glass)
        Box(
            Modifier
                .fillMaxSize()
                .padding(handled)
                // 垫过的声明消费：页里的 imePadding 才不会把底栏 / 导航条再算一遍
                .consumeWindowInsets(handled)
                .glassSource(glass)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            CompositionLocalProvider(
                LocalCommunityBarScroll provides scrollBehavior,
                com.xjtu.toolbox.ui.glass.LocalCouiScrollReporter provides scrollBehavior,
            ) {
                content(top)
            }
        }
    }
}

/** 这一页顶栏的折叠状态：页内下拉刷新要它，才能先展开大标题、展开完才算下拉。 */
internal val LocalCommunityBarScroll =
    androidx.compose.runtime.staticCompositionLocalOf<com.xjtu.toolbox.ui.glass.CouiTopBarScrollBehavior?> { null }

/**
 * 社区里页与页之间的转场：往里走（列表 → 帖子 → 编辑）新页从右边推进来、旧页往左让一点并变淡；
 * 往回走反过来，退出的那页盖在上面滑走。
 */
fun <S> AnimatedContentTransitionScope<S>.communitySlide(forward: Boolean): ContentTransform {
    val slide = tween<IntOffset>(380, easing = FastOutSlowInEasing)
    val fade = tween<Float>(260)
    return if (forward) {
        ContentTransform(
            targetContentEnter = slideInHorizontally(slide) { it } + fadeIn(fade),
            initialContentExit = slideOutHorizontally(slide) { -it / 4 } + fadeOut(fade),
            targetContentZIndex = 1f,
        )
    } else {
        ContentTransform(
            targetContentEnter = slideInHorizontally(slide) { -it / 4 } + fadeIn(fade),
            initialContentExit = slideOutHorizontally(slide) { it } + fadeOut(fade),
            targetContentZIndex = -1f,
        )
    }
}

/** 顶栏上的图标按钮：和首页右上角同一套 COUI 圆钮材质。 */
@Composable
fun CommunityBarAction(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    CouiToolbarActionButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description, tint = MiuixTheme.colorScheme.onSurface)
    }
}

private const val MAX_REFS_PER_QUERY = 40

/** 一页里各楼层的编号合并成一次查询，查过的不再查。 */
internal class IssueRefResolver(private val repo: GithubDiscussionsRepository, private val scope: kotlinx.coroutines.CoroutineScope) : IssueRefs {
    override val kinds = androidx.compose.runtime.mutableStateMapOf<Int, GithubRefKind>()
    private val asked = mutableSetOf<Int>()
    private val pending = mutableSetOf<Int>()
    private var job: kotlinx.coroutines.Job? = null

    override fun request(numbers: Set<Int>) {
        pending += numbers - asked
        if (pending.isEmpty() || job?.isActive == true) return
        job = scope.launch {
            kotlinx.coroutines.delay(120)
            while (pending.isNotEmpty()) {
                val batch = pending.take(MAX_REFS_PER_QUERY).toSet()
                pending -= batch
                asked += batch
                repo.referenceKinds(CommunityRepo.OWNER, CommunityRepo.NAME, batch).onSuccess { kinds += it }
            }
        }
    }
}

/** 光标前正在输入的 @前缀（不含 @）；没在输 @ 时返回 null。 */
fun mentionQuery(text: String, cursor: Int): String? {
    val before = text.substring(0, cursor.coerceIn(0, text.length))
    val at = before.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !before[at - 1].isWhitespace() && before[at - 1] !in "(（，。、：；！？") return null
    val query = before.substring(at + 1)
    return query.takeIf { q -> q.length <= 39 && q.all { it.isLetterOrDigit() || it == '-' } }
}

/** @联想条：列出本帖参与者，点一下把正在输的 @前缀换成完整用户名。 */
@Composable
fun MentionSuggestions(
    candidates: List<CommunityUser>,
    query: String,
    onPick: (CommunityUser) -> Unit,
) {
    val matched = remember(candidates, query) {
        candidates.filter { it.login.startsWith(query, ignoreCase = true) }.take(8)
    }
    androidx.compose.animation.AnimatedVisibility(visible = matched.isNotEmpty()) {
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(matched.size) { i ->
                val user = matched[i]
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onPick(user) }
                        .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CommunityAvatar(user.avatar, user.login, 22.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("@${user.login}", style = MiuixTheme.textStyles.footnote1)
                }
            }
        }
    }
}

/** 可以被 @ 的人。 */
data class CommunityUser(val login: String, val avatar: String?)

/** GitHub 头像；没有地址或还没加载出来时显示首字母。 */
@Composable
fun CommunityAvatar(url: String?, login: String?, size: Dp) {
    val colors = MiuixTheme.colorScheme
    Box(
        Modifier.size(size).clip(CircleShape).background(colors.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (login ?: "?").take(1).uppercase(),
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurfaceVariantSummary,
        )
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = login,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 小标签：分类、楼主、已解答。 */
@Composable
fun CommunityTag(text: String, color: Color = MiuixTheme.colorScheme.primary) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote2,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 仓库主人和协作者发的内容带这个标签。 */
@Composable
fun AdminTag() = CommunityTag("管理员", Color(0xFFE5484D))

/** 帖子、楼层底部的小动作：点赞、回复、采纳…… */
@Composable
fun CommunityAction(
    icon: ImageVector,
    text: String,
    active: Boolean = false,
    /** 点亮时换用的图标，比如实心的赞。 */
    activeIcon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val tint by androidx.compose.animation.animateColorAsState(
        when {
            active -> colors.primary
            enabled -> colors.onSurfaceVariantSummary
            else -> colors.disabledOnSurface
        },
        label = "actionTint",
    )
    // 点亮时图标轻轻弹一下
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(active) {
        if (active) {
            scale.animateTo(1.25f, androidx.compose.animation.core.tween(120))
            scale.animateTo(1f, androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (active && activeIcon != null) activeIcon else icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
        if (text.isNotEmpty()) Text(text, style = MiuixTheme.textStyles.footnote1, color = tint)
    }
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 HH:mm / MM-dd / yyyy-MM-dd。 */
fun communityTime(iso: String): String =
    communityTimeOf(iso, Clock.System.now(), CampusTimeZone)

/** [communityTime] 的可注入版本：`now` / `zone` 由调用方给，测试才能固定「今天」与「哪个时区的今天」。 */
internal fun communityTimeOf(iso: String, now: Instant, zone: TimeZone): String = runCatching {
    val instant = Instant.parse(iso)
    val time = instant.toLocalDateTime(zone)
    val today = now.toLocalDateTime(zone).date
    val minutes = (now - instant).inWholeMinutes
    when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        time.date.toEpochDays() == today.toEpochDays() -> "${minutes / 60} 小时前"
        time.date.toEpochDays() == today.toEpochDays() - 1 -> "昨天 ${pad2(time.hour)}:${pad2(time.minute)}"
        time.year == today.year -> "${pad2(time.month.ordinal + 1)}-${pad2(time.day)}"
        else -> "${time.year}-${pad2(time.month.ordinal + 1)}-${pad2(time.day)}"
    }
}.getOrDefault("")

private fun pad2(value: Int): String = value.toString().padStart(2, '0')
