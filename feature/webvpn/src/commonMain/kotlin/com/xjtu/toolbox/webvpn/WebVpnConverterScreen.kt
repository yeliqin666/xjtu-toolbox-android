package com.xjtu.toolbox.webvpn

import com.xjtu.toolbox.util.redactUrl
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.*
import com.xjtu.toolbox.error.FriendlyError
import kotlinx.coroutines.launch
import com.xjtu.toolbox.platform.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.glass.*
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Android / 桌面那条路：本端有同步实现（`:data` 的 `WebVpnUtil`，纯字符串运算）⇒包一层 [asAsyncCodec] 交给下面那个实现。 */
@Composable
fun WebVpnConverterScreen(
    codec: WebVpnCodec,
    onOpenWithWebVpn: (vpnUrl: String) -> Unit,
    onBack: () -> Unit,
    onOpenApply: (() -> Unit)? = null,
) = WebVpnConverterScreen(codec.asAsyncCodec(), onOpenWithWebVpn, onBack, onOpenApply)

/**
 * WebVPN 网址互转工具：
 * - 校内 URL ↔ WebVPN URL 双向转换
 * - 转换后可通过内置浏览器（自动复用 WebVPN session）打开
 *
 * 这是**那个实现**（转换本体是异步的）。为什么要有两个重载：两个形态的类型不同
 * （[WebVpnCodec] / [WebVpnAsyncCodec]），而 Kotlin 接口不能同时声明同步与 `suspend` 的同名方法 ⇒
 * 「本端有实现」与「转换在别处」只能各占一条（见 [WebVpnAsyncCodec] 的 KDoc）。真实的实现都只实现
 * 其中之一，所以重载解析没有歧义。
 *
 * @param codec 两个方向的转换本体（[WebVpnAsyncCodec]）。为什么是**参数**而不是直接调
 *              `:data` 的 `WebVpnUtil`：那个对象要用 `javax.crypto` 的 AES-128-CFB 加密域名，
 *              而这一屏在 `:core` 的 commonMain（三个目标，含 wasmJs）⇒ 实现按平台的能力缝由宿主
 *              注入：Android / 桌面传同步那份 `WebVpnUtil`（走上面那个同名的重载），`:web` 传
 *              `CampusWebVpnApi`（转换在服务端，`POST /api/webvpn/convert`，契约 §5.7）。
 * @param onOpenWithWebVpn 用户点击访问按钮时调用，参数为最终要访问的（已转为 WebVPN 域的）URL。
 *                        `:app` 那侧外层没连上 WebVPN 时会用已存账号自动登录，再进入 BrowserScreen；
 *                        桌面端没有内置浏览器，直接把地址交给系统浏览器。
 * @param onOpenApply 「去申请 WebVPN 权限」（打开 [WebVpnDenied.APPLY_URL]）；为 null 的端不显示这张卡。
 */
@Composable
fun WebVpnConverterScreen(
    codec: WebVpnAsyncCodec,
    onOpenWithWebVpn: (vpnUrl: String) -> Unit,
    onBack: () -> Unit,
    onOpenApply: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    var inputUrl by remember { mutableStateOf("") }
    var convertedUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isReversed by remember { mutableStateOf(false) } // false=原始→VPN, true=VPN→原始

    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "WebVPN",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        val introCard: @Composable () -> Unit = {
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
                        "校外访问内网要经 webvpn.xjtu.edu.cn 代理。这里可双向转换网址，也能直接用内置浏览器打开（已登录免验证）。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding.withoutTop(glass))
                .glassSource(glass)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Spacer(Modifier.height(glassTop))
            // 宽屏两栏：左边是真正要用的转换卡，右边放说明和速查。以前整页限宽 720 居中，
            // 三张卡竖着排，平板横屏两边各空一大块。窄屏转换卡也放到最前面：进来就是为了转网址。
            com.xjtu.toolbox.ui.adaptive.AdaptiveTwoColumns(
                verticalSpacing = 12.dp,
                firstWeight = 1.2f,
                first = {
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
                    AppSegmentedTabs(
                        tabs = listOf("原始 → WebVPN", "WebVPN → 原始"),
                        selectedTabIndex = if (isReversed) 1 else 0,
                        onTabSelected = { tab ->
                            isReversed = tab == 1
                            convertedUrl = ""
                            error = null
                        },
                        embedded = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = inputUrl,
                        onValueChange = {
                            inputUrl = it
                            convertedUrl = ""
                            error = null
                        },
                        label = if (!isReversed) "校内网址（如 kq.xjtu.edu.cn/）" else "WebVPN 网址",
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
                            // 转换可能是一次 **HTTP 往返**（`:web` 的取数就是那一发）⇒ 一律走协程。
                            // Android / 桌面那份实现是同步的，照样立刻拿到结果（不多一次延迟）。
                            scope.launch {
                                val reversed = isReversed
                                error = null
                                val result = runCatching {
                                    if (!reversed) codec.toVpnUrl(normalized) else codec.fromVpnUrl(normalized)
                                }
                                val converted = result.getOrNull()
                                if (converted.isNullOrBlank()) {
                                    convertedUrl = ""
                                    // 两种“没拿到”分开说：实现自己抛的错（401 闸门 / 网络那类）用它那句话，
                                    // 而 from_vpn 解不出来那一档是取数实现**如实返回 null**（契约 §5.7 的 400），
                                    // 屏上那句话正好说的是这件事。
                                    error = result.exceptionOrNull()?.let { FriendlyError.of(it, "转换") }
                                        ?: if (reversed) "无法解析此 WebVPN 网址，请确认格式正确"
                                        else "转换失败，请稍后重试"
                                } else {
                                    convertedUrl = converted
                                }
                                Log.d("WebVpnConverter", "convert reversed=$reversed input=$normalized output=${convertedUrl.redactUrl()}")
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
                                // 没连上会自动登录再打开，用户不用先去别处登录
                                Text("用 WebVPN 打开")
                            }
                        }
                    }
                }
            }

                },
                second = {
            introCard()
            // 申请入口：提示「学校 WebVPN 没给你的账号开通…」的用户来这儿
            if (onOpenApply != null) {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "用不了 WebVPN？",
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "WebVPN 要先在学校事务中心申请权限，通过后才能从校外访问。点下面按钮直达申请页，已带上你的登录状态。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onOpenApply, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("去申请 WebVPN 权限")
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
                        "考勤" to "https://kq.xjtu.edu.cn/"
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
                }
            }
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
