package com.xjtu.toolbox.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MFA 短信验证弹窗，挂在 [SessionManager.activeMfaRequest] 上。
 *
 * miuix 的 Overlay* 组件要注册进 `LocalDialogStates`，而这个 CompositionLocal 只有
 * Scaffold 自己提供——写在 Scaffold 外部拿到的是静态默认空列表，弹窗静默不显示
 * （AccountManagerScreen.kt 里对同一约定有过记录）。之前这个弹窗只塞进了 `MainScreen`
 * 一个页面，账号管理页是 NavHost 里跟 MainScreen 平级的另一个目的地，MainScreen 一换出
 * 弹窗就没地方渲染——首次登录卡在账号管理页时 MFA 短信发了，弹窗却弹不出来，
 * 正是这个原因。所以这个函数要在**每个会触发登录的页面自己的 Scaffold content 里**
 * 调用一次，而不是只挂在某一个页面上。
 */
@Composable
fun MfaDialogHost(sessionManager: SessionManager?) {
    val sessionMfaState = sessionManager?.activeMfaRequest?.collectAsState()
    sessionMfaState?.value?.let { req ->
        var phone by remember(req) { mutableStateOf("") }
        var codeInput by remember(req) { mutableStateOf("") }
        var sending by remember(req) { mutableStateOf(false) }
        var codeSent by remember(req) { mutableStateOf(false) }
        var verifying by remember(req) { mutableStateOf(false) }
        var err by remember(req) { mutableStateOf<String?>(null) }
        LaunchedEffect(req) {
            sending = true
            try {
                phone = withContext(Dispatchers.IO) { req.mfaContext.getPhoneNumber() }
                codeSent = true
            } catch (e: Exception) {
                err = "获取验证手机号失败：${e.message}"
            }
            sending = false
        }
        BackHandler(enabled = true) { req.cancel() }
        OverlayDialog(
            show = true,
            title = "两步验证",
            summary = "登录「${req.siteName}」需要短信验证码",
            onDismissRequest = { req.cancel() }
        ) {
            Column(
                Modifier.fillMaxWidth().imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (phone.isNotEmpty()) "验证码已发送至 $phone" else "正在获取手机号…",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (codeSent) {
                    TextField(
                        value = codeInput,
                        onValueChange = { codeInput = it.take(6); err = null },
                        label = "6位验证码",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                err?.let {
                    Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                }
                if (codeSent) {
                    TextButton(
                        text = if (verifying) "验证中…" else "验证并登录",
                        onClick = {
                            if (codeInput.length != 6) { err = "请输入6位验证码"; return@TextButton }
                            verifying = true; err = null
                            if (!req.submit(codeInput)) {
                                err = "提交失败"
                                verifying = false
                            }
                        },
                        enabled = !verifying,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                } else if (sending) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("准备中…", style = MiuixTheme.textStyles.body1)
                    }
                }
            }
        }
    }
}
