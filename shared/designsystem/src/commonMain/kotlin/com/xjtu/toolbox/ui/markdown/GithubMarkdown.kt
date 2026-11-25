package com.xjtu.toolbox.ui.markdown

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Size as CoilSize
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.markdownAlertColors
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.xjtu.toolbox.community.CommunityRepo
import com.xjtu.toolbox.community.GithubRefKind
import com.xjtu.toolbox.ui.components.ZoomableBox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * GitHub 风格 Markdown（GFM）渲染：社区帖子、公告、投票、反馈回复、更新说明共用。
 * 用 mikepenz/multiplatform-markdown-renderer，颜色和字号取自 MIUIX 主题，图片经 Coil 加载。
 * 小岱的流式回答和公式走 [MarkdownText]。
 *
 * [textStyle] 比 body1 小时标题不放大、只加粗（公告条这类小字场合）。
 */
@Composable
fun GithubMarkdown(
    markdown: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    color: Color = MiuixTheme.colorScheme.onSurface,
    textStyle: TextStyle = MiuixTheme.textStyles.body1.copy(lineHeight = 24.sp),
) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val dark = colors.surface.luminance() < 0.5f
    val text = textStyle.copy(color = color)
    val markdownColors = remember(color, colors.dividerLine, dark) {
        DefaultMarkdownColors(
            text = color,
            codeBackground = colors.onSurface.copy(alpha = 0.07f),
            inlineCodeBackground = colors.onSurface.copy(alpha = 0.07f),
            dividerColor = colors.dividerLine,
            tableBackground = colors.onSurface.copy(alpha = 0.03f),
            alert = markdownAlertColors(dark),
        )
    }
    val compact = textStyle.fontSize < styles.body1.fontSize
    fun heading(style: TextStyle, weight: FontWeight) =
        if (compact) text.copy(fontWeight = FontWeight.Bold) else style.copy(color = color, fontWeight = weight)
    val typography = DefaultMarkdownTypography(
        h1 = heading(styles.title2, FontWeight.Bold),
        h2 = heading(styles.title3, FontWeight.Bold),
        h3 = heading(styles.title4, FontWeight.SemiBold),
        h4 = heading(styles.headline1, FontWeight.SemiBold),
        h5 = heading(styles.headline2, FontWeight.SemiBold),
        h6 = heading(styles.headline2, FontWeight.Medium),
        text = text,
        code = styles.body2.copy(color = color, fontFamily = FontFamily.Monospace),
        inlineCode = text.copy(fontFamily = FontFamily.Monospace, fontSize = TextUnit.Unspecified),
        quote = styles.body2.copy(color = colors.onSurfaceVariantSummary, lineHeight = 22.sp),
        paragraph = text,
        ordered = text,
        bullet = text,
        list = text,
        textLink = TextLinkStyles(style = SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium)),
        table = text,
        alertTitle = text.copy(fontWeight = FontWeight.Bold),
    )
    // 段内单个换行按硬换行显示，与 GitHub 评论一致（#214）；代码块不经此路径
    val annotator = remember { markdownAnnotator(config = markdownAnnotatorConfig(eolAsNewLine = true)) }
    val refs = LocalIssueRefs.current
    val refNumbers = remember(markdown) { issueNumbersIn(markdown) }
    LaunchedEffect(refs, refNumbers) { refs?.request(refNumbers) }
    val knownRefs = refs?.kinds?.filterKeys { it in refNumbers }.orEmpty()
    // 引用垫一层浅底圆角，和正文分得开；内容仍是标准的「> 」引用，只是这边画得像卡片
    val quoteBackground = colors.onSurface.copy(alpha = 0.05f)
    val components = markdownComponents(
        blockQuote = { model ->
            Box(Modifier.padding(vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(quoteBackground)) {
                MarkdownBlockQuote(model.content, model.node, model.typography.quote)
            }
        },
    )
    @Composable
    fun TextPart(text: String, textModifier: Modifier) {
        val linked = remember(text, knownRefs) { linkIssues(linkMentions(linkGithubRefs(githubHtmlToMarkdown(text))), knownRefs) }
        Markdown(linked, markdownColors, typography, textModifier, components = components, imageTransformer = Coil3ImageTransformerImpl, annotator = annotator)
    }
    // 独占一行的图片拆出来自己画（渲染库把图片当行内元素，尺寸一变整段重排）；`<details>` 拆成可折叠的卡片
    val parts = remember(markdown) { splitPostParts(markdown) }
    val single = parts.singleOrNull()
    if (single is PostPart.Text) {
        TextPart(single.markdown, modifier)
        return
    }
    val bar = typography.quote.color
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part) {
                is PostPart.Text -> TextPart(part.markdown, Modifier.fillMaxWidth())
                is PostPart.Image -> PostImage(part)
                is PostPart.QuotedImages -> QuoteCard(quoteBackground, bar) { part.images.forEach { PostImage(it) } }
                is PostPart.Details ->
                    if (part.quoted) QuoteCard(quoteBackground, bar) { DetailsBlock(part, color, textStyle) }
                    else DetailsBlock(part, color, textStyle)
            }
        }
    }
}

