package com.xjtu.toolbox.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import com.xjtu.toolbox.GradientAppIcon
import com.xjtu.toolbox.nav.expandOriginSource
import com.xjtu.toolbox.nav.rememberExpandOriginSource
import com.xjtu.toolbox.ui.components.AppCardColor
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.utils.SinkFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.School
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.ui.adaptive.readableWidth
import com.xjtu.toolbox.ui.glass.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 小游戏合集页。
 *
 * 每张卡带一行「战绩」：分数类显示最高分，对弈类显示各难度胜负。
 * 这一行是合集页存在的理由——否则它就只是一个多余的中转层，
 * 各游戏本来就能从全局搜索直接进（见 Routes 里各自的路由）。
 */
@Composable
fun GamesScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // 战绩每次进页面读一次即可：玩完一局是 pop 回来，会重新组合。
    val arcade = remember {
        listOf(
            GameEntry(
                route = Routes.GAME_MERGE,
                title = "合成西交大",
                summary = "同级校徽相碰就合成",
                icon = Icons.Default.Grain,
                color = Color(0xFFD9483B),
                statLabel = "最高分",
                stat = GameStore.bestScore(context, GameIds.MERGE).takeIf { it > 0 }?.toString(),
            ),
            GameEntry(
                route = Routes.GAME_2048,
                title = "GPA 2048",
                summary = com.xjtu.toolbox.game.g2048.Gpa2048Texts.SUMMARY,
                icon = Icons.Default.GridOn,
                color = Color(0xFFE39A1B),
                statLabel = "最高分",
                stat = GameStore.bestScore(context, GameIds.G2048).takeIf { it > 0 }?.toString(),
            ),
        )
    }
    val boards = remember {
        listOf(
            GameEntry(
                route = Routes.GAME_GOMOKU,
                title = "五子棋",
                summary = "西交执黑，对面是上交 AI",
                icon = Icons.Default.Dashboard,
                color = Color(0xFF1F9E8F),
                modes = listOf("人机", "同屏", "联机"),
                stat = battleRecord(context, GameIds.GOMOKU, listOf("easy", "hard", "hell", "local", "online")),
            ),
            GameEntry(
                route = Routes.GAME_GO,
                title = "围棋",
                summary = "9 / 13 / 19 路",
                icon = Icons.Default.Casino,
                color = Color(0xFF4C5FD5),
                modes = listOf("同屏", "联机"),
                stat = battleRecord(context, GameIds.GO, listOf("local", "online")),
            ),
            GameEntry(
                route = Routes.GAME_XIANGQI,
                title = "象棋",
                summary = "红方西交、黑方上交",
                icon = Icons.Default.School,
                color = Color(0xFFB8322E),
                modes = listOf("同屏", "联机"),
                stat = battleRecord(context, GameIds.XIANGQI, listOf("local", "online")),
            ),
        )
    }

    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "小游戏",
                largeTitle = "小游戏",
                // 这页顶栏原来用 background 而非 surface（卡片本身才是 surface），
                // 玻璃时透明，经典时保持原色，不借 glassBarColor 的默认值
                color = if (glass != null) Color.Transparent else MiuixTheme.colorScheme.background,
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        Box(
            Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .readableWidth()
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .overScrollVertical()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(glassTop + 8.dp))
                // 两款休闲游戏并排做成大卡：分数就是它们的全部，所以最高分放大当主角
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    arcade.forEach { entry ->
                        ArcadeCard(entry, onClick = { onNavigate(entry.route) }, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    "棋类对弈",
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "同屏双人，或者两台手机扫码联机",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .squircleClip(24.dp)
                        .background(AppCardColor)
                        .padding(vertical = 6.dp),
                ) {
                    boards.forEach { entry -> BoardGameRow(entry, onClick = { onNavigate(entry.route) }) }
                }
                Spacer(Modifier.height(24.dp))
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

private data class GameEntry(
    val route: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    /** 这款游戏的主色：卡面渐变、图标都从它来。 */
    val color: Color,
    /** 没玩过就是 null——显示「还没玩过」比显示「0 分」少一份挫败感。 */
    val stat: String?,
    val statLabel: String = "",
    /** 棋类支持的对局方式，显示成一排小标签。 */
    val modes: List<String> = emptyList(),
)

/** 把各难度的胜负合成一行，全是 0 时返回 null。 */
private fun battleRecord(
    context: android.content.Context,
    game: String,
    difficulties: List<String>,
): String? {
    val win = difficulties.sumOf { GameStore.wins(context, game, it) }
    val loss = difficulties.sumOf { GameStore.losses(context, game, it) }
    val draw = difficulties.sumOf { GameStore.draws(context, game, it) }
    if (win == 0 && loss == 0 && draw == 0) return null
    return buildString {
        append("${win}胜${loss}负")
        if (draw > 0) append("${draw}和")
    }
}

/**
 * 休闲游戏大卡：整张卡是这款游戏的主色渐变，右下角压一个放大的半透明图标当插画，
 * 最高分用大字——以前是白底一行字，看不出是「游戏」。
 */
@Composable
private fun ArcadeCard(entry: GameEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val origin = rememberExpandOriginSource()
    val density = LocalDensity.current
    val top = lerp(entry.color, Color.White, 0.18f)
    val bottom = lerp(entry.color, Color.Black, 0.18f)
    Box(
        modifier
            .expandOriginSource(origin)
            .squircleClip(26.dp)
            .background(Brush.linearGradient(listOf(top, entry.color, bottom)))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = {
                    origin.arm(entry.route, 26.dp, density)
                    onClick()
                },
            ),
    ) {
        Icon(
            entry.icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.16f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 18.dp, y = 18.dp)
                .size(118.dp),
        )
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier
                    .size(36.dp)
                    .squircleBackground(Color.White.copy(alpha = 0.22f), 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(entry.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(entry.title, style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Bold, color = Color.White)
            Text(entry.summary, style = MiuixTheme.textStyles.footnote1, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(18.dp))
            Text(
                if (entry.stat != null) entry.statLabel else "还没玩过",
                style = MiuixTheme.textStyles.footnote2,
                color = Color.White.copy(alpha = 0.75f),
            )
            Text(
                entry.stat ?: "开一局",
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

/** 棋类一行：App 式渐变图标、名称与说明、对局方式标签，右侧战绩。 */
@Composable
private fun BoardGameRow(entry: GameEntry, onClick: () -> Unit) {
    val origin = rememberExpandOriginSource()
    val density = LocalDensity.current
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier
            .fillMaxWidth()
            .expandOriginSource(origin)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = {
                    origin.arm(entry.route, 18.dp, density)
                    onClick()
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientAppIcon(entry.icon, entry.color, size = 50.dp, iconSize = 25.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
            Text(entry.summary, style = MiuixTheme.textStyles.footnote1, color = muted, maxLines = 1)
            if (entry.modes.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    entry.modes.forEach { mode ->
                        Text(
                            mode,
                            style = MiuixTheme.textStyles.footnote2,
                            color = entry.color,
                            modifier = Modifier
                                .squircleBackground(entry.color.copy(alpha = 0.10f), 7.dp)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            entry.stat ?: "未对局",
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (entry.stat != null) FontWeight.Bold else FontWeight.Normal,
            color = if (entry.stat != null) MiuixTheme.colorScheme.onSurface else muted.copy(alpha = 0.7f),
        )
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
    }
}
