package com.xjtu.toolbox.game.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「这一局要不要上传」的持有者。一个游戏页面一个。
 *
 * 页面的退出 / 重开入口都改走 [leave]：这局破了纪录就先挂一个询问，答完再继续原来的动作；
 * 没破纪录就直接走，不会平白多一次弹窗。进页面时 [resume] 捞一次上次没处理完的账
 * （异常退出、或结算时没传成功），所以复盘的时机是「下次打开同一个游戏」。
 *
 * 为什么不像结算那样返回 `PendingAsk` 让页面自己渲染：这里的询问发生在**页面即将销毁**的路上，
 * 得先把动作挂住（[then]），答完才放行，否则弹窗会飘到已经返回的页面上。
 */
@Stable
class RunAsker internal constructor(private val game: String) {

    /** 非空即正在问。交给 [RunAskDialog] 渲染。 */
    var offer by mutableStateOf<RunOffer?>(null)
        private set

    /** 答完之后要接着做的事（退出 / 重开）。复盘时没有后续动作，就是 null。 */
    private var then: (() -> Unit)? = null

    /** 进页面时捞一次上次残留的账。只在值得问的时候才会出现。 */
    fun resume() {
        LeaderboardRun.resume(game)?.let { offer = it }
    }

    /** 退出 / 重开前：这局破纪录就先问一句，否则直接执行 [after]。 */
    fun leave(score: Int, after: () -> Unit) {
        val pending = LeaderboardRun.abandon(game, score)
        if (pending == null) {
            after()
        } else {
            offer = pending
            then = after
        }
    }

    /** 上传沿用已有身份（没选过名字就匿名），失败留档下次补 —— 这里不弹名字框。 */
    fun answer(upload: Boolean) {
        val pending = offer ?: return
        if (upload) Leaderboard.submitQuietly(game, pending.score) else LeaderboardRun.clear(game)
        offer = null
        val next = then
        then = null
        next?.invoke()
    }
}

@Composable
fun rememberRunAsker(game: String): RunAsker {
    val asker = remember(game) { RunAsker(game) }
    LaunchedEffect(game) { asker.resume() }
    return asker
}

/** [RunAsker] 的弹窗。两个按钮就够，别把结算那套名字输入搬过来。 */
@Composable
fun RunAskDialog(asker: RunAsker) {
    val pending = asker.offer ?: return
    WindowDialog(
        show = true,
        onDismissRequest = { asker.answer(upload = false) },
        title = when (pending.end) {
            RunEnd.ABANDONED -> "本局还没上报"
            RunEnd.INTERRUPTED -> "检测到上次异常退出"
            RunEnd.UNSENT -> "上次有一局没传上去"
        },
        summary = when (pending.end) {
            RunEnd.ABANDONED -> "本局 ${pending.score} 分，超过了你之前的最高分。要上传到全服榜吗？"
            RunEnd.INTERRUPTED -> "上一局 ${pending.score} 分没走到结算就中断了。如果那是你打的，要上传吗？"
            RunEnd.UNSENT -> "上一局 ${pending.score} 分当时没上传成功，要补传一次吗？"
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { asker.answer(upload = true) },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("上传到全服榜", color = MiuixTheme.colorScheme.onPrimary)
            }
            TextButton(
                text = "不上传",
                onClick = { asker.answer(upload = false) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "只上传分数和名字，不含账号、学号。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
