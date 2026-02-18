package com.xjtu.toolbox.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * 登录类型枚举
 */
enum class LoginType(val label: String, val description: String) {
    ATTENDANCE("考勤系统", "本科生考勤查询"),
    JWXT("教务系统", "课表/考试/评教"),
    JWAPP("移动教务", "成绩查询"),
    YWTB("一网通办", "个人信息/学期"),
    LIBRARY("图书馆", "座位预约"),
    CAMPUS_CARD("校园卡", "余额/账单查询");

    /**
     * 创建对应的 Login 实例
     * @param existingClient 已有的 OkHttpClient（用于 CAS SSO 复用 TGC cookie）
     * @param visitorId 设备指纹 ID（保持一致可避免重复 MFA）
     * @param useWebVpn 是否使用 WebVPN 模式（考勤/图书馆等内部服务在校外时需要）
     */
    fun createLogin(
        existingClient: OkHttpClient? = null,
        visitorId: String? = null,
        useWebVpn: Boolean = false,
        cachedRsaKey: String? = null
    ): XJTULogin = when (this) {
        ATTENDANCE -> AttendanceLogin(existingClient, visitorId, useWebVpn)
        JWXT -> JwxtLogin(existingClient, visitorId, cachedRsaKey)
        JWAPP -> JwappLogin(existingClient, visitorId, cachedRsaKey)
        YWTB -> YwtbLogin(existingClient, visitorId, cachedRsaKey)
        LIBRARY -> LibraryLogin(existingClient, visitorId)
        CAMPUS_CARD -> CampusCardLogin(existingClient, visitorId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    loginType: LoginType = LoginType.ATTENDANCE,
    existingClient: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
    useWebVpn: Boolean = false,
    onLoginSuccess: (login: XJTULogin, username: String, password: String) -> Unit,
    onBack: () -> Unit = {}
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录 · ${loginType.label}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题
            Text(
                text = "西安交通大学",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "统一身份认证 · ${loginType.description}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 学号输入
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("学号 / 手机号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 密码输入
            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 错误信息
            errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 状态信息
            if (statusMessage.isNotEmpty()) {
                Text(
                    text = statusMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 登录按钮
            Button(
                onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        errorMessage = "请输入学号和密码"
                        return@Button
                    }

                    isLoading = true
                    errorMessage = null
                    statusMessage = "正在连接统一认证..."

                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                statusMessage = "正在初始化${loginType.label}登录..."
                                val login = loginType.createLogin(
                                    existingClient = existingClient,
                                    visitorId = visitorId,
                                    useWebVpn = useWebVpn,
                                    cachedRsaKey = cachedRsaKey
                                )

                                statusMessage = "正在验证身份..."
                                val loginResult = login.login(username, password)

                                Pair(login, loginResult)
                            }

                            val (login, loginResult) = result

                            when (loginResult.state) {
                                LoginState.SUCCESS -> {
                                    statusMessage = "登录成功！"
                                    onLoginSuccess(login, username, password)
                                }
                                LoginState.FAIL -> {
                                    errorMessage = loginResult.message
                                    statusMessage = ""
                                }
                                LoginState.REQUIRE_MFA -> {
                                    errorMessage = "需要手机验证码（MFA），暂不支持"
                                    statusMessage = ""
                                }
                                LoginState.REQUIRE_CAPTCHA -> {
                                    errorMessage = "需要验证码，暂不支持"
                                    statusMessage = ""
                                }
                                LoginState.REQUIRE_ACCOUNT_CHOICE -> {
                                    statusMessage = "选择本科生身份..."
                                    val finalResult = withContext(Dispatchers.IO) {
                                        login.login(accountType = XJTULogin.AccountType.UNDERGRADUATE)
                                    }
                                    if (finalResult.state == LoginState.SUCCESS) {
                                        statusMessage = "登录成功！"
                                        onLoginSuccess(login, username, password)
                                    } else {
                                        errorMessage = finalResult.message
                                        statusMessage = ""
                                    }
                                }
                            }
                        } catch (e: java.net.ConnectException) {
                            errorMessage = "无法连接校内网络\n请确认已连接交大校园网或 WebVPN"
                            statusMessage = ""
                        } catch (e: java.net.SocketTimeoutException) {
                            errorMessage = "连接超时\n请检查网络或确认已连接校园网"
                            statusMessage = ""
                        } catch (e: Exception) {
                            errorMessage = "网络错误: ${e.message}"
                            statusMessage = ""
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("登录中...")
                } else {
                    Text("登录")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 提示
            Text(
                text = "使用西安交通大学统一身份认证登录\n密码仅在本地加密后发送至学校服务器",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
