package com.xjtu.toolbox.game.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xjtu.toolbox.qrlogin.QrScannerView
import com.xjtu.toolbox.util.QrBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 给 NavHost 用的便利函数：把当前 Context 装进 [AppOnlineLobby] 并记住它。
 * 棋盘屏（在 :core）只认 [OnlineLobby]，拿不到 Context。
 */
@Composable
fun rememberAppOnlineLobby(): OnlineLobby {
    val context = LocalContext.current
    return remember(context) { AppOnlineLobby(context) }
}

/**
 * 联机入口的 **Android 实现**：一个蓝牙 [OnlineLobbyHost] + 原样的 Android 大厅界面。
 *
 * 这一层为什么留在 `:app`：大厅 UI 要用运行时权限弹窗、`QrScannerView`（Android View）、
 * 以及 `android.graphics.Bitmap` 画的二维码 —— 三样都是 Android 专属。协议流程
 * （[OnlineLobbyState]）与棋盘屏都在 `:core`，只认 [OnlineLobby] 这个接口。
 *
 * Web 端传 `null`：浏览器没有 BLE，联机入口整个不出现（不是做一个假大厅）。
 */
class AppOnlineLobby(private val context: Context) : OnlineLobby {
    override val host: OnlineLobbyHost = BleOnlineLobbyHost(context)

    @Composable
    override fun Content(
        state: OnlineLobbyState,
        kind: GameKind,
        ruleParam: String?,
        onSessionReady: (session: OnlineGameSession, amHost: Boolean, hostFirst: Boolean, ruleParam: String?) -> Unit,
        onCancel: () -> Unit,
        hostOptions: (@Composable () -> Unit)?,
    ) {
        OnlineLobbyContent(
            state = state,
            kind = kind,
            ruleParam = ruleParam,
            onSessionReady = onSessionReady,
            onCancel = onCancel,
            hostOptions = hostOptions,
        )
    }
}

/**
 * 三种棋共用的联机入口 UI：选创建/加入房间、房主显示二维码候人、加入方扫码连接。
 * 连上之后把 [OnlineGameSession] 交给调用方，UI 本身不认识棋盘——接下来怎么把
 * 对方的着法应用到棋盘上，是每个棋各自的事。
 *
 * 只走蓝牙：校园网开了 AP 隔离，局域网直连实测连不上。
 *
 * **流程**（开房、等人、连接、失败重试）在 :core 的 [OnlineLobbyState] 里，这里只管界面
 * 与三样 Android 专属的交互（权限、扫码控件、二维码图片）。
 */
