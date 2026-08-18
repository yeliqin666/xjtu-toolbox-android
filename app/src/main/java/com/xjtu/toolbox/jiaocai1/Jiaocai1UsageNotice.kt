package com.xjtu.toolbox.jiaocai1

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

private const val PREFS = "feature_hints"
private const val KEY = "jiaocai1_hint_shown"

/**
 * 首次进入教材全文库时的使用声明。
 * 用 WindowBottomSheet：浏览页和阅读器都能弹，不依赖哪一层 Scaffold。
 */
@Composable
fun Jiaocai1UsageNotice() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val show = remember { mutableStateOf(!prefs.getBoolean(KEY, false)) }
    if (!show.value) return

    fun dismiss() {
        show.value = false
        prefs.edit().putBoolean(KEY, true).apply()
    }

    BackHandler { dismiss() }
    WindowBottomSheet(
        show = show.value,
        title = "使用声明",
        onDismissRequest = { dismiss() },
    ) {
        Column(Modifier.padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(
                "教材全文库仅供本校师生线上教学与个人学习使用。本功能不开放下载。",
                style = MiuixTheme.textStyles.body1,
            )
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(16.dp))
            Button(onClick = { dismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text("我已阅读并同意")
            }
        }
    }
}
