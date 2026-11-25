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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.browser.shareableUrl
import com.xjtu.toolbox.webvpn.WebVpnCodec
import com.xjtu.toolbox.ui.components.TextLink
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
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
        Text("在浏览器中打开", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        if (url.isBlank()) {
            Text("链接为空", color = cs.onBackgroundVariant, fontSize = 13.sp)
        } else {
            // WebVPN 网关地址交给系统浏览器没有会话，给的是还原后的原始地址
            Text(external, color = cs.onBackgroundVariant, fontSize = 13.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { openInBrowser(external) },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("在系统浏览器打开", color = cs.onPrimary) }
                TextLink(
                    text = if (copied) "已复制" else "复制链接",
                    onClick = {
                        clipboard.setText(AnnotatedString(external))
                        copied = true
                    },
                    enabled = !copied,
                )
            }
            // then 要内置浏览器走完登录后自动跳转，桌面做不到
            if (then.isNotBlank()) {
                Text("在浏览器里登录后，再前往目标页面。", color = cs.onBackgroundVariant, fontSize = 12.sp)
            }
        }

        TextLink(text = "返回", onClick = { onNavigate(DesktopTarget.App(com.xjtu.toolbox.nav.AppRoute.Main)) })
    }
}

/**
 * 「带 then 的」跳转，该把哪一个地址交给系统浏览器（纯函数：宿主与单测同一份判据）。
 *
 * **只交第一跳 `url`**：`then` 的约定是「先加载 url、走完登录，再自动跳到 then」——那第二跳只有内置浏览器
 * 收得到回调（`:app` 的 `isAuthHop` 那条路）才成立。桌面把地址交给系统浏览器之后什么都没有了，若直开
 * `then` 等于跳过登录直接敲支付页。所以只给第一跳，屏上提示用户登录后自己前往。
 */
internal fun desktopBrowserTarget(url: String): String? = url.takeIf { it.isNotBlank() }
