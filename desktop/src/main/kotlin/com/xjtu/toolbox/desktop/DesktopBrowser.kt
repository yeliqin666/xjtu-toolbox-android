package com.xjtu.toolbox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.browser.shareableUrl
import com.xjtu.toolbox.webvpn.WebVpnCodec
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** `AppRoute.Browser` 在桌面端的等价物 —— 外链中转页（把地址交给系统浏览器）。 */
@Composable
internal fun DesktopBrowserScreen(
    url: String,
    then: String,
    codec: WebVpnCodec,
    onNavigate: (DesktopTarget) -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val external = remember(url, codec) { shareableUrl(url, codec) }
    val clipboard = LocalClipboardManager.current
    var copied by remember(url) { mutableStateOf(false) }

    Column(
        // 自己画底：离屏证据图里没有窗口，不画的话深色模式下会变成「浅字透明底」（与
        // `HostUnavailableScreen` 同一条理由）。
        modifier = Modifier.fillMaxSize().background(cs.background).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("浏览器", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("桌面端没有内嵌浏览器", color = cs.primary, fontSize = 13.sp)
        Text(
            "这条路由在 :app 是 WebView 里的内置浏览器（地址栏 / 前进后退 / 注入 App 的校园会话 / " +
                "夜间模式），桌面端没有 WebView 这一层（Compose Desktop 的渲染栈是 skiko/Skia，" +
                "依赖里也没有 JCEF / JavaFX WebView）⇒ 这一端只做「交出去」那件事：" +
                "把地址交给系统浏览器，本窗给去向。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )

        if (url.isBlank()) {
            Text("这条路由没带目标网址", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                "AppRoute.Browser 的 url 是空的（深链里没写 url=、或从索引页直接点进来）。" +
                    "正常进来的路是通知里的外链、教师主页的「在浏览器中打开」、消息与屁岱里的链接。",
                color = cs.onBackgroundVariant,
                fontSize = 12.sp,
            )
        } else {
            Text("目标网址", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(url, color = cs.onBackgroundVariant, fontSize = 12.sp)
            if (external != url) {
                Text(
                    "这是 WebVPN 网关地址（webvpn.xjtu.edu.cn）—— 系统浏览器没有本 App 的网关会话，" +
                        "所以交给它的是还原后的原始地址（与 :app 那颗「在外部浏览器打开」同一条口径）：",
                    color = cs.onBackgroundVariant,
                    fontSize = 12.sp,
                )
                Text(external, color = cs.primary, fontSize = 12.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = "在系统浏览器打开",
                    onClick = { openInBrowser(external) },
                    minWidth = 160.dp,
                )
                TextButton(
                    text = if (copied) "已复制" else "复制链接",
                    onClick = {
                        clipboard.setText(AnnotatedString(external))
                        copied = true
                    },
                    minWidth = 120.dp,
                )
            }
            Text(
                "这一屏不会自己弹浏览器：点上面那颗按钮才把地址交出去，之后由系统的默认浏览器接手。",
                color = cs.onBackgroundVariant,
                fontSize = 12.sp,
            )
        }

        if (then.isNotBlank()) {
            Text(
                "这条路由还带了 then=…（:app 在页面加载完、走完登录之后自动跳下一站）。" +
                    "那个动作发生在内置浏览器里 —— 本端没有那一层，跳不过去，如实说明。",
                color = cs.onBackgroundVariant,
                fontSize = 12.sp,
            )
        }

        TextButton(
            text = "回全部页面",
            onClick = { onNavigate(DesktopTarget.Routes) },
            minWidth = 120.dp,
        )
    }
}

/** 「带 then 的」跳转，该把哪一个地址交给系统浏览器（纯函数：宿主与单测同一份判据）。 */
internal fun desktopBrowserTarget(url: String, then: String): String? = when {
    then.isNotBlank() -> then
    url.isNotBlank() -> url
    else -> null
}
