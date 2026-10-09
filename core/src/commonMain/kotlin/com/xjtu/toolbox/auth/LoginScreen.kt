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

/**
 * **共享的登录屏**（`docs/desktop-port-plan.md` §5.2「认证 UI 进 `:core`」）。
 *
 * ## 为什么它搬得进 `:core`，而 `:app` 的 `ProfileTab` 搬不进
 *
 * `:app` 的登录界面与「凭据存储 + 会话管家 + 校园网判定 + 账号管理」缠在一起，那几样都是宿主能力。
 * 这条缝只取「用户要看见并操作的那一件」：两个输入框、一个按钮、一个状态。所以它是**哑视图** ——
 * 输入值与状态由宿主持有（[username] / [password] / [state]），提交也只是回调。
 *
 * 于是三端各自给状态，屏只有一份：
 * - `:app` 的登录界面仍在 `ProfileTab`（Stage A 不动它：红线「Android 行为不变」）；
 * - `:desktop` 的窗口模式用它 —— 用户在这里输学号密码，宿主拿去做真 CAS 登录；
 * - serve 模式（Stage A 的 `:server`）也可以用它。
 *
 * ## ⚠️ 红线：这一屏**不显示**学号 / 姓名
 *
 * 两条都守：
 * 1. 输入框**永不预填**已存凭据 —— 宿主有凭据时应当**直接进主界面**（静默恢复），
 *    而不是把记忆下来的学号摆在登录页上（那会让截图、投屏、肩窥都带上个人数据）；
 * 2. 这里出现的错文案来自 [LoginUiState.Failed.message]，宿主传进来的是
 *    `FriendlyError.of(...)` 的中文短句（如「账号或密码无效」），**不是**异常堆栈，也不带学号。
 *    `renderScreens` 出的那张证据图因此也是空输入框。
 *
 * @param title 大标题（桌面端给「西交工具箱」）
 * @param subtitle 副标题（说明这一屏在干什么）
 * @param username / [onUsernameChange] 账号输入（学号 / 手机号）。**不预填**，见类 KDoc。
 * @param password / [onPasswordChange] 密码输入。
 * @param state 登录中 / 失败 [LoginUiState]，由宿主驱动；屏不自己发起登录。
 * @param onSubmit 用户按下「登录」（也可能是键盘动作）。屏不关心它是不是协程。
 * @param footer 底部说明（例如「凭据只存在这台机器上」）。null = 不画。
 */
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

/**
 * 登录屏的状态。宿主驱动（[LoginScreen] 只负责画）。
 *
 * 只有三态：**正在登**、**登失败**、**还没开始**。没有「成功」——成功了宿主就直接换屏，
 * 一个「已登录的登录页」是没有意义的中间态。
 */
sealed interface LoginUiState {
    /** 尚未提交（首次进入、或用户改过输入）。 */
    data object Idle : LoginUiState

    /** 正在提交（含 CAS 往返、可能的短信二验等待）。 */
    data object Submitting : LoginUiState

    /** 上一次提交失败。[message] 是给用户看的中文短句，**不得**含学号/姓名（见 [LoginScreen] 的红线）。 */
    data class Failed(val message: String) : LoginUiState
}

/**
 * **共享的短信验证码弹窗**（两步验证 / MFA）。
 *
 * ## 它为什么也进 `:core`
 *
 * `:app` 有一份一模一样的（`auth/MfaDialogHost.kt`），挂在 `SessionManager.activeMfaRequest` 上。
 * 桌面端有窗口 ⇒ 也能弹（设计文档 §5.2：桌面**有窗口**，所以短信二验、二维码都能做，不是
 * 「服务端无 UI」那种难题）。但 `:core` **不认识** `SessionManager` / `MfaRequest` / `MFAContext`
 * —— 那是 `:data` 的类型，而 `:data` 依赖 `:core`（不能反向依赖）。所以照老办法切缝：
 * 这里只画，**状态由宿主喂**（谁持有 `MfaRequest`，谁负责取手机号、`submit`、`cancel`、数拒绝次数）。
 *
 * ## 与 `:app` 那份的关系
 *
 * 不合并。合并就得把 `MfaRequest` 提到 `:core`（`:data` 的会话内核正是它唯一的实现），
 * 那是为了少写一个 40 行的哑视图去动会话内核的公开面 —— 不划算，也违反「Android 行为不变」
 * 这条硬约束（`MfaDialogHost` 的挂载点/卸载点、`attachMfaHost` 的计数都在 `:app` 的 Activity 生命周期上）。
 * 两边**文案与交互**保持一致：标题「两步验证」、summary 带站点名、验证码被拒时留在窗里重输。
 *
 * @param siteName 正在登录的站点名（「图书馆」），出现在 summary 里
 * @param phone 验证手机号（宿主取到后给；null = 还在取，弹窗显示「正在获取手机号…」）
 * @param error 上一次提交的错误（验证码不对 / 提交失败）；null = 不画
 * @param onSubmit 用户点了「验证并登录」。屏只做长度校验（6 位），对错由服务端说了算。
 * @param onCancel 用户取消（点窗外 / 取消按钮）。**必须**调 [SessionManager]（`:app` 侧）或
 *   对应的 cancel —— 否则登录锁会一直挂到超时，同一边其余站点都登不上。
 */
@Composable
fun MfaCodeDialog(
    siteName: String,
    phone: String?,
    error: String?,
    /**
     * 第几次尝试。宿主每收到一次「服务端拒了这个码」就加一 —— 屏据此**清空已输入的验证码**
     *（`:app` 那份就是 `rejections` 一变就 `codeInput = ""`，对得上）。
     */
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
                // 只收 6 位数字，且改动输入时清掉上一次的错（与 :app 那份的 take(6) 同一口径）
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
