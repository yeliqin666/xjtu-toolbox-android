package com.xjtu.toolbox.qrlogin

import com.xjtu.toolbox.ui.components.enterOnce
import android.Manifest
import android.content.pm.PackageManager
import com.xjtu.toolbox.platform.BackHandler
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.FullScreenOverlay
import androidx.core.content.ContextCompat
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.library.LibrarySeatQr
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private sealed class UiState {
    object NeedPermission : UiState()
    object PermissionDenied : UiState()
    object Scanning : UiState()
    data class Notifying(val scanned: String) : UiState()
    data class Confirm(val scanned: String) : UiState()
    object Authorizing : UiState()
    object Success : UiState()
    data class Error(val message: String, val canRescan: Boolean, val title: String = "无法登录") : UiState()
}

/**
 * 首页扫一扫：电脑端的统一身份认证登录码，或图书馆桌面上的座位码。
 * 座位码不在这里处理，交给 [onLibrarySeat] 跳图书馆页——那边有平面图、我的预约、换座，
 * 这里只负责认码。盖在主界面最外层的整屏浮层，和全局搜索同一套层级，见 [FullScreenOverlay]。
 */
@Composable
fun QrLoginScreen(
    sessionManager: SessionManager?,
    onBack: () -> Unit,
    onLibrarySeat: (LibrarySeatQr) -> Unit = {},
) {
    FullScreenOverlay {
        QrLoginContent(sessionManager = sessionManager, onBack = onBack, onLibrarySeat = onLibrarySeat)
    }
}

