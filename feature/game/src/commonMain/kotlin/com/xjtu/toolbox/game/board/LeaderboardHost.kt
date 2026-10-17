package com.xjtu.toolbox.game.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 榜单浮层的宿主：只画玩家主动打开的榜单。
 *
 * 和 MFA 弹窗（Android 的 MfaDialogHost、桌面的 DesktopMfaDialog）一样挂在导航外层，用自带 Window 的 [WindowDialog]——
 * Overlay* 只能渲染在页面自己的 Scaffold 里，而榜单是在游戏里被点开的，挂一次覆盖所有页面才靠谱。
 *
 * 「上榜询问」不在这里：它属于发起那一局的那个页面，见 [LeaderboardAskDialog]。
 */
@Composable
fun LeaderboardHost() {
    (Leaderboard.sheet as? Leaderboard.Sheet.Ranking)?.let { RankingDialog(it) }
    // 改名框盖在榜单之上：改完 finishRename() 会把榜单的 stamp +1，让它重新拉一次
    Leaderboard.renaming?.let { RenameDialog(it) }
}

/**
 * 一局结束后够上榜时的询问。由**游戏页面自己**挂载并把 [pending] 传进来：
 * 页面一销毁弹窗就跟着消失，不会飘到已经返回的别的页面上。
 *
 * 这个弹窗**点空白处不关**，只能点右上角的 ×（或下面「这次不上榜」）：游戏刚结束时
 * 手指还在屏幕上，误触把询问点掉就等于白白丢了一次上榜机会。
 *
 * 名字留空就是匿名，按钮文案跟着变，省得再去解释一遍「留空=匿名」。
 * 「后续上榜不再提示」默认勾上：绝大多数人不想每破一次纪录就被问一次。
 *
 * 标题与副标题没有交给 [WindowDialog] 画，而是自己排了一行——这样 × 才能落在右上角。
 * 字号字色照抄 DialogContentLayout 里那份，视觉上和别的弹窗一致。
 */
