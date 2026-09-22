package com.xjtu.toolbox.game.net

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xjtu.toolbox.qrlogin.QrScannerView
import com.xjtu.toolbox.util.QrBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 三种棋共用的联机入口 UI：选创建/加入房间、房主显示二维码候人、加入方扫码连接。
 * 连上之后把 [OnlineGameSession] 交给调用方，UI 本身不认识棋盘——接下来怎么把
 * 对方的着法应用到棋盘上，是每个棋各自的事。
 */
private enum class LobbyStage { CHOOSE, HOST_WAITING, JOIN_SCANNING, JOIN_CONNECTING, FAILED }

@Composable
fun OnlineLobbyContent(
    kind: GameKind,
    ruleParam: String?,
    /**
     * 会话协程挂在谁身上。**必须**是比大厅活得久的作用域（棋盘页的 rememberCoroutineScope）：
     * 以前用大厅自己的 scope，一连上画面就从大厅切到棋盘，大厅离开组合、scope 被取消，
     * 会话的收发和心跳协程跟着全死——握手发不出去，对局里也收不到对方的着法。
     */
    sessionScope: kotlinx.coroutines.CoroutineScope,
    hostFirstDefault: Boolean = true,
    onSessionReady: (session: OnlineGameSession, amHost: Boolean, hostFirst: Boolean, ruleParam: String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(LobbyStage.CHOOSE) }
    var qrText by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("") }
    var failReason by remember { mutableStateOf("") }

    // 开着的房间：取消、重试、离开页面都要关掉，否则端口和蓝牙广播会一直挂到超时。
    var room by remember { mutableStateOf<OnlineController.HostRoom?>(null) }
    fun closeRoom() {
        room?.close()
        room = null
    }
    DisposableEffect(Unit) { onDispose { closeRoom() } }

    // 蓝牙三件套权限一起要，只在点了「创建 / 加入」之后才申请，单机、同屏双人碰不到。
    // **授权结果回来之后**才真正开房 / 进扫码：以前是弹出授权框的同时就开房，那一刻权限还没给，
    // 房主这一局的二维码里就没有蓝牙，第一次联机永远只剩局域网一条路。
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
        closeRoom()
        val opened = OnlineController.hostGame(context, kind, ruleParam, hostFirstDefault, sessionScope)
        if (!opened.lanReady && !opened.bleReady) {
            opened.close()
            failReason = "没连 Wi-Fi，蓝牙也没开或没授权，对方没有任何办法连进来。连上 Wi-Fi 或打开蓝牙后再开房。"
            stage = LobbyStage.FAILED
            return
        }
        room = opened
        qrText = opened.qrText
        stage = LobbyStage.HOST_WAITING
        statusText = when {
            opened.lanReady && opened.bleReady -> "等待对方扫码…局域网和蓝牙同时在等，谁先通用谁"
            opened.lanReady -> "等待对方扫码…（蓝牙没开，只能走局域网，两人需在同一网络）"
            else -> "等待对方扫码…（没连 Wi-Fi，只能走蓝牙，请靠近一点）"
        }
        scope.launch {
            val session = opened.awaitGuest()
            if (room !== opened) { session?.close(); return@launch }   // 期间已取消或重开
            room = null
            if (session == null) {
                failReason = "3 分钟内没有人连进来，或者连上后握手没成功，房间已关闭。点「重试」可以再开一局。"
                stage = LobbyStage.FAILED
                return@launch
            }
            onSessionReady(session, true, hostFirstDefault, ruleParam)
        }
    }

    fun startJoinWith(scanned: String) {
        // 扫码控件对着同一张码会连续回调好几次，只认第一次
        if (stage != LobbyStage.JOIN_SCANNING) return
        val payload = OnlineQrPayload.decode(scanned)
        if (payload == null) {
            failReason = "这不是本游戏的联机对局二维码，换一张再扫。"
            stage = LobbyStage.FAILED
            return
        }
        stage = LobbyStage.JOIN_CONNECTING
        statusText = "正在尝试局域网连接…"
        scope.launch {
            OnlineController.joinGame(context, payload, kind, sessionScope) { attempt ->
                when (attempt) {
                    OnlineController.JoinAttempt.TryingLan -> statusText = "正在尝试局域网连接…"
                    OnlineController.JoinAttempt.TryingBle -> statusText = "局域网没连上，正在尝试蓝牙…"
                    OnlineController.JoinAttempt.Handshaking -> statusText = "已连上，正在和房主确认对局…"
                    is OnlineController.JoinAttempt.Failed -> {
                        failReason = attempt.message
                        stage = LobbyStage.FAILED
                    }
                    is OnlineController.JoinAttempt.Success -> {
                        // joinGame 只在握手完成后才报 Success，这时 resolved* 已是房主给的真实值
                        onSessionReady(attempt.session, false, attempt.session.resolvedHostFirst, attempt.session.resolvedRuleParam)
                    }
                }
            }
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (stage) {
            LobbyStage.CHOOSE -> {
                Text("联机对战", style = MiuixTheme.textStyles.title3)
                Spacer12()
                Text(
                    "跟朋友各拿一台手机对弈：优先走同一网络下的局域网，连不上（校园网常见）" +
                        "会自动改走蓝牙直连，全程不改你手机的网络设置。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                Spacer12()
                Button(onClick = { withBlePermission(::startHost) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text("创建房间（我显示二维码）")
                }
                Spacer(8.dp)
                Button(onClick = { withBlePermission { stage = LobbyStage.JOIN_SCANNING } }, modifier = Modifier.fillMaxWidth()) {
                    Text("加入房间（我扫对方的码）")
                }
                Spacer(8.dp)
                TextButton(text = "取消", onClick = onCancel, modifier = Modifier.fillMaxWidth())
            }

            LobbyStage.HOST_WAITING -> {
                HostQrCard(qrText)
                Spacer12()
                LinearProgressIndicator(Modifier.width(120.dp))
                Spacer(8.dp)
                Text(statusText, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center)
                Spacer12()
                TextButton(text = "取消等待", onClick = { closeRoom(); onCancel() })
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
                TextButton(text = "取消", onClick = onCancel)
            }

            LobbyStage.JOIN_CONNECTING -> {
                LinearProgressIndicator(Modifier.width(120.dp))
                Spacer(8.dp)
                Text(statusText, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center)
                Spacer12()
                TextButton(text = "取消", onClick = onCancel)
            }

            LobbyStage.FAILED -> {
                Text("没能连上", style = MiuixTheme.textStyles.title3, color = MiuixTheme.colorScheme.error)
                Spacer12()
                Text(failReason, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center)
                Spacer12()
                Button(onClick = { closeRoom(); stage = LobbyStage.CHOOSE }, modifier = Modifier.fillMaxWidth()) {
                    Text("重试")
                }
                Spacer(8.dp)
                TextButton(text = "返回", onClick = onCancel)
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
            "二维码生成失败，换个网络环境（IP 更短）或者稍后再试。",
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
