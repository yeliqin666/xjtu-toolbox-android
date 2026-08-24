package com.xjtu.toolbox.feedback

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.util.ServiceUsageTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主动询问：冷启动后问一次「最近用着怎么样」。
 *
 * 用 BottomSheet 而不是首页横幅——横幅占 Hero 位置、关了也还在信息流里晃；
 * Sheet 滑掉就结束。节流规则仍在 [FeedbackStore.shouldPrompt]：每版本至多一次、
 * 两次至少隔两周、且用户真的用过一阵子。答了或划走都记账，不会再追问。
 *
 * 必须写在某个 Scaffold 的 content 里，OverlayBottomSheet 才找得到宿主。
 * 延迟弹出，给启动公告 / 更新框先走，避免两层叠在一起。
 */
@Composable
fun FeedbackPromptSheet(
    onOpenFeedback: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var show by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val used = ServiceUsageTracker.counts(context, TRACKED_KEYS).values.sum()
        if (!FeedbackStore.shouldPrompt(context, BuildConfig.VERSION_CODE, used)) return@LaunchedEffect
        delay(2200)
        armed = true
        show = true
    }
    if (!armed) return

    var answered by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }

    fun close() {
        FeedbackStore.markPrompted(context, BuildConfig.VERSION_CODE)
        show = false
    }

    fun send(rating: String, text: String) {
        // 失败就静默丢弃：这是我们主动打扰用户换来的一句话，不值得再弹一个错误提示
        val ticket = FeedbackStore.newTicket()
        scope.launch {
            runCatching {
                FeedbackApi.submit(
                    ticket = ticket,
                    category = "版本评价 · " + rating,
                    content = text.ifBlank { "（未填写）" },
                    contact = "",
                    anonId = FeedbackStore.anonId(context),
                )
            }.onSuccess {
                FeedbackStore.addTicket(
                    context,
                    FeedbackStore.Ticket(ticket, "版本评价", text, System.currentTimeMillis())
                )
            }
        }
        FeedbackStore.markPrompted(context, BuildConfig.VERSION_CODE)
        sent = true
    }

    BackHandler(enabled = show) { close() }
    OverlayBottomSheet(
        show = show,
        title = "v${BuildConfig.VERSION_NAME} 用着还顺手吗？",
        onDismissRequest = { close() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            if (sent) {
                Text(
                    "谢了，收到。有回复会出现在「我的 · 反馈与建议」里。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(
                    text = "知道了",
                    onClick = { show = false },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )
                return@Column
            }

            Text(
                "一句话就行，不用注册。划掉就不会再问。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(16.dp))

            if (answered == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = "挺好",
                        onClick = { answered = "好用" },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "有问题",
                        onClick = { answered = "有问题" },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "别问了",
                        onClick = { close() },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                TextField(
                    value = note,
                    onValueChange = { note = it },
                    label = if (answered == "有问题") "哪儿不对？" else "有什么想加的功能吗？（选填）",
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = "发送",
                        onClick = { send(answered.orEmpty(), note.trim()) },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "写详细点",
                        onClick = { close(); onOpenFeedback() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// 只是用来估算"这人用了多少"，不需要跟服务列表严格同步
private val TRACKED_KEYS = listOf(
    "schedule", "score", "card", "library", "emptyroom",
    "lms", "attendance", "classreplay", "jiaocai1", "agent",
)