/** 正文里 `#编号`、`GH-编号` 的类型查询；查到的才换成链接。社区页提供，别处没有就只认完整链接。 */
interface IssueRefs {
    val kinds: Map<Int, GithubRefKind>
    fun request(numbers: Set<Int>)
}

val LocalIssueRefs = staticCompositionLocalOf<IssueRefs?> { null }

internal sealed interface PostPart {
    data class Text(val markdown: String) : PostPart
    /** [width]/[height] 来自 GitHub 网页插图的 `<img width height>`，有就能一次占好位。 */
    data class Image(val url: String, val alt: String, val width: Int?, val height: Int?) : PostPart
    /** 引用块（`> `）里独占一行的图片，连着的几张放进同一张引用卡片（#470）。 */
    data class QuotedImages(val images: List<Image>) : PostPart
    /** `<details>` 折叠块：[summary]、[body] 都是 Markdown 原文，[open] 对应 `<details open>`。 */
    data class Details(val summary: String, val body: String, val open: Boolean, val quoted: Boolean) : PostPart
}

private val MD_IMAGE_LINE = Regex("""^!\[([^\]]*)]\(<?(https://[^\s)>]+)>?(?:\s+"[^"]*")?\)$""")
/** 套了链接的图片 `[![说明](图)](链接)`（star-history、徽章常这么写），取里面的图。 */
private val LINKED_MD_IMAGE = Regex("""^\[(!\[[^\]]*]\([^)]+\))]\([^)\s]+\)$""")
private val HTML_IMAGE_LINE = Regex("""^(?:<p[^>]*>\s*)?(?:<a\b[^>]*>\s*)?<img\b([^>]*?)/?>(?:\s*</a>)?(?:\s*</p>)?$""", RegexOption.IGNORE_CASE)
private val QUOTE_PREFIX = Regex("""^(?:>\s?)+""")
private fun htmlAttr(attrs: String, name: String): String? =
    Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)

private val DETAILS_OPEN = Regex("""^<details\b[^>]*>""", RegexOption.IGNORE_CASE)
private val DETAILS_ANY_OPEN = Regex("""<details\b""", RegexOption.IGNORE_CASE)
private val DETAILS_CLOSE = Regex("""</details\s*>""", RegexOption.IGNORE_CASE)
private val DETAILS_OPEN_ATTR = Regex("""\bopen\b""", RegexOption.IGNORE_CASE)
private val SUMMARY_BLOCK = Regex("""^\s*<summary\b[^>]*>([\s\S]*?)</summary\s*>""", RegexOption.IGNORE_CASE)

