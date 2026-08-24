package com.xjtu.toolbox.qrlogin

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.xjtu.toolbox.auth.SessionManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

private sealed class UiState {
    object NeedPermission : UiState()
    object PermissionDenied : UiState()
    object Scanning : UiState()
    data class Notifying(val scanned: String) : UiState()
    data class Confirm(val scanned: String) : UiState()
    object Authorizing : UiState()
    object Success : UiState()
    data class Error(val message: String, val canRescan: Boolean) : UiState()
}

/**
 * 扫码登录。独立 Dialog，盖过首页大标题和悬浮底栏（与全局搜索同一套层级）。
 */
@Composable
fun QrLoginScreen(
    sessionManager: SessionManager?,
    onBack: () -> Unit,
) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
        ),
    ) {
        QrLoginContent(sessionManager = sessionManager, onBack = onBack)
    }
}

@Composable
private fun QrLoginContent(
    sessionManager: SessionManager?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    var state by remember {
        mutableStateOf<UiState>(if (hasCameraPermission()) UiState.Scanning else UiState.NeedPermission)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        state = if (granted) UiState.Scanning else UiState.PermissionDenied
    }

    LaunchedEffect(Unit) {
        if (state is UiState.NeedPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun toError(r: CasQrLogin.Result): UiState = when (r) {
        is CasQrLogin.Result.Invalid -> UiState.Error(r.reason, canRescan = true)
        is CasQrLogin.Result.Expired -> UiState.Error(r.detail, canRescan = true)
        is CasQrLogin.Result.Failed -> UiState.Error(r.detail, canRescan = true)
        else -> UiState.Error("操作未完成", canRescan = true)
    }

    fun onDecoded(scanned: String) {
        if (!CasQrLogin.isXjtuQrLogin(scanned)) {
            state = UiState.Error("这不是西安交大的扫码登录二维码", canRescan = true)
            return
        }
        val mgr = sessionManager
        if (mgr == null) {
            state = UiState.Error("尚未登录账号，请先在「我的」中登录", canRescan = false)
            return
        }
        state = UiState.Notifying(scanned)
        scope.launch {
            state = when (val r = CasQrLogin.markScanned(mgr, scanned)) {
                is CasQrLogin.Result.Scanned -> UiState.Confirm(scanned)
                else -> toError(r)
            }
        }
    }

    fun runAuthorize(scanned: String) {
        val mgr = sessionManager
        if (mgr == null) {
            state = UiState.Error("尚未登录账号，请先在「我的」中登录", canRescan = false)
            return
        }
        state = UiState.Authorizing
        scope.launch {
            state = when (val r = CasQrLogin.confirmAuth(mgr, scanned)) {
                is CasQrLogin.Result.Success -> UiState.Success
                else -> toError(r)
            }
        }
    }

    BackHandler(onBack = onBack)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MiuixTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = "扫码登录",
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (val s = state) {
                    is UiState.Scanning -> ScanningContent(onResult = ::onDecoded)

                    is UiState.Notifying -> CenterCard {
                        InfiniteProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("已扫到，正在通知电脑…", color = MiuixTheme.colorScheme.onSurface)
                    }

                    is UiState.Confirm -> CenterCard {
                        StatusIcon(Icons.Filled.QrCodeScanner, MiuixTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "确认登录",
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "电脑上应已显示「请在手机上确认」。将用当前账号登录，请确认是你本人操作。",
                            textAlign = TextAlign.Center,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { runAuthorize(s.scanned) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("确认登录", color = MiuixTheme.colorScheme.onPrimary)
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            text = "取消",
                            onClick = { state = UiState.Scanning },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is UiState.Authorizing -> CenterCard {
                        InfiniteProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("正在授权登录…", color = MiuixTheme.colorScheme.onSurface)
                    }

                    is UiState.Success -> CenterCard {
                        StatusIcon(Icons.Filled.CheckCircle, Color(0xFF34C759))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "登录成功",
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "电脑端已完成登录。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                            Text("完成", color = MiuixTheme.colorScheme.onPrimary)
                        }
                    }

                    is UiState.Error -> CenterCard {
                        StatusIcon(Icons.Filled.ErrorOutline, Color(0xFFFF3B30))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "无法登录",
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            s.message,
                            textAlign = TextAlign.Center,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(20.dp))
                        if (s.canRescan) {
                            Button(
                                onClick = { state = UiState.Scanning },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("重新扫描", color = MiuixTheme.colorScheme.onPrimary)
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(text = "返回", onClick = onBack, modifier = Modifier.fillMaxWidth())
                        } else {
                            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                                Text("返回", color = MiuixTheme.colorScheme.onPrimary)
                            }
                        }
                    }

                    is UiState.PermissionDenied -> CenterCard {
                        Text(
                            "需要相机权限才能扫码",
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "请在系统设置中授予本应用相机权限后重试。",
                            textAlign = TextAlign.Center,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("重新授权", color = MiuixTheme.colorScheme.onPrimary)
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(text = "返回", onClick = onBack, modifier = Modifier.fillMaxWidth())
                    }

                    is UiState.NeedPermission -> { }
                }
            }
        }
    }
}

@Composable
private fun ScanningContent(onResult: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        QrScannerView(
            modifier = Modifier.fillMaxSize(),
            onResult = onResult,
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(240.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Text(
            "将电脑上的登录二维码放入框内",
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun CenterCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = content,
            )
        }
    }
}

@Composable
private fun StatusIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Icon(
        icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(56.dp),
    )
}
