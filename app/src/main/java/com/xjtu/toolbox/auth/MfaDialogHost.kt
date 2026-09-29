package com.xjtu.toolbox.auth

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * MFA 短信验证弹窗，挂在 [SessionManager.activeMfaRequest] 上。
 *
 * 用 [WindowDialog]（自带独立 Window）而不是 Overlay*：后者只能渲染在页面自己的 Scaffold 里，
 * 从 NavHost 子页面触发的重认证弹不出框。所以只在 NavHost 外层挂**一次**，覆盖所有页面。
 *
 * 挂载期间向 [SessionManager] 登记为宿主；没有任何宿主时（Activity 已销毁、纯后台）
 * [SessionManager.verifyMfaWithUser] 直接报错，不再占着登录锁空等。
 * 验证码被拒时弹窗保留并提示，由用户重输。
 */
@Composable
fun MfaDialogHost(sessionManager: SessionManager?) {
    DisposableEffect(sessionManager) {
        val detach = sessionManager?.attachMfaHost()
        onDispose { detach?.invoke() }
    }
    val sessionMfaState = sessionManager?.activeMfaRequest?.collectAsStateWithLifecycle()
    sessionMfaState?.value?.let { req ->
        var phone by remember(req) { mutableStateOf("") }
        var codeInput by remember(req) { mutableStateOf("") }
        var sending by remember(req) { mutableStateOf(false) }
        var codeSent by remember(req) { mutableStateOf(false) }
        var verifying by remember(req) { mutableStateOf(false) }
        var err by remember(req) { mutableStateOf<String?>(null) }
        val rejections by req.rejections.collectAsStateWithLifecycle()
        LaunchedEffect(rejections) {
            if (rejections > 0) {
                verifying = false
                codeInput = ""
                err = "验证码不对，请重新输入"
            }
        }
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
        WindowDialog(
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