/** 从 [start] 行的 `<details>` 起，找到与之配对的 `</details>`（可嵌套），返回下一行的下标；没闭合就到引用或全文结束。 */
private fun detailsEnd(lines: List<String>, start: Int, quoted: Boolean): Int {
    var depth = 0
    var fenced = false
    for (j in start until lines.size) {
        val t = lines[j].trim()
        if (quoted && j > start && !t.startsWith(">")) return j
        val body = if (quoted) t.replace(QUOTE_PREFIX, "") else t
        if (body.startsWith("```") || body.startsWith("~~~")) { fenced = !fenced; continue }
        if (fenced) continue
        val outside = body.split('`').filterIndexed { k, _ -> k % 2 == 0 }.joinToString(" ")
        depth += DETAILS_ANY_OPEN.findAll(outside).count() - DETAILS_CLOSE.findAll(outside).count()
        if (depth <= 0) return j + 1
    }
    return lines.size
}

private fun parseDetails(region: String, quoted: Boolean): PostPart.Details {
    val trimmed = region.trim()
    val head = DETAILS_OPEN.find(trimmed)
    var rest = if (head != null) trimmed.substring(head.range.last + 1) else trimmed
    rest.trimEnd().let { if (it.endsWith("</details>", ignoreCase = true)) rest = it.dropLast("</details>".length) }
    val summary = SUMMARY_BLOCK.find(rest)
    val title = summary?.groupValues?.get(1)?.lines()?.joinToString(" ") { it.trim() }?.trim().orEmpty()
    val body = if (summary != null) rest.substring(summary.range.last + 1) else rest
    return PostPart.Details(
        summary = title.ifBlank { "详情" },
        body = body.trimIndent().trim('\n'),
        open = head != null && DETAILS_OPEN_ATTR.containsMatchIn(head.value.substring("<details".length)),
        quoted = quoted,
    )
}

/**
 * 把正文切成块：独占一行的图片（Markdown、`<img>`、套了链接的都算，引用块里的也算）单独成块，
 * 行首的 `<details>` 连同配对的 `</details>` 切成折叠块；代码块里的不动。
 */
internal fun splitPostParts(markdown: String): List<PostPart> {
    val parts = mutableListOf<PostPart>()
    val buffer = StringBuilder()
    fun flush() {
        // 只剩 `>` 的空行（引用里图片之间的空行）不单独画成一张空引用
        if (buffer.lines().any { it.trim().replace(QUOTE_PREFIX, "").isNotBlank() }) parts += PostPart.Text(buffer.toString().trim('\n'))
        buffer.clear()
    }
    val lines = markdown.split('\n')
    var fenced = false
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        if (t.startsWith("```") || t.startsWith("~~~")) fenced = !fenced
        val quoted = !fenced && t.startsWith(">")
        val stripped = if (quoted) t.replace(QUOTE_PREFIX, "").trim() else t
        if (!fenced && DETAILS_OPEN.containsMatchIn(stripped)) {
            flush()
            val end = detailsEnd(lines, i, quoted)
            val region = lines.subList(i, end).joinToString("\n") { if (quoted) it.trimStart().replace(QUOTE_PREFIX, "") else it }
            parts += parseDetails(region, quoted)
            i = end
            continue
        }
        i++
        val body = LINKED_MD_IMAGE.matchEntire(stripped)?.groupValues?.get(1) ?: stripped
        val image = if (fenced) null else MD_IMAGE_LINE.matchEntire(body)?.let { PostPart.Image(it.groupValues[2], it.groupValues[1], null, null) }
            ?: HTML_IMAGE_LINE.matchEntire(body)?.groupValues?.get(1)?.let { attrs ->
                htmlAttr(attrs, "src")?.takeIf { it.startsWith("https://") }?.let { src ->
                    PostPart.Image(src, htmlAttr(attrs, "alt").orEmpty(),
                        htmlAttr(attrs, "width")?.toIntOrNull(), htmlAttr(attrs, "height")?.toIntOrNull())
                }
            }
        when {
            image == null -> buffer.append(line).append('\n')
            quoted -> {
                flush()
                val last = parts.lastOrNull()
                if (last is PostPart.QuotedImages) parts[parts.lastIndex] = PostPart.QuotedImages(last.images + image)
                else parts += PostPart.QuotedImages(listOf(image))
            }
            else -> { flush(); parts += image }
        }
    }
    flush()
    return parts.ifEmpty { listOf(PostPart.Text(markdown)) }
}