@Composable
fun OnlineLobbyContent(
    state: OnlineLobbyState,
    kind: GameKind,
    /** 房主开房时的规则参数（围棋的路数等），加入方从房主的 hello 里拿，不看这个。 */
    ruleParam: String?,
    onSessionReady: (session: OnlineGameSession, amHost: Boolean, hostFirst: Boolean, ruleParam: String?) -> Unit,
    onCancel: () -> Unit,
    /** 房主开房前可选的规则（比如围棋路数），画在「创建房间」上面。 */
    hostOptions: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current

    // 蓝牙三件套权限一起要，只在点了「创建 / 加入」之后才申请，单机、同屏双人碰不到。
    // **授权结果回来之后**才真正开房 / 进扫码，免得开房那一刻权限还没给。
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        afterPermission?.invoke()
        afterPermission = null
    }

    fun withBlePermission(action: () -> Unit) {
        if (OnlinePermissions.hasAllBlePermissions(context)) {
            action()
        } else {
            afterPermission = action
            blePermissionLauncher.launch(OnlinePermissions.BLE_PERMISSIONS)
        }
    }

    // 扫码加入要用相机，跟扫码登录/匹配交友那两处一样就地要权限，不跳系统设置再跳回来。
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { cameraGranted = it }

    fun startHost() {
        state.startHost(kind, ruleParam) { session, hostFirst, rule ->
            onSessionReady(session, true, hostFirst, rule)
        }
    }

    fun startJoinWith(scanned: String) {
        state.startJoinWith(scanned, kind) { session, hostFirst, rule ->
            onSessionReady(session, false, hostFirst, rule)
        }
    }

    val cancel = { state.reset(); onCancel() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state.stage) {
            LobbyStage.CHOOSE -> {
                Text("联机对战", style = MiuixTheme.textStyles.title3)
                Spacer12()
                Text(
                    "跟朋友各拿一台手机，蓝牙直连对弈，不用联网，也不改你手机的网络设置。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                Spacer12()
                // 开房的人定规则：先后手、以及各棋自己的参数（围棋路数）
                LobbyOptionRow("先后手") {
                    LobbyChip("我先走", state.hostFirst) { state.hostFirst = true }
                    LobbyChip("对方先走", !state.hostFirst) { state.hostFirst = false }
                }
                hostOptions?.let {
                    Box(Modifier.height(8.dp))
                    it()
                }
                Spacer12()
                Button(onClick = { withBlePermission(::startHost) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text("创建房间（我显示二维码）", color = MiuixTheme.colorScheme.onPrimary)
                }
                Spacer(8.dp)
                Button(onClick = { withBlePermission { state.beginScanning() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("加入房间（我扫对方的码）")
                }
                Spacer(8.dp)
                Text(
                    "规则由开房的一方决定，加入方自动跟随。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }

            LobbyStage.HOST_WAITING -> {
                HostQrCard(state.qrText)
                Spacer12()
                LinearProgressIndicator(Modifier.width(120.dp))
                Spacer(8.dp)
                Text(state.statusText, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center)
                Spacer12()
                TextButton(text = "取消等待", onClick = cancel)
            }

            LobbyStage.JOIN_SCANNING -> {
                Text("扫房主的二维码", style = MiuixTheme.textStyles.title3)
                Spacer12()
                if (cameraGranted) {
                    Box(
                        Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black),
                    ) {
                        QrScannerView(Modifier.fillMaxSize(), onResult = ::startJoinWith)
                    }
                } else {
                    LaunchedEffect(Unit) { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                    Text(
                        "需要相机权限才能扫码，请在弹出的授权里允许。",
                        style = MiuixTheme.textStyles.body2,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer12()
                TextButton(text = "取消", onClick = cancel)
            }

            LobbyStage.JOIN_CONNECTING -> {
                LinearProgressIndicator(Modifier.width(120.dp))
                Spacer(8.dp)
                Text(state.statusText, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center)
                Spacer12()
                TextButton(text = "取消", onClick = cancel)
            }

            LobbyStage.FAILED -> {
                Text("没能连上", style = MiuixTheme.textStyles.title3, color = MiuixTheme.colorScheme.error)
                Spacer12()
                Text(state.failReason, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center)
                Spacer12()
                Button(onClick = { state.reset() }, modifier = Modifier.fillMaxWidth()) {
                    Text("重试")
                }
                Spacer(8.dp)
                TextButton(text = "返回", onClick = cancel)
            }
        }
    }
}


@Composable
private fun HostQrCard(qrText: String?) {
    var bitmap by remember(qrText) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(qrText) { mutableStateOf(false) }
    LaunchedEffect(qrText) {
        val text = qrText ?: return@LaunchedEffect
        val bmp = withContext(Dispatchers.Default) { QrBitmap.generate(text, 640) }
        if (bmp == null) failed = true else bitmap = bmp
    }
    when {
        failed -> Text(
            "二维码生成失败，稍后再试。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        bitmap != null -> Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "联机对局二维码",
            modifier = Modifier.size(232.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp),
        )
        else -> Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
            LinearProgressIndicator(Modifier.width(80.dp))
        }
    }
}

@Composable
private fun Spacer12() = Spacer(12.dp)

@Composable
private fun Spacer(height: androidx.compose.ui.unit.Dp) {
    Box(Modifier.height(height))
}
