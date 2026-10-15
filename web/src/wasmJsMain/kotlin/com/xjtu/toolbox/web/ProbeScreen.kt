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
import com.xjtu.toolbox.home.AppServices
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
        // ── 本端支持清单（`WebSupport`，与首页宫格/学辅目录同一份真源）在页面上的现场快照 ──
        // 它不联网、也不依赖上面那几发探测：只想让“哪些能点、哪些还没有”在一张页上可核。
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            insideMargin = PaddingValues(12.dp),
        ) {
            Column {
                Text(
                    "本端支持的路由（${WebSupport.openableRouteIds.size} 条）",
                    color = cs.onSurface,
                    fontSize = 13.sp,
                )
                Text(
                    WebSupport.openableRouteIds.sorted().joinToString(" · "),
                    color = cs.onBackgroundVariant,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "服务目录里能点进去的（${WebSupport.openableCatalog.size}/${AppServices.all.size}）：" +
                        WebSupport.openableCatalog.joinToString(" · ") { it.title },
                    color = cs.onBackgroundVariant,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "目录里有、本端还没有屏的（${WebSupport.unsupportedCatalog.size}）：" +
                        WebSupport.unsupportedCatalog.joinToString(" · ") { it.title },
                    color = cs.onBackgroundVariant,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "这张清单与 `AppPage` 的 `when` 分支必须同时改（加一屏要改哪两处，见 `WebSupport` 的 KDoc）：" +
                        "只加分支不加清单 = 那一屏没有入口；只加清单不加分支 = 点进去会看到「清单与分支对不上」。",
                    color = cs.primary,
                    fontSize = 10.sp,
                )
            }
        }
        // ── 浏览器能力（D12 ③）：三样（相机扫码 / 剪贴板 / WebCrypto）在**这一份部署里**到底在不在 ──
        // 它们跟地址栏里的地址走（安全上下文），所以只在文档里写没用：这里现场列出来，
        // 而且每一行都带「本端谁在用 / 差的是什么」—— 见 `WebBrowserCapabilities` 的 KDoc。
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            insideMargin = PaddingValues(12.dp),
        ) {
            Column {
                Text("浏览器能力（这一份部署的）", color = cs.onSurface, fontSize = 13.sp)
                Text(
                    "这几条不是代码能决定的，而是**当前这个地址**决定的（安全上下文）：HTTPS 或 localhost " +
                        "有，明文 HTTP + 非 localhost 没有（契约 §3.5；--https 见 D11）。",
                    color = cs.onBackgroundVariant,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(6.dp))
                WebBrowserCapabilities.rows().forEach { row ->
                    Row {
                        Text(
                            if (row.present) "✓" else "✗",
                            color = if (row.present) Color(0xFF2E7D32) else cs.error,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(row.label, color = cs.onSurface, fontSize = 12.sp)
                            Text(row.note, color = cs.onBackgroundVariant, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
