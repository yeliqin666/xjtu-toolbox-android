package com.xjtu.toolbox.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 共享的登录屏。 */
@Composable
fun LoginScreen(
    title: String,
    subtitle: String,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    state: LoginUiState,
    onSubmit: () -> Unit,
    footer: String? = null,
) {
    val cs = MiuixTheme.colorScheme
    val busy = state is LoginUiState.Submitting
    // 空输入按不住按钮只是省一次必然失败的往返；真正的判据在宿主（它还要判断 service 的冷却/熔断）
    val canSubmit = !busy && username.isNotBlank() && password.isNotEmpty()

    Box(
        modifier = Modifier.fillMaxSize().background(cs.background).imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 竖屏手机上占满、桌面窗口里限宽 —— 桌面窗口比手机宽得多，不限宽输入框会拉成一条线
                .widthIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = cs.onBackground, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = cs.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))

            TextField(
                value = username,
                onValueChange = onUsernameChange,
                label = "学号 / 手机号",
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = password,
                onValueChange = onPasswordChange,
                label = "密码",
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            (state as? LoginUiState.Failed)?.let { failed ->
                Text(
                    failed.message,
                    color = cs.error,
                    style = MiuixTheme.textStyles.footnote1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    // 文案要说明「可能正在等短信验证码」：MFA 是弹窗，但用户可能没立刻看到
                    Text("正在登录…", color = cs.onPrimary)
                } else {
                    Text("登录", color = cs.onPrimary)
                }
            }

            footer?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    color = cs.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 登录屏的状态。 */
sealed interface LoginUiState {
    /** 尚未提交（首次进入、或用户改过输入）。 */
    data object Idle : LoginUiState

    /** 正在提交（含 CAS 往返、可能的短信二验等待）。 */
    data object Submitting : LoginUiState

    /** 上一次提交失败。[message] 是给用户看的中文短句，不得含学号/姓名。 */
    data class Failed(val message: String) : LoginUiState
}

/** 共享的短信验证码弹窗（两步验证 / MFA）。 */
@Composable
fun MfaCodeDialog(
    siteName: String,
    phone: String?,
    error: String?,
    /** 第几次尝试。 */
    attempt: Int = 0,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // 键里带 attempt：被拒一次就换一把 key，输入框自然回到空（用户不用自己删掉旧码）
    var code by remember(siteName, attempt) { mutableStateOf("") }
    var localError by remember(siteName, attempt) { mutableStateOf<String?>(null) }

    WindowDialog(
        show = true,
        title = "两步验证",
        summary = "登录「$siteName」需要短信验证码",
        onDismissRequest = onCancel,
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Text(
                phone?.let { "验证码已发送至 $it" } ?: "正在获取手机号…",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body1,
            )
            Spacer(Modifier.height(12.dp))
            TextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(6); localError = null },
                label = "6 位验证码",
                singleLine = true,
                enabled = phone != null,
                modifier = Modifier.fillMaxWidth(),
            )
            (localError ?: error)?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
            }
            Spacer(Modifier.height(16.dp))
            Row {
                TextButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "验证并登录",
                    onClick = {
                        if (code.length != 6) localError = "请输入 6 位验证码" else onSubmit(code)
                    },
                    enabled = phone != null && code.length == 6,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