/** 折叠块：点摘要那一行展开 / 收起，`<details open>` 默认展开。 */
@Composable
private fun DetailsBlock(details: PostPart.Details, color: Color, textStyle: TextStyle) {
    var expanded by remember(details) { mutableStateOf(details.open) }
    val angle by animateFloatAsState(if (expanded) 90f else 0f)
    val colors = MiuixTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.onSurface.copy(alpha = 0.04f))
            .animateContentSize(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = colors.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = angle },
            )
            Spacer(Modifier.width(6.dp))
            GithubMarkdown(details.summary, Modifier.weight(1f), color, textStyle)
        }
        if (expanded && details.body.isNotBlank()) {
            GithubMarkdown(details.body, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), color, textStyle)
        }
    }
}

/** 引用里的图片、折叠块：和 [MarkdownBlockQuote] 同一张浅底卡片、同一条左侧竖线。 */
@Composable
private fun QuoteCard(background: Color, bar: Color, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.padding(vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(background)) {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val x = 4.dp.toPx()
                    drawLine(bar, Offset(x, 2.dp.toPx()), Offset(x, size.height - 2.dp.toPx()), 2.dp.toPx())
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

private enum class ImageStatus { LOADING, FAILED, READY }

/**
 * 正文里的一张图：知道比例就一次占好位，不知道先占一块，加载完平滑变到真实比例。
 * 加载中转圈；失败了（GitHub 图床在国内常连不上，桌面和 Web 端也没配网络取图）点一下重试，
 * 或者去浏览器里打开；加载好了点开全屏缩放。
 */
@Composable
private fun PostImage(image: PostPart.Image) {
    val known = image.width?.takeIf { it > 0 }?.let { w -> image.height?.takeIf { it > 0 }?.let { h -> w.toFloat() / h } }
    var ratio by remember(image.url) { mutableStateOf(known) }
    var status by remember(image.url) { mutableStateOf(ImageStatus.LOADING) }
    // 换个 attempt 就重建 AsyncImage 重新请求；失败的请求 Coil 不缓存
    var attempt by remember(image.url) { mutableStateOf(0) }
    var viewing by remember { mutableStateOf(false) }
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val uriHandler = LocalUriHandler.current
    // 按屏宽解码、高不限（#384）：不给尺寸时 Coil 按首帧那块 180dp 高的占位去缩图，
    // 盒子按真实比例撑开后显示的还是那张小图，于是发糊
    val decodeWidth = LocalWindowInfo.current.containerSize.width.coerceAtLeast(1)
    Box(
        Modifier
            .then(if (image.width != null && image.width > 0) Modifier.widthIn(max = image.width.dp) else Modifier)
            .fillMaxWidth()
            .animateContentSize()
            .then(ratio?.let { Modifier.aspectRatio(it.coerceIn(0.25f, 4f)) } ?: Modifier.height(180.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .clickable(enabled = status != ImageStatus.LOADING) {
                if (status == ImageStatus.FAILED) { status = ImageStatus.LOADING; attempt++ } else viewing = true
            },
        contentAlignment = Alignment.Center,
    ) {
        key(attempt) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current).data(image.url).crossfade(true)
                    .size(CoilSize(Dimension(decodeWidth), Dimension.Undefined)).build(),
                contentDescription = image.alt.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onError = { status = ImageStatus.FAILED },
                onSuccess = { state ->
                    status = ImageStatus.READY
                    if (ratio == null) {
                        val size = state.painter.intrinsicSize
                        if (size.width > 0f && size.height > 0f) ratio = size.width / size.height
                    }
                },
            )
        }
        when (status) {
            ImageStatus.LOADING -> CircularProgressIndicator(size = 24.dp)
            ImageStatus.FAILED -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.BrokenImage, contentDescription = null, tint = summary)
                Text("图片加载失败，点按重试", style = MiuixTheme.textStyles.footnote1, color = summary)
                Text(
                    "在浏览器中打开",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { runCatching { uriHandler.openUri(image.url) } }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            ImageStatus.READY -> Unit
        }
    }
    if (viewing) ImageViewer(image.url, image.alt, onDismiss = { viewing = false })
}

/** 正文图片全屏查看：双指缩放、双击放大，没放大时单击或点左上角关闭。 */
@Composable
private fun ImageViewer(url: String, alt: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomableBox(Modifier.fillMaxSize(), key = url, onTap = onDismiss) { zoom ->
                AsyncImage(
                    // 全屏要能放大看细节：解原图，不按屏幕尺寸缩
                    model = ImageRequest.Builder(LocalPlatformContext.current).data(url).size(CoilSize.ORIGINAL).build(),
                    contentDescription = alt.ifBlank { null },
                    contentScale = ContentScale.Fit,
                    modifier = zoom,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

private val MENTION = Regex("""(^|[\s(（，。、：；！？])@([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))(?![A-Za-z0-9-])""")

/**
 * 把正文里的 @用户名 换成指向 GitHub 主页的链接，渲染出来是主题色、可点。
 * 围栏代码块和行内代码里的不动；前面紧挨字母数字的（邮箱之类）也不动。
 */
internal fun linkMentions(markdown: String): String {
    if ('@' !in markdown) return markdown
    return mapOutsideCode(markdown) { text ->
        MENTION.replace(text) { m ->
            val name = m.groupValues[2]
            "${m.groupValues[1]}[@$name](https://github.com/$name)"
        }
    }
}

private val HTML_COMMENT = Regex("""<!--.*?-->""")
private val HTML_IMG = Regex("""<img\b([^>]*?)/?>""", RegexOption.IGNORE_CASE)
private val HTML_LINK = Regex("""<a\b([^>]*)>(.*?)</a\s*>""", RegexOption.IGNORE_CASE)
private val HTML_HEADING_START = Regex("""^((?:\s*>)*\s*)<h([1-6])\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HTML_HR_LINE = Regex("""^((?:\s*>)*\s*)<hr\b[^>]*>\s*$""", RegexOption.IGNORE_CASE)
private val HTML_LIST_ITEM = Regex("""<li\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HTML_CELL = Regex("""<(?:td|th)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HTML_SUMMARY = Regex("""<summary\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HTML_INLINE = Regex("""</?(b|strong|i|em|code|kbd|tt|s|del|strike|br)\b[^>]*>""", RegexOption.IGNORE_CASE)
// 只有排版作用、去掉标签留文字的
private val HTML_DROP_TAG = Regex(
    """</?(?:details|summary|div|p|center|span|font|sub|sup|small|big|u|ins|mark|abbr|cite|q|samp|var|dfn|picture|source|video|audio|""" +
        """table|thead|tbody|tfoot|tr|td|th|caption|colgroup|col|ul|ol|li|dl|dt|dd|pre|blockquote|section|article|header|footer|figure|figcaption|""" +
        """a|h[1-6]|hr|img|wbr)\b[^>]*>""",
    RegexOption.IGNORE_CASE,
)
private val HTML_ENTITY = Regex("""&(?:#(\d{1,7})|#[xX]([0-9a-fA-F]{1,6})|([a-zA-Z]+));""")
private val EMOJI_CODE = Regex("""(?<![\w:]):([a-z0-9_+-]{1,40}):(?![\w:])""")
private val LINE_QUOTE_PREFIX = Regex("""^\s*(?:>\s?)*""")

/**
 * 渲染库不认 HTML：行内标签被吞掉，块级 HTML（如 `<details>`）连同同一行的文字整块不显示。
 * 这里把 GitHub 帖子常见的写法换成等价 Markdown：`<b>`/`<i>`/`<code>`/`<kbd>`/`<del>` 换成对应记号，
 * `<a>`/`<img>` 换成链接和图片，`<h1>`~`<h6>`/`<hr>`/`<br>`/`<li>` 换成标题、分隔线、换行、列表点，
 * 其余排版标签去掉留文字，注释删掉，HTML 实体和常用 emoji 短码解码。行首的 `<details>` 由
 * [splitPostParts] 切成折叠卡片，这里只兜底处理夹在别处的摘要。
 */
fun githubHtmlToMarkdown(markdown: String): String {
    if ('<' !in markdown && '&' !in markdown && ':' !in markdown) return markdown
    return mapOutsideCodeIn(markdown) { text, line ->
        var s = HTML_COMMENT.replace(text, "")
        s = HTML_HR_LINE.replace(s) { "${it.groupValues[1]}***" }
        s = HTML_HEADING_START.replace(s) { "${it.groupValues[1]}${"#".repeat(it.groupValues[2].toInt())} " }
        s = HTML_IMG.replace(s) { m ->
            val src = htmlAttr(m.groupValues[1], "src")?.takeIf { it.startsWith("https://") }
            if (src == null) "" else "![${escapeMarkdown(htmlAttr(m.groupValues[1], "alt").orEmpty())}]($src)"
        }
        s = HTML_LINK.replace(s) { m ->
            val href = htmlAttr(m.groupValues[1], "href")?.takeIf { it.isNotBlank() && ' ' !in it }
            val label = m.groupValues[2].trim().ifEmpty { href.orEmpty() }
            if (href == null) label else "[$label]($href)"
        }
        s = HTML_LIST_ITEM.replace(s, "• ")
        s = HTML_CELL.replace(s, " ")
        s = HTML_SUMMARY.replace(s, "▸ ")
        val table = line.replace(LINE_QUOTE_PREFIX, "").trimStart().startsWith("|")
        s = HTML_INLINE.replace(s) { m ->
            when (m.groupValues[1].lowercase()) {
                "b", "strong" -> "**"
                "i", "em" -> "*"
                "code", "kbd", "tt" -> "`"
                "s", "del", "strike" -> "~~"
                // 表格一行不能断开；别处换成真换行，续上这一行的引用前缀
                else -> if (table) " " else "\n" + (LINE_QUOTE_PREFIX.find(line)?.value ?: "")
            }
        }
        s = HTML_DROP_TAG.replace(s, "")
        s = EMOJI_CODE.replace(s) { m -> EMOJI[m.groupValues[1]] ?: m.value }
        HTML_ENTITY.replace(s) { m -> decodeEntity(m) ?: m.value }
    }
}

/** GitHub 常用的 emoji 短码；不认识的照原样留着（`:core:` 这类模块名也不会被误换）。 */
private val EMOJI = mapOf(
    "+1" to "👍", "-1" to "👎", "thumbsup" to "👍", "thumbsdown" to "👎", "smile" to "😄", "smiley" to "😃",
    "grinning" to "😀", "grin" to "😁", "laughing" to "😆", "joy" to "😂", "rofl" to "🤣", "wink" to "😉", "blush" to "😊",
    "slightly_smiling_face" to "🙂", "upside_down_face" to "🙃", "innocent" to "😇", "heart_eyes" to "😍",
    "star_struck" to "🤩", "sunglasses" to "😎", "partying_face" to "🥳", "thinking" to "🤔", "confused" to "😕",
    "sweat_smile" to "😅", "sweat" to "😓", "cry" to "😢", "sob" to "😭", "rage" to "😡", "scream" to "😱",
    "see_no_evil" to "🙈", "facepalm" to "🤦", "shrug" to "🤷", "heart" to "❤️", "broken_heart" to "💔",
    "rocket" to "🚀", "tada" to "🎉", "fire" to "🔥", "star" to "⭐", "star2" to "🌟", "sparkles" to "✨", "eyes" to "👀",
    "pray" to "🙏", "clap" to "👏", "ok_hand" to "👌", "muscle" to "💪", "raised_hands" to "🙌", "wave" to "👋",
    "point_right" to "👉", "point_up" to "☝️", "100" to "💯", "bug" to "🐛", "beetle" to "🐞", "warning" to "⚠️",
    "x" to "❌", "white_check_mark" to "✅", "heavy_check_mark" to "✔️", "question" to "❓", "exclamation" to "❗",
    "bulb" to "💡", "memo" to "📝", "pencil2" to "✏️", "wrench" to "🔧", "hammer" to "🔨", "construction" to "🚧",
    "zap" to "⚡", "boom" to "💥", "coffee" to "☕", "lock" to "🔒", "unlock" to "🔓", "key" to "🔑", "link" to "🔗",
    "calendar" to "📅", "book" to "📖", "books" to "📚", "mag" to "🔍", "gift" to "🎁", "trophy" to "🏆", "medal" to "🏅",
    "bell" to "🔔", "pushpin" to "📌", "chart_with_upwards_trend" to "📈", "recycle" to "♻️", "art" to "🎨",
    "package" to "📦", "hourglass" to "⌛", "alarm_clock" to "⏰", "moon" to "🌙", "sunny" to "☀️", "rainbow" to "🌈",
    "cat" to "🐱", "dog" to "🐶", "new" to "🆕", "sos" to "🆘", "no_entry" to "⛔", "information_source" to "ℹ️",
)

private fun decodeEntity(m: MatchResult): String? {
    val (dec, hex, name) = m.destructured
    val code = dec.toIntOrNull() ?: hex.toIntOrNull(16)
    val text = if (code != null) {
        when (code) {
            in 1..0xFFFF -> code.toChar().toString()
            in 0x10000..0x10FFFF -> (code - 0x10000).let { charArrayOf((0xD800 + (it shr 10)).toChar(), (0xDC00 + (it and 0x3FF)).toChar()).concatToString() }
            else -> return null
        }
    } else when (name.lowercase()) {
        "lt" -> "<"
        "gt" -> ">"
        "amp" -> "&"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> " "
        else -> return null
    }
    // 解出来的是 Markdown / HTML 记号就转义，免得 `&lt;b&gt;` 又变回标签
    return if (text.length == 1 && text[0] in "<>&\\`*_[]") "\\$text" else text
}

/** 只改代码块和行内代码以外的文字。 */
private fun mapOutsideCode(markdown: String, transform: (String) -> String): String =
    mapOutsideCodeIn(markdown) { text, _ -> transform(text) }

/** 同 [mapOutsideCode]，[transform] 另外拿到这段文字所在的整行。 */
private fun mapOutsideCodeIn(markdown: String, transform: (text: String, line: String) -> String): String {
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
        if (fenced) { out.append(line); return@forEachIndexed }
        // 按反引号切开，奇数段是行内代码
        line.split('`').forEachIndexed { j, part ->
            if (j > 0) out.append('`')
            out.append(if (j % 2 == 1) part else transform(part, line))
        }
    }
    return out.toString()
}

private val ISSUE_REF = Regex("""(^|[\s(（，。、：；！？])#(\d{1,7})(?![\w-])""")
private val GH_REF = Regex("""(^|[\s(（，。、：；！？])GH-(\d{1,7})(?![\w-])""")

/** 正文里出现的 `#编号`、`GH-编号`，代码里的不算。 */
internal fun issueNumbersIn(markdown: String): Set<Int> {
    if ('#' !in markdown && "GH-" !in markdown) return emptySet()
    val found = linkedSetOf<Int>()
    mapOutsideCode(markdown) { text ->
        ISSUE_REF.findAll(text).forEach { m -> m.groupValues[2].toIntOrNull()?.let(found::add) }
        GH_REF.findAll(text).forEach { m -> m.groupValues[2].toIntOrNull()?.let(found::add) }
        text
    }
    return found
}

/** 把确认存在的 `#编号`、`GH-编号` 换成链接；没查到的保持普通文字。 */
internal fun linkIssues(markdown: String, kinds: Map<Int, GithubRefKind>): String {
    if (kinds.isEmpty()) return markdown
    val base = "https://github.com/${CommunityRepo.OWNER}/${CommunityRepo.NAME}"
    fun link(m: MatchResult, label: String): String {
        val number = m.groupValues[2].toIntOrNull()
        val path = when (kinds[number]) {
            GithubRefKind.DISCUSSION -> "discussions"
            GithubRefKind.PULL_REQUEST -> "pull"
            GithubRefKind.ISSUE -> "issues"
            null -> null
        }
        return if (path == null) m.value else "${m.groupValues[1]}[$label$number]($base/$path/$number)"
    }
    return mapOutsideCode(markdown) { text ->
        GH_REF.replace(ISSUE_REF.replace(text) { link(it, "#") }) { link(it, "GH-") }
    }
}

// 前面是行首、空白或中文标点（Markdown 链接 `](` 里的不算）；后面只能跟句末标点和空白
private const val REF_START = """(^|[\s（，。、：；！？])"""
private const val REF_END = """(?=[.,:;!?)）。，、；：！？]*(?:\s|$))"""
private val REPO_REF = Regex("""(^|[\s(（，。、：；！？])([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9._-]{1,100})#(\d{1,7})(?![\w-])""")
private val GITHUB_REF_URL = Regex(
    REF_START + """https://github\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+)/""" +
        """(?:(?:issues|pull|discussions)/(\d{1,7})(#(?:discussioncomment|issuecomment)-\d+)?|labels/([A-Za-z0-9._~%-]+))""" + REF_END,
)

/**
 * GitHub 的自动短链（#474）：`Owner/Repo#123` 变成链接；指向 issue / PR / 讨论的裸链接缩成 `#123`
 * （别的仓库是 `Owner/Repo#123`，带评论锚点的加「(comment)」）；标签链接显示成标签名，
 * 和 GitHub 一样只认完整 URL 编码过的。`GH-123` 要先确认存在，在 [linkIssues] 里换。
 */
internal fun linkGithubRefs(markdown: String): String {
    if ('#' !in markdown && "github.com/" !in markdown) return markdown
    return mapOutsideCode(markdown) { text ->
        val shortened = GITHUB_REF_URL.replace(text) { m ->
            val (lead, owner, repo, number, anchor, label) = m.destructured
            val url = m.value.substring(lead.length)
            val title = if (number.isNotEmpty()) {
                val same = owner.equals(CommunityRepo.OWNER, true) && repo.equals(CommunityRepo.NAME, true)
                (if (same) "#$number" else "$owner/$repo#$number") + if (anchor.isNotEmpty()) " (comment)" else ""
            } else {
                percentDecode(label)?.takeIf { it.isNotBlank() }?.let(::escapeMarkdown)
            }
            if (title == null) m.value else "$lead[$title]($url)"
        }
        REPO_REF.replace(shortened) { m ->
            val (lead, owner, repo, number) = m.destructured
            "$lead[$owner/$repo#$number](https://github.com/$owner/$repo/issues/$number)"
        }
    }
}

private fun percentDecode(value: String): String? = runCatching {
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        if (value[i] == '%') {
            bytes += value.substring(i + 1, i + 3).toInt(16).toByte()
            i += 3
        } else {
            bytes += value[i].code.toByte()
            i++
        }
    }
    bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
}.getOrNull()

private fun escapeMarkdown(text: String): String =
    text.replace(Regex("""[\\`*_\[\]<>]""")) { "\\" + it.value }
