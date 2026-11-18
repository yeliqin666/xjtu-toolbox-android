package com.xjtu.toolbox.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xjtu.toolbox.ui.components.TextLink
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** serve 模式的会话屏：登录 / 短信二验 / 登出。 */
@Composable
fun WebServeSessionScreen(session: WebServeSession, onBack: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()

    /** `null` = 还没问过 `:server`。 */
    var authenticated by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var mfa by remember { mutableStateOf<MfaState?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }

    suspend fun refresh() {
        session.sessionState().fold(
            onSuccess = { authenticated = it; error = null },
            onFailure = { authenticated = false; error = it.message ?: it.toString() },
        )
    }

    // 进屏先问一次「有没有会话」：带 cookie 的浏览器这里就答 true（冷启动从落盘凭据静默恢复）。
    // 这一发同时也是「令牌换 cookie」的那一步。
    LaunchedEffect(Unit) { refresh() }

    fun doLogin() {
        error = null
        mfa = null
        busy = true
        scope.launch {
            // ⚠️ 必须并发：登录那一发在等短信验证码时会挂 150 秒，串行等它就拿不到询问了
            val login = async { session.login(username.trim(), password) }
            while (!login.isCompleted) {
                delay(MFA_POLL_INTERVAL_MS)
                session.mfa().onSuccess { if (it.pending) mfa = it }
            }
            login.await().fold(
                onSuccess = { authenticated = true; mfa = null },
                onFailure = { authenticated = false; mfa = null; error = it.message ?: it.toString() },
            )
            busy = false
        }
    }

    fun doLogout() {
        error = null
        busy = true
        scope.launch {
            session.logout().fold(
                onSuccess = { authenticated = false },
                onFailure = { error = it.message ?: it.toString() },
            )
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row {
            Text("登录", color = colors.onSurface, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextLink(text = "返回", onClick = onBack, chevron = true)
        }

        // 访问令牌是 `:server` 启动横幅里打印的那一行；只存页面内存，不写 localStorage
        if (!session.hasToken && authenticated != true) {
            Text("请输入服务器的访问令牌", color = colors.onBackgroundVariant, fontSize = 12.sp)
            TextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = "访问令牌",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { session.setToken(tokenInput); scope.launch { refresh() } },
                enabled = tokenInput.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text("确定", color = colors.onPrimary) }
        }

        when (authenticated) {
            null -> Text("正在检查登录状态…", color = colors.onSurface, fontSize = 13.sp)
            true -> Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已登录", color = colors.onSurface, fontSize = 14.sp)
                    TextLink(text = "退出登录", onClick = { doLogout() }, enabled = !busy, color = colors.error)
                }
            }
            false -> Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = username,
                        onValueChange = { username = it },
                        label = "学号",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "密码",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 这一发会一直开着等短信验证码（最长 150 秒）
                    Button(
                        onClick = { doLogin() },
                        enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            if (busy) "正在登录…" else "登录",
                            color = colors.onPrimary,
                        )
                    }
                }
            }
        }

        error?.let {
            Text(it, color = colors.error, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
    }

    // ── 短信二验：挂起的那条询问（轮询来的快照）──
    val pending = mfa?.takeIf { it.pending }
    if (pending != null) {
        OverlayDialog(
            show = true,
            title = "短信验证码",
            summary = mfaSummary(pending),
            onDismissRequest = { /* 忙时不关：这一发登录还开着，关掉弹窗只会让人以为没在登录 */ },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "请输入学校发送的短信验证码，验证可能需要几秒。",
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 11.sp,
                )
                TextField(
                    value = code,
                    onValueChange = { code = it },
                    label = "验证码",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { scope.launch { session.submitMfa(code.trim()).onSuccess { mfa = it } } },
                        enabled = code.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text("提交", color = MiuixTheme.colorScheme.onPrimary) }
                    TextLink(
                        text = "取消这次登录",
                        onClick = { scope.launch { session.cancelMfa().onSuccess { mfa = it } } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 弹窗那几句：站点名 / 被拒次数 / 还剩几次机会（有哪个写哪个，没来的字段不编）。 */
private fun mfaSummary(state: MfaState): String {
    val site = state.siteName?.let { "「$it」要求短信二次验证" } ?: "需要短信二次验证"
    val attempts = state.attemptsLeft?.let { "还能试 $it 次" }
    val rejections = state.rejections?.takeIf { it > 0 }?.let { "已被拒 $it 次" }
    return listOfNotNull(site, rejections, attempts).joinToString("；")
}

/** 轮询 `GET /api/session/mfa` 的间隔。 */
private const val MFA_POLL_INTERVAL_MS = 2_000L
