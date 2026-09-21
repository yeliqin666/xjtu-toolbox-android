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
import androidx.compose.foundation.shape.RoundedCornerShape
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
    hostFirstDefault: Boolean = true,
    onSessionReady: (session: OnlineGameSession, amHost: Boolean, hostFirst: Boolean, ruleParam: String?) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(LobbyStage.CHOOSE) }
    var qrText by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("") }
    var failReason by remember { mutableStateOf("") }

    // 蓝牙三件套权限一起要——三种都要么一起用得上（BLE 兜底），要么都用不上，没必要分开问。
    // 只在用户点了"创建房间"/"加入房间"之后才申请，单机、同屏双人完全碰不到这个 launcher。
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果直接在下面 OnlinePermissions.hasAllBlePermissions 里重新查，不用单独处理 */ }

    fun ensureBlePermissionAsked() {
        if (!OnlinePermissions.hasAllBlePermissions(context)) {
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
        ensureBlePermissionAsked()
        stage = LobbyStage.HOST_WAITING
        statusText = "等待对方扫码加入…（局域网优先，连不上会自动等蓝牙）"
        val hostSession = OnlineController.hostGame(context, kind, ruleParam, hostFirstDefault, scope)
        qrText = hostSession.qrText
        scope.launch {
            val session = hostSession.awaitGuest()
            if (session == null) {
                failReason = "180 秒内没有人扫码加入，已停止等待。回去重新打开一次联机页可以再开一局。"
                stage = LobbyStage.FAILED
                return@launch
            }
            onSessionReady(session, true, hostFirstDefault, ruleParam)
        }
    }

    fun startJoinWith(scanned: String) {
        val payload = OnlineQrPayload.decode(scanned)
        if (payload == null) {
            failReason = "这不是本游戏的联机对局二维码，换一张再扫。"
            stage = LobbyStage.FAILED
            return
        }
        ensureBlePermissionAsked()
        stage = LobbyStage.JOIN_CONNECTING
        statusText = "正在尝试局域网连接…"
        scope.launch {
            OnlineController.joinGame(context, payload, kind, scope) { attempt ->
                when (attempt) {
                    OnlineController.JoinAttempt.TryingLan -> statusText = "正在尝试局域网连接…"
                    OnlineController.JoinAttempt.TryingBle -> statusText = "局域网没连上，正在尝试蓝牙…"
                    is OnlineController.JoinAttempt.Failed -> {
                        failReason = attempt.message
                        stage = LobbyStage.FAILED
                    }
                    is OnlineController.JoinAttempt.Success -> {
                        // hostFirst / ruleParam 由房主在 hello 里告知，握手完成后才知道准确值；
                        // 这里先占位传 true/null，真正的值等 onSessionReady 的调用方读
                        // session.resolvedHostFirst / resolvedRuleParam。
                        onSessionReady(attempt.session, false, attempt.session.resolvedHostFirst, attempt.session.resolvedRuleParam)
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
                Button(onClick = ::startHost, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text("创建房间（我显示二维码）")
                }
                Spacer(8.dp)
                Button(onClick = { stage = LobbyStage.JOIN_SCANNING }, modifier = Modifier.fillMaxWidth()) {
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
                TextButton(text = "取消等待", onClick = onCancel)
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
                Button(onClick = { stage = LobbyStage.CHOOSE }, modifier = Modifier.fillMaxWidth()) {
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
