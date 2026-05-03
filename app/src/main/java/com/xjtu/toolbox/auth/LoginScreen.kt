package com.xjtu.toolbox.auth

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperBottomSheet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
    JWXT("教务系统", "日程/考试/评教"),
    JWAPP("移动教务", "成绩查询"),
    YWTB("一网通办", "个人信息/学期"),
    LIBRARY("图书馆", "座位预约"),
    CAMPUS_CARD("校园卡", "余额/账单查询"),
    DZPZ("电子打印证", "成绩单下载"),
    VENUE("体育场馆", "运动场地预订"),
    CLASS("课程平台", "课程回放 · TronClass"),
    LMS("思源学堂", "课程 · 作业 · 回放"),
    JIAOCAI("教材中心", "在线阅览 · PDF 下载");

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
        DZPZ -> DzpzLogin(existingClient, visitorId, cachedRsaKey)
        VENUE -> VenueLogin(existingClient, visitorId, cachedRsaKey)
        CLASS -> com.xjtu.toolbox.classreplay.ClassLogin(existingClient, visitorId, cachedRsaKey)
        LMS -> com.xjtu.toolbox.lms.LmsLogin(existingClient, visitorId, cachedRsaKey)
        JIAOCAI -> com.xjtu.toolbox.jiaocai.JiaocaiLogin(existingClient, visitorId, cachedRsaKey)
    }
}

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
    // MFA 验证码状态
    var mfaLogin by remember { mutableStateOf<XJTULogin?>(null) }
    var showMfaSheet by remember { mutableStateOf(false) }
    var mfaPhone by remember { mutableStateOf("") }
    var mfaCode by remember { mutableStateOf("") }
    var mfaError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "登录 · ${loginType.label}",
                color = MiuixTheme.colorScheme.surfaceVariant,
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
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.primary
            )
            Text(
                text = "统一身份认证 · ${loginType.description}",
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 学号输入
            TextField(
                value = username,
                onValueChange = { username = it },
                label = "学号 / 手机号",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 密码输入
            var passwordVisible by remember { mutableStateOf(false) }
            TextField(
                value = password,
                onValueChange = { password = it },
                label = "密码",
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
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.footnote1
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 状态信息
            if (statusMessage.isNotEmpty()) {
                Text(
                    text = statusMessage,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1
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
                                    val mfa = loginResult.mfaContext
                                    if (mfa == null) {
                                        errorMessage = "MFA 上下文缺失"
                                        statusMessage = ""
                                    } else {
                                        mfaLogin = login
                                        statusMessage = "正在获取手机号..."
                                        try {
                                            val phone = withContext(Dispatchers.IO) { mfa.getPhoneNumber() }
                                            withContext(Dispatchers.IO) { mfa.sendVerifyCode() }
                                            mfaPhone = phone
                                            mfaCode = ""
                                            mfaError = null
                                            showMfaSheet = true
                                            statusMessage = ""
                                        } catch (e: Exception) {
                                            errorMessage = "发送验证码失败: ${e.message}"
                                            statusMessage = ""
                                        }
                                    }
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
                        size = 20.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = MiuixTheme.colorScheme.onPrimary),
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
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }

        // MFA 验证码 BottomSheet
        if (showMfaSheet) {
            SuperBottomSheet(
                show = showMfaSheet,
                title = "手机验证",
                onDismissRequest = {
                    showMfaSheet = false
                    isLoading = false
                }
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "验证码已发送至 $mfaPhone",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TextField(
                        value = mfaCode,
                        onValueChange = { mfaCode = it.take(6) },
                        label = "6位验证码",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    mfaError?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = it,
                            color = MiuixTheme.colorScheme.error,
                            style = MiuixTheme.textStyles.footnote1
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (mfaCode.length != 6) {
                                mfaError = "请输入6位验证码"
                                return@Button
                            }
                            val login = mfaLogin ?: return@Button
                            val mfa = login.mfaContext ?: return@Button
                            isLoading = true
                            mfaError = null
                            statusMessage = "验证中..."
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { mfa.verifyCode(mfaCode) }
                                    statusMessage = "验证通过，完成登录..."
                                    val finalResult = withContext(Dispatchers.IO) {
                                        login.login(username, password)
                                    }
                                    when (finalResult.state) {
                                        LoginState.SUCCESS -> {
                                            showMfaSheet = false
                                            isLoading = false
                                            statusMessage = "登录成功！"
                                            onLoginSuccess(login, username, password)
                                        }
                                        LoginState.REQUIRE_MFA -> {
                                            val newMfa = finalResult.mfaContext
                                            if (newMfa != null) {
                                                val phone = withContext(Dispatchers.IO) { newMfa.getPhoneNumber() }
                                                withContext(Dispatchers.IO) { newMfa.sendVerifyCode() }
                                                mfaPhone = phone
                                                mfaCode = ""
                                                mfaError = null
                                            } else {
                                                mfaError = "MFA 上下文丢失"
                                            }
                                            isLoading = false
                                            statusMessage = ""
                                        }
                                        else -> {
                                            mfaError = finalResult.message
                                            isLoading = false
                                            statusMessage = ""
                                        }
                                    }
                                } catch (e: Exception) {
                                    mfaError = "验证失败: ${e.message}"
                                    mfaCode = ""
                                    isLoading = false
                                    statusMessage = ""
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = mfaCode.length == 6 && !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                size = 20.dp,
                                colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = MiuixTheme.colorScheme.onPrimary),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("验证中...")
                        } else {
                            Text("验证")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val mfaCtx = mfaLogin?.mfaContext ?: return@TextButton
                            mfaError = null
                            mfaCode = ""
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { mfaCtx.sendVerifyCode() }
                                    statusMessage = "验证码已重新发送"
                                } catch (e: Exception) {
                                    mfaError = "重新发送失败: ${e.message}"
                                }
                            }
                        }
                    ) {
                        Text("重新发送验证码")
                    }
                }
            }
        }
    }
}