package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「这一端缺的那一块」的统一说明块。
 *
 * 共享屏（日程 / 设置 / 首页）本身是三端同一份代码，某一端少接了一档能力时，那一块**不画**是对的
 * ——画出来点一下就露馅；但什么都不说，用户只会觉得这端坏了。于是缺口说明走宿主槽位
 * （`ScheduleHost.gapNotes` / `SettingsHost.gapNote` / `HomeHost.gapNote`，默认空 ⇒ 不实现的端一行都不用改），
 * 由各端自己给文案，共享屏把这段文字画在「本来就不画那一块」的位置上。
 *
 * 文案口径必须是三件事一起说清：**这一端没有**什么 / **为什么**没有 / **去哪儿能用**。
 * 只说前两件等于告诉用户「就这样吧」，第三件才是他要的出路。
 *
 * 视觉照 web 已有的 `WebGapNote`（卡片 + 11sp + `onBackgroundVariant`）：同一种「这是说明，不是功能」
 * 的观感，不抢主内容的注意力，也不用强调色——它不是错误。
 */
@Composable
fun GapNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        insideMargin = PaddingValues(10.dp),
    ) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            fontSize = 11.sp,
        )
    }
}