@Composable
fun LeaderboardAskDialog(pending: Leaderboard.PendingAsk?, onDone: () -> Unit) {
    if (pending == null) return
    val scope = rememberCoroutineScope()
    var name by remember(pending) { mutableStateOf("") }
    var silent by remember(pending) { mutableStateOf(true) }
    var busy by remember(pending) { mutableStateOf(false) }
    // 出错时留在弹窗里显示的话；非空即说明上一次提交没成
    var err by remember(pending) { mutableStateOf<String?>(null) }

    WindowDialog(
        show = true,
        // null = 点空白处和按返回键都不关，只有下面 × 与「这次不上榜」两条明路
        onDismissRequest = null,
    ) {
        Column(Modifier.fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                    Text(
                        "上榜了",
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${boardTitle(pending.game)} 全服榜 · ${pending.score} 分",
                        fontSize = MiuixTheme.textStyles.body1.fontSize,
                        textAlign = TextAlign.Center,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(
                    onClick = { if (!busy) onDone() },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
                }
            }
            TextField(
                value = name,
                onValueChange = { name = it.take(Leaderboard.MAX_NAME); err = null },
                label = "名字（留空匿名）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(remember { MutableInteractionSource() }, SinkFeedback()) { silent = !silent }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(state = if (silent) ToggleableState.On else ToggleableState.Off, onClick = null)
                Spacer(Modifier.width(10.dp))
                Text("后续上榜不再提示", style = MiuixTheme.textStyles.body1)
            }
            Text(
                "上榜只上传名字和分数，不含账号、学号。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            err?.let {
                Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
            }
            val cleaned = Leaderboard.sanitizeName(name)
            Button(
                onClick = {
                    busy = true
                    err = null
                    scope.launch {
                        // answer 失败时也会把选择记下来：同一个问题不该反复问
                        when (val r = Leaderboard.answer(pending.game, pending.score, cleaned, silent)) {
                            Leaderboard.AnswerResult.OK -> onDone()
                            // 服务端有话说（名字不合适／被占用／审核没跑成）就留在原地，原样转达
                            is Leaderboard.AnswerResult.Rejected -> {
                                busy = false
                                err = r.message
                            }
                            Leaderboard.AnswerResult.Failed -> {
                                busy = false
                                err = "没能传上去，检查一下网络再试。"
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColorsPrimary(),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        busy -> "上传中…"
                        cleaned == null -> "匿名上榜"
                        else -> "用「$cleaned」上榜"
                    },
                    color = MiuixTheme.colorScheme.onPrimary,
                )
            }
            TextButton(
                text = "这次不上榜",
                onClick = onDone,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 榜单本体。只画名次、名字、分数三列，前三名给个奖牌。 */
@Composable
private fun RankingDialog(sheet: Leaderboard.Sheet.Ranking) {
    val title = boardTitle(sheet.game)
    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<BoardSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // stamp：改名成功后由 Leaderboard.finishRename() 递增，用来刷新列表
    // reload：这一页自己的「重试」按钮
    LaunchedEffect(sheet.game, sheet.stamp, reload) {
        loading = true
        error = null
        runCatching { LeaderboardApi.board(sheet.game, LeaderboardStore.uid(), Leaderboard.LIMIT) }
            .onSuccess { snapshot = it }
            .onFailure { error = it.message ?: "榜单暂时打不开" }
        loading = false
    }

    WindowDialog(
        show = true,
        onDismissRequest = { Leaderboard.dismiss() },
        title = "$title 全服榜",
        summary = snapshot?.let { if (it.total > 0) "共 ${it.total} 人上榜" else null },
    ) {
        val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
        when {
            loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(size = 24.dp, strokeWidth = 2.5.dp)
            }

            error != null -> Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(error!!, style = MiuixTheme.textStyles.footnote1, color = muted, textAlign = TextAlign.Center)
                TextButton(text = "重试", onClick = { reload++ }, modifier = Modifier.fillMaxWidth())
            }

            else -> {
                val board = snapshot
                val mine = board?.me
                if (board == null || board.rows.isEmpty()) {
                    Text(
                        "这个榜还是空的，打一局就是第一名。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        board.rows.forEach { row -> BoardRowView(row, isMe = row.rank == mine?.rank, muted) }
                    }
                }
                // 自己那一行固定在底部：名次（百名开外也看得见）+ 改名入口。
                // 在榜内时上面已经高亮过一行，这里再列一次是有意的——改名按钮得有个固定位置。
                if (mine != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "你的名次",
                            style = MiuixTheme.textStyles.footnote2,
                            color = muted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "改名",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(remember { MutableInteractionSource() }, SinkFeedback()) {
                                    Leaderboard.startRename(sheet.game, mine.name, mine.score)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    BoardRowView(mine, isMe = true, muted)
                }
            }
        }
    }
}

/**
 * 改名。改的就是榜单上自己那一行——服务端按 `(game, uid)` 更新，分数保留。
 *
 * 和上榜询问不同，这里点空白处可以直接取消：改名没提交之前没有任何东西会丢，
 * 误触关掉只是"这次不改了"。真提交时用的是同一套闸（词表、云审核、昵称独占）。
 */
@Composable
private fun RenameDialog(state: Leaderboard.Renaming) {
    val scope = rememberCoroutineScope()
    var name by remember(state) { mutableStateOf(state.current.orEmpty()) }
    var busy by remember(state) { mutableStateOf(false) }
    var err by remember(state) { mutableStateOf<String?>(null) }

    WindowDialog(
        show = true,
        onDismissRequest = { if (!busy) Leaderboard.cancelRename() },
        title = "改名",
        summary = "${boardTitle(state.game)} 全服榜",
    ) {
        Column(Modifier.fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = name,
                onValueChange = { name = it.take(Leaderboard.MAX_NAME); err = null },
                label = "名字（留空匿名）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "改的是榜上那一行，分数保留。名字不能和别人重复。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            err?.let {
                Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
            }
            val cleaned = Leaderboard.sanitizeName(name)
            val unchanged = cleaned == state.current
            Button(
                onClick = {
                    busy = true
                    err = null
                    scope.launch {
                        when (val r = Leaderboard.rename(state.game, state.score, cleaned)) {
                            Leaderboard.AnswerResult.OK -> Leaderboard.finishRename()
                            is Leaderboard.AnswerResult.Rejected -> {
                                busy = false
                                err = r.message
                            }
                            Leaderboard.AnswerResult.Failed -> {
                                busy = false
                                err = "没能传上去，检查一下网络再试。"
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColorsPrimary(),
                enabled = !busy && !unchanged,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        busy -> "提交中…"
                        unchanged -> "名字没变"
                        cleaned == null -> "改为匿名"
                        else -> "改为「$cleaned」"
                    },
                    color = MiuixTheme.colorScheme.onPrimary,
                )
            }
            TextButton(
                text = "取消",
                onClick = { Leaderboard.cancelRename() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BoardRowView(row: BoardRow, isMe: Boolean, muted: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isMe) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (row.rank) {
                1 -> "🥇"
                2 -> "🥈"
                3 -> "🥉"
                else -> "${row.rank}"
            },
            style = MiuixTheme.textStyles.footnote1,
            color = if (row.rank <= 3) muted else muted.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(30.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            row.name,
            style = MiuixTheme.textStyles.body2,
            fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${row.score}",
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Bold,
        )
    }
}
