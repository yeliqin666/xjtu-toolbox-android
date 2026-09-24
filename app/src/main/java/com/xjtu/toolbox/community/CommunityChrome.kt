package com.xjtu.toolbox.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.crossfade
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.markdownAlertColors
import com.xjtu.toolbox.ui.glass.glassBarColor
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.glassTopBar
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 社区各页的外壳：App 自己的玻璃小标题栏，内容铺到顶栏下面做采样源。
 * [content] 拿到的 top 是要放进滚动内容里的顶部留白。
 */
@Composable
fun CommunityPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (top: Dp) -> Unit,
) {
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = actions,
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
    ) { padding ->
        val top = padding.glassTop(glass)
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding.withoutTop(glass))
                .glassSource(glass)
        ) {
            content(top)
        }
    }
}

/**
 * 社区里页与页之间的转场：往里走（列表 → 帖子 → 编辑）新页从右边推进来、旧页往左让一点并变淡；
 * 往回走反过来，退出的那页盖在上面滑走。
 */
internal fun <S> AnimatedContentTransitionScope<S>.communitySlide(forward: Boolean): ContentTransform {
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

/** 顶栏上的图标按钮。 */
@Composable
fun CommunityBarAction(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description, tint = MiuixTheme.colorScheme.onSurface)
    }
}

/**
 * GitHub 风格 Markdown（GFM）渲染，用 mikepenz/multiplatform-markdown-renderer 的核心模块，
 * 颜色和字号取自 MIUIX 主题；图片经 Coil 加载。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val dark = colors.surface.luminance() < 0.5f
    val text = styles.body1.copy(color = colors.onSurface, lineHeight = 24.sp)
    val markdownColors = remember(colors.onSurface, colors.dividerLine, dark) {
        DefaultMarkdownColors(
            text = colors.onSurface,
            codeBackground = colors.onSurface.copy(alpha = 0.07f),
            inlineCodeBackground = colors.onSurface.copy(alpha = 0.07f),
            dividerColor = colors.dividerLine,
            tableBackground = colors.onSurface.copy(alpha = 0.03f),
            alert = markdownAlertColors(dark),
        )
    }
    val typography = DefaultMarkdownTypography(
        h1 = styles.title2.copy(color = colors.onSurface, fontWeight = FontWeight.Bold),
        h2 = styles.title3.copy(color = colors.onSurface, fontWeight = FontWeight.Bold),
        h3 = styles.title4.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold),
        h4 = styles.headline1.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold),
        h5 = styles.headline2.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold),
        h6 = styles.headline2.copy(color = colors.onSurface, fontWeight = FontWeight.Medium),
        text = text,
        code = styles.body2.copy(color = colors.onSurface, fontFamily = FontFamily.Monospace),
        inlineCode = text.copy(fontFamily = FontFamily.Monospace, fontSize = TextUnit.Unspecified),
        quote = text.copy(color = colors.onSurfaceVariantSummary).plus(SpanStyle(fontStyle = FontStyle.Italic)),
        paragraph = text,
        ordered = text,
        bullet = text,
        list = text,
        textLink = TextLinkStyles(style = SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium)),
        table = text,
        alertTitle = text.copy(fontWeight = FontWeight.Bold),
    )
    val linked = remember(markdown) { linkMentions(markdown) }
    Markdown(
        content = linked,
        colors = markdownColors,
        typography = typography,
        imageTransformer = Coil3ImageTransformerImpl,
        modifier = modifier,
    )
}

private val MENTION = Regex("""(^|[\s(（，。、：；！？])@([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))(?![A-Za-z0-9-])""")

/**
 * 把正文里的 @用户名 换成指向 GitHub 主页的链接，渲染出来是主题色、可点。
 * 围栏代码块和行内代码里的不动；前面紧挨字母数字的（邮箱之类）也不动。
 */
internal fun linkMentions(markdown: String): String {
    if ('@' !in markdown) return markdown
    val out = StringBuilder(markdown.length + 32)
    var fenced = false
    markdown.split('\n').forEachIndexed { i, line ->
        if (i > 0) out.append('\n')
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            fenced = !fenced
            out.append(line)
            return@forEachIndexed
        }
        if (fenced || '@' !in line) { out.append(line); return@forEachIndexed }
        // 按反引号切开，奇数段是行内代码
        line.split('`').forEachIndexed { j, part ->
            if (j > 0) out.append('`')
            if (j % 2 == 1) out.append(part)
            else out.append(MENTION.replace(part) { m ->
                val name = m.groupValues[2]
                "${m.groupValues[1]}[@$name](https://github.com/$name)"
            })
        }
    }
    return out.toString()
}

/** 光标前正在输入的 @前缀（不含 @）；没在输 @ 时返回 null。 */
internal fun mentionQuery(text: String, cursor: Int): String? {
    val before = text.substring(0, cursor.coerceIn(0, text.length))
    val at = before.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !before[at - 1].isWhitespace() && before[at - 1] !in "(（，。、：；！？") return null
    val query = before.substring(at + 1)
    return query.takeIf { q -> q.length <= 39 && q.all { it.isLetterOrDigit() || it == '-' } }
}

/** @联想条：列出本帖参与者，点一下把正在输的 @前缀换成完整用户名。 */
@Composable
internal fun MentionSuggestions(
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
                model = coil3.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
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
            scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f))
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

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 HH:mm / MM-dd / yyyy-MM-dd。解析不了返回空串。 */
fun communityTime(iso: String): String = runCatching {
    val zone = ZoneId.systemDefault()
    val time = OffsetDateTime.parse(iso).atZoneSameInstant(zone)
    val minutes = Duration.between(time, ZonedDateTime.now(zone)).toMinutes()
    val today = LocalDate.now(zone)
    when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        time.toLocalDate() == today -> "${minutes / 60} 小时前"
        time.toLocalDate() == today.minusDays(1) -> "昨天 " + time.format(DateTimeFormatter.ofPattern("HH:mm"))
        time.year == today.year -> time.format(DateTimeFormatter.ofPattern("MM-dd"))
        else -> time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }
}.getOrDefault("")
