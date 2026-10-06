package com.xjtu.toolbox.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.schedule.CampusScheduleApi
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 后端自检 —— 交接文档 §3「第 2 步」要求的那个 **CORS 探针**，做成了页面上看得见的结果。
 *
 * 结论先写在这里，页面只是把它复现出来：
 *  1. **同源 `/api/...` 可用**（开发期由 webpack dev-server 反代、生产由 nginx 反代到 `:3099`）；
 *  2. **跨源直连 `http://127.0.0.1:3099` 在浏览器里必失败** —— campus-api 刻意不给零鉴权端点
 *     发 `Access-Control-Allow-Origin`（否则任意网页都能读个人数据）。所以「Web 端必须同源反代」
 *     不是选型偏好，而是硬约束；这也解释了为什么 `:core` 的 `CampusApi` 把 `baseUrl` 留成参数。
 */
@Composable
fun ProbeScreen() {
    val cs = MiuixTheme.colorScheme
    var results by remember { mutableStateOf<List<ProbeResult>?>(null) }

    LaunchedEffect(Unit) {
        val client = toolboxWebClient()
        val list = mutableListOf<ProbeResult>()
        list += probe(client, "同源 · CAS 登录态（裸对象）", "$API_BASE/api/status")
        list += probe(client, "同源 · 当前学期", "$API_BASE/api/jwxt/term")
        list += probe(client, "同源 · 开学日 / 总周数", "$API_BASE/api/jwxt/term-start")
        // 课表要用当前学期；拿不到就退化成不带 term 的请求，让失败结果自己说话
        val term = runCatching { CampusScheduleApi(client, API_BASE).load().term }.getOrNull()
        list += probe(client, "同源 · 本学期课表行", "$API_BASE/api/jwxt/schedule" + (term?.let { "?term=$it" } ?: ""))
        list += probe(client, "跨源直连 :3099（预期失败）", "$DIRECT_BASE/api/status")
        results = list
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Web 数据源自检",
            color = cs.onSurface,
            fontSize = 17.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
        Text(
            "同一个 :core 客户端（Ktor JS 引擎）分别打同源反代与跨源直连；" +
                "下面最后一行的失败就是「浏览器必须同源」的证据。",
            color = cs.onBackgroundVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(8.dp))

        val list = results
        if (list == null) {
            Text("探测中…", color = cs.onSurface, modifier = Modifier.padding(14.dp))
            return@Column
        }
        list.forEach { r ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                insideMargin = PaddingValues(12.dp),
            ) {
                Row {
                    Text(if (r.ok) "✓" else "✗", color = if (r.ok) Color(0xFF2E7D32) else cs.error, fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("${r.label}  (${r.ms} ms)", color = cs.onSurface, fontSize = 13.sp)
                        Text(r.url, color = cs.onBackgroundVariant, fontSize = 10.sp)
                        Text(r.detail, color = cs.onBackgroundVariant, fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
