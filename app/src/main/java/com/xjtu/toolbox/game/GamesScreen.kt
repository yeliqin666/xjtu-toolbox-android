package com.xjtu.toolbox.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.xjtu.toolbox.ui.isWideLayout
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
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
    val entries = remember {
        listOf(
            GameEntry(
                route = Routes.GAME_MERGE,
                title = "合成西交大",
                summary = "同级校徽相碰就合成，一路合到西交大",
                icon = Icons.Default.Grain,
                stat = GameStore.bestScore(context, GameIds.MERGE)
                    .takeIf { it > 0 }?.let { "最高 $it 分" },
            ),
            GameEntry(
                route = Routes.GAME_2048,
                title = "GPA 2048",
                summary = "1.0 一路合到 4.3，绩点版 2048",
                icon = Icons.Default.GridOn,
                stat = GameStore.bestScore(context, GameIds.G2048)
                    .takeIf { it > 0 }?.let { "最高 $it 分" },
            ),
            GameEntry(
                route = Routes.GAME_GOMOKU,
                title = "五子棋",
                summary = "西交执黑，对面是上交 AI",
                icon = Icons.Default.Dashboard,
                stat = battleRecord(context, GameIds.GOMOKU, listOf("easy", "hard", "hell", "local", "online")),
            ),
            GameEntry(
                route = Routes.GAME_GO,
                title = "围棋",
                summary = "9/13/19 路，同屏双人",
                icon = Icons.Default.Casino,
                stat = battleRecord(context, GameIds.GO, listOf("local", "online")),
            ),
            GameEntry(
                route = Routes.GAME_XIANGQI,
                title = "象棋",
                summary = "红方西交、黑方上交，同屏双人",
                icon = Icons.Default.School,
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
            LazyVerticalGrid(
                // 窄屏一列（卡里要放得下一行说明和一行战绩），宽屏两列。
                columns = GridCells.Fixed(if (isWideLayout()) 2 else 1),
                modifier = Modifier
                    .readableWidth()
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .overScrollVertical(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = glassTop + 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.route }) { entry ->
                    GameCard(entry, onClick = { onNavigate(entry.route) })
                }
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
    /** 没玩过就是 null——显示「还没玩过」比显示「0 分」少一份挫败感。 */
    val stat: String?,
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

@Composable
private fun GameCard(entry: GameEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                entry.icon,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    entry.summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.stat ?: "还没玩过",
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (entry.stat != null) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                    },
                )
            }
        }
    }
}
