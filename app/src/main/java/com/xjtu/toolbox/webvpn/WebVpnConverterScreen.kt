package com.xjtu.toolbox.webvpn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.util.WebVpnUtil
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * WebVPN 网址互转工具：
 * - 校内 URL ↔ WebVPN URL 双向转换
 * - 转换后可通过内置浏览器（自动复用 WebVPN session）打开
 *
 * @param isWebVpnReady WebVPN session 是否已建立（vpnClient != null）。
 * 若就绪，"WebVPN 访问"按钮直接打开浏览器；否则触发 navigate（其中含登录流程）。
 * @param onOpenWithWebVpn 用户点击访问按钮时调用，参数为最终要访问的（已转为 WebVPN 域的）URL。
 *                        外层负责确保 vpnClient 已就绪后再进入 BrowserScreen。
 */
@Composable
fun WebVpnConverterScreen(
    isWebVpnReady: Boolean,
    onOpenWithWebVpn: (vpnUrl: String) -> Unit,
    onBack: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    var inputUrl by remember { mutableStateOf("") }
    var convertedUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isReversed by remember { mutableStateOf(false) } // false=原始→VPN, true=VPN→原始

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "WebVPN 网址互转",
                color = MiuixTheme.colorScheme.surfaceVariant,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 介绍卡
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "什么是 WebVPN",
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "校外访问交大内网时，需要通过 webvpn.xjtu.edu.cn 代理。此工具可双向转换网址，并可直接在内置浏览器里用 WebVPN 打开（已登录时无需再次验证）。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            // 转换卡
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Cached,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "网址转换",
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    TabRowWithContour(
                        tabs = listOf("原始 → WebVPN", "WebVPN → 原始"),
                        selectedTabIndex = if (isReversed) 1 else 0,
                        onTabSelected = { tab ->
                            isReversed = tab == 1
                            convertedUrl = ""
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = inputUrl,
                        onValueChange = {
                            inputUrl = it
                            convertedUrl = ""
                            error = null
                        },
                        label = if (!isReversed) "校内网址（如 bkkq.xjtu.edu.cn/）" else "WebVPN 网址",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            color = MiuixTheme.colorScheme.error,
                            style = MiuixTheme.textStyles.footnote1
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val raw = inputUrl.trim()
                            if (raw.isBlank()) {
                                error = "请先输入网址"
                                return@Button
                            }
                            val normalized = if (!isReversed && !raw.startsWith("http://") && !raw.startsWith("https://")) {
                                "https://$raw"
                            } else raw
                            convertedUrl = if (!isReversed) {
                                WebVpnUtil.getVpnUrl(normalized)
                            } else {
                                WebVpnUtil.getOriginalUrl(normalized).orEmpty().also {
                                    if (it.isBlank()) error = "无法解析此 WebVPN 网址，请确认格式正确"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("转换") }

                    if (convertedUrl.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text("转换结果", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                convertedUrl,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary
                            )
                        }
                        // 仅在「原始→WebVPN」方向显示访问按钮（结果是 webvpn URL，可以直接在浏览器打开）
                        if (!isReversed && convertedUrl.startsWith("http")) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { onOpenWithWebVpn(convertedUrl) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isWebVpnReady) "用 WebVPN 打开" else "登录 WebVPN 后打开"
                                )
                            }
                        }
                    }
                }
            }

            // 示例提示卡
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "常用站点速查",
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val examples = listOf(
                        "教务系统" to "https://jwxt.xjtu.edu.cn/",
                        "图书馆主页" to "https://www.lib.xjtu.edu.cn/",
                        "一网通办" to "https://ywtb.xjtu.edu.cn/",
                        "本科考勤" to "https://bkkq.xjtu.edu.cn/"
                    )
                    examples.forEach { (name, url) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(0.3f))
                            Text(
                                url,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.weight(0.7f)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点击「转换」即可生成对应 WebVPN 地址，并可一键访问。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