@Composable
private fun QrLoginContent(
    sessionManager: SessionManager?,
    onBack: () -> Unit,
    onLibrarySeat: (LibrarySeatQr) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loginState = com.xjtu.toolbox.auth.LocalAppLoginState.current

    /** 确认页上展示的账号。昵称优先，没有就用学号。 */
    val accountLabel = remember(loginState.cachedNickname, loginState.activeUsername) {
        val nick = loginState.cachedNickname?.takeIf { it.isNotBlank() }
        val user = loginState.activeUsername.takeIf { it.isNotBlank() }
        when {
            nick != null && user != null -> "$nick · $user"
            else -> nick ?: user
        }
    }

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
        LibrarySeatQr.parse(scanned)?.let { onLibrarySeat(it); return }
        if (!CasQrLogin.isXjtuQrLogin(scanned)) {
            state = UiState.Error("这不是登录二维码，也不是图书馆座位码", canRescan = true, title = "认不出这个码")
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

    val scanning = state is UiState.Scanning

    // 取景时小白条压在暗的取景画面上，换成浅色才看得清；离开时还原。状态栏在实色标题栏上，跟随主题不动
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.DisposableEffect(scanning) {
            val window = (view.context as? android.app.Activity)?.window
            val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
            val lightNav = controller?.isAppearanceLightNavigationBars
            if (scanning && controller != null) controller.isAppearanceLightNavigationBars = false
            onDispose {
                if (controller != null && lightNav != null) controller.isAppearanceLightNavigationBars = lightNav
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                if (scanning) Color.Black else MiuixTheme.colorScheme.background
            ),
    ) {
        // 取景画面铺到屏幕底边（小白条后面也是），顶部被实色标题栏盖住。
        if (scanning) {
            ScanningContent(onResult = ::onDecoded)
        }

        // 不要在这里再补 statusBars 让位：SmallTopAppBar 的
        // defaultWindowInsetsPadding 默认就是 true，自己会让，叠一层就是双倍留白。
        Column(Modifier.fillMaxSize()) {
            // 始终折叠：这一页没有可滚动的长内容，大标题只会占掉取景空间，
            // miuix 的 SmallTopAppBar 就是钉死在折叠态的版本。
            // 外面再套一层同色底：标题栏先让出状态栏再画自己的底色，状态栏那一段要靠这层补上，
            // 状态栏和标题栏才连成一块；标题也不会压在取景画面上看不清。
            Box(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)) {
                SmallTopAppBar(
                    title = "扫一扫",
                    color = MiuixTheme.colorScheme.background,
                    titleColor = MiuixTheme.colorScheme.onSurface,
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
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                when (val s = state) {
                    is UiState.Scanning -> Unit   // 已在底层铺好

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
                            "确认后，电脑将以此账号登录",
                            textAlign = TextAlign.Center,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        // 把"用哪个账号"摆出来。这是一次授权操作，而原来的文案只说
                        // "将用当前账号登录"——多账号的人根本无从判断当前是哪个。
                        accountLabel?.let { label ->
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                            ) {
                                Text(
                                    label,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    style = MiuixTheme.textStyles.body2,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { runAuthorize(s.scanned) },
                            modifier = Modifier.fillMaxWidth(),
                            // 默认 buttonColors 是中性灰，配 onPrimary 的白字几乎看不清，
                            // 看着还像禁用。主操作要用活力色。
                            colors = ButtonDefaults.buttonColorsPrimary(),
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
                        // 到这一步已经没有别的事可做，让它自己收起来，
                        // 不必让用户为一个纯告知的界面再点一次。按钮保留给手快的人。
                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(1600)
                            onBack()
                        }
                        // 对勾沿路径画出、外圈弹一下；文字随后依次上浮
                        com.xjtu.toolbox.ui.components.DrawCheckmark(Color(0xFF34C759), size = 72.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "登录成功",
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.enterOnce(2),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "电脑端已完成登录。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.enterOnce(3),
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text("完成", color = MiuixTheme.colorScheme.onPrimary)
                        }
                    }

                    is UiState.Error -> CenterCard {
                        StatusIcon(Icons.Filled.ErrorOutline, Color(0xFFFF3B30))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            s.title,
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
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) {
                                Text("重新扫描", color = MiuixTheme.colorScheme.onPrimary)
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(text = "返回", onClick = onBack, modifier = Modifier.fillMaxWidth())
                        } else {
                            Button(
                                onClick = onBack,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) {
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
                            colors = ButtonDefaults.buttonColorsPrimary(),
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
        // 取景框：框外压暗 + 四角标记。
        // 原来只有一块半透明白色方块，既不引导对准，在浅色画面上还几乎看不见。
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height) * 0.62f
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val corner = 20.dp.toPx()

            // 挖空的暗层：整屏一块，中间抠掉取景区（EvenOdd）。
            drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            androidx.compose.ui.geometry.Rect(left, top, left + side, top + side),
                            androidx.compose.ui.geometry.CornerRadius(corner, corner),
                        )
                    )
                    fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                },
                Color.Black.copy(alpha = 0.5f),
            )

            // 四角短线，比整圈描边更轻，也更像"取景器"。
            val armLen = side * 0.16f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            val right = left + side
            val bottom = top + side
            listOf(
                // 每个角两条线：水平、垂直
                Triple(left + corner, top, left + corner + armLen to top),
                Triple(left, top + corner, left to top + corner + armLen),
                Triple(right - corner - armLen, top, right - corner to top),
                Triple(right, top + corner, right to top + corner + armLen),
                Triple(left + corner, bottom, left + corner + armLen to bottom),
                Triple(left, bottom - corner - armLen, left to bottom - corner),
                Triple(right - corner - armLen, bottom, right - corner to bottom),
                Triple(right, bottom - corner - armLen, right to bottom - corner),
            ).forEach { (x, y, end) ->
                drawLine(
                    color = Color.White,
                    start = androidx.compose.ui.geometry.Offset(x, y),
                    end = androidx.compose.ui.geometry.Offset(end.first, end.second),
                    strokeWidth = stroke.width,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
        Text(
            "对准电脑登录码或图书馆座位码",
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun CenterCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        // 页面底色是 background，卡片再用默认色就几乎看不出边界。
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surface,
            ),
        ) {
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
