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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * serve 模式的会话屏（契约 §5.1）：登录 / 短信二验 / 登出。
 *
 * 为什么要专门一屏：serve 模式的**取数全部要求「有会话 + 有令牌」**
 *（`/api/status` 是唯一例外）。页面上没有别的入口能建立这个会话 —— campus-api 那套是
 * 「凭据托管在反代进程里、回环零鉴权」，而这里是浏览器自己登进 `:server`。
 *
 * ## 三条交互口径（都照 §5.1）
 *
 * 1. **令牌只在内存里**（见 [WebServeSession] 的 KDoc）：`GET /api/session` 那一发才需要它，
 *    它顺手把 cookie 换来；贴一次之后同一浏览器会话内不用再贴（cookie 是会话 cookie）；
 * 2. **登录那一发会挂起**（最长 150 秒，等服务端那条 CAS + 短信流程跑完）⇒ 它跑在 `async` 里，
 *    同时**并发轮询** `GET /api/session/mfa` 拿挂起的那条询问（轮询间隔见 [MFA_POLL_INTERVAL_MS]）；
 * 3. **弹窗里的三格如实显示** `siteName` / `rejections` / `attemptsLeft`；绑定手机号契约**不投影**
 *    （§5.1 的 TODO ①）⇒ 那句「验证码已发送到 138\*\*\*\*0000」写不出来，如实不写。
 *
 * ⚠️ 非 serve 模式（同源反代 campus-api）**不该看这一屏**：那边页面上没有「登录」这回事，
 * 底栏也不会长出这一格（见 `ToolboxWebApp` 的 `WebBottomBar`）。
 */
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
    // 这一发同时也是「令牌换 cookie」的那一步（§5.1 第 1 条）。
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
            Text("serve 会话", color = colors.onSurface, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(text = "回课表", onClick = onBack, minWidth = 84.dp)
        }
        Text(
            "这一屏是 serve 模式（`:server` 托管本页）的登录口：会话在 `:server` 进程里，" +
                "浏览器不带任何凭据地登进去。非 serve 模式（同源反代 campus-api）用不到它。",
            color = colors.onBackgroundVariant,
            fontSize = 11.sp,
        )

        // ── 访问令牌：只在内存里，浏览器会话内贴一次 ──
        if (!session.hasToken && authenticated != true) {
            Text(
                "访问令牌：`:server` 启动横幅里打印的那一行（Authorization: Bearer <令牌>）。" +
                    "它只存在这个页面的内存里，不写 localStorage —— 刷新后若 cookie 还在就不必重贴。",
                color = colors.onBackgroundVariant,
                fontSize = 11.sp,
            )
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
            ) { Text("用这枚令牌问会话") }
        }

        when (authenticated) {
            null -> Text("正在问 :server 有没有会话…", color = colors.onSurface, fontSize = 13.sp)
            true -> Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已登录（会话在 :server 进程里）", color = colors.onSurface, fontSize = 14.sp)
                    Text(
                        "取数（课表 / 成绩 / 黄页 / 图书馆 / 校园卡 …）现在都走 `:server`。" +
                            "登出会删掉 :server 上的凭据、cookie 与站点快照。",
                        color = colors.onBackgroundVariant,
                        fontSize = 11.sp,
                    )
                    TextButton(text = "登出", onClick = { doLogout() }, enabled = !busy, minWidth = 96.dp)
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
                    // 这一发会一直开着等短信验证码（最长 150 秒）⇒ 按钮的文案要说清「可能挂一会儿」
                    Button(
                        onClick = { doLogin() },
                        enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "正在登录…（挂短信二验时最长 150 秒）" else "登录") }
                }
            }
        }

        error?.let {
            Text(it, color = colors.error, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
    }

    // ── 短信二验：挂起的那条询问（轮询来的快照，见 §5.1 第 3 条）──
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
                    "验证码是发给 :server 那条登录流程的，结论从下一次轮询（被拒次数涨没涨）或" +
                        "那发登录请求的响应读 —— 所以这里交完可能还显示一会儿「等待」。" +
                        "（契约不投影绑定手机号，所以这里不写「已发送到 138****」。）",
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
                    ) { Text("提交") }
                    TextButton(
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

/**
 * 轮询 `GET /api/session/mfa` 的间隔。
 *
 * 契约 §5.1 只写了「MFA 是**轮询**」（不是 SSE），**没有定间隔** —— 2 秒是这里取的：一次挂起最长
 * 150 秒 ⇒ 最多 75 发请求，对一个人的浏览器与服务端都不算什么，而弹窗上的「被拒次数」也够及时。
 *
 * TODO（与 `:server` 一起定）：把这个数写进契约 §5.1，并考虑退避（例如每 10 秒后拉长到 5 秒）——
 * 现在两边各有一个「凭感觉」的值就是将来漂移的起点。
 */
private const val MFA_POLL_INTERVAL_MS = 2_000L
