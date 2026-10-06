package com.xjtu.toolbox.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Web 端外壳：底部两格（课表 / 后端自检），形态照 :app 的「底部导航 + 每格一块屏」。
 *
 * 两块屏都不是装饰，它们分别是第 2 步的两条验收：
 *   - **课表**：`:core` 的课表层在浏览器里跑**真数据**（campus-api 的当前学期 + 47 列原始行），
 *     证明共享层不是「能编译」而是「能用」；
 *   - **自检**：把「同源可用、跨源直连被 CORS 挡」做成页面上看得见的结果，
 *     省得下次有人再去猜「为什么浏览器里拿不到数据」。
 *
 * 深链：`?tab=probe` 直接落在自检屏（无头浏览器截图与控制台排障用）。
 */
@Composable
fun ToolboxWebApp() {
    var tab by remember { mutableStateOf(if (initialTabIsProbe()) 1 else 0) }
    val cs = MiuixTheme.colorScheme

    Column(modifier = Modifier.fillMaxSize().background(cs.background)) {
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                0 -> ScheduleScreen()
                else -> ProbeScreen()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).background(cs.surface),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem("课表", tab == 0, cs) { tab = 0 }
            NavItem("后端自检", tab == 1, cs) { tab = 1 }
        }
    }
}

@Composable
private fun NavItem(label: String, active: Boolean, cs: Colors, onClick: () -> Unit) {
    Box(modifier = Modifier.padding(horizontal = 4.dp).clickable { onClick() }) {
        Text(
            label,
            color = if (active) cs.primary else cs.onBackgroundVariant,
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
