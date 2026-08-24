package com.xjtu.toolbox.jiaocai1

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private const val PREFS = "feature_hints"
private const val KEY = "jiaocai1_hint_shown"

/**
 * 首次进入教材全文库时的使用声明。
 * 用 WindowDialog：浏览页和阅读器都能弹，不依赖哪一层 Scaffold。
 *
 * 这是一份需要用户真正表态的声明，所以**只有点「我已阅读并同意」才算同意**：
 * 点弹窗外部不关闭（[WindowDialog] 的 onDismissRequest 传 null 即为空操作），
 * 返回键等同于「不同意」——退出功能，且不写入已读标记，下次进来照旧弹。
 *
 * @param onDecline 用户拒绝或按返回时调用，应当把用户带离本功能。
 */
@Composable
fun Jiaocai1UsageNotice(onDecline: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val show = remember { mutableStateOf(!prefs.getBoolean(KEY, false)) }
    if (!show.value) return

    /** 同意：记下标记，以后不再弹。 */
    fun accept() {
        show.value = false
        prefs.edit().putBoolean(KEY, true).apply()
    }

    /** 拒绝：不留标记，直接退出功能。 */
    fun decline() {
        show.value = false
        onDecline()
    }

    BackHandler { decline() }
    WindowDialog(
        show = show.value,
        title = "使用声明",
        summary = "教材全文库仅供本校师生线上教学与个人学习使用。本功能不开放下载。",
        // 传 null：点外部不再被当作同意
        onDismissRequest = null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "请遵守相关法律法规及《西安交通大学保护数字资源知识产权公告》，并遵循合理使用原则。严禁以任何手段下载、转发全文，严禁用于任何商业目的。不得将统一身份认证账号提供给他人使用。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "如发现违规，图书馆将协助学校追查；法律后果由违规者自负。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = "我已阅读并同意",
                onClick = { accept() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = "不同意并返回",
                onClick = { decline() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
