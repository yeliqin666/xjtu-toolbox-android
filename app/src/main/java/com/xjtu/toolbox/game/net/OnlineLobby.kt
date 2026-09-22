package com.xjtu.toolbox.game.net

import android.Manifest
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class LobbyStage { CHOOSE, HOST_WAITING, JOIN_SCANNING, JOIN_CONNECTING, FAILED }

/**
 * 联机大厅的状态：放在棋盘页（而不是大厅组件）里记住。
 *
 * 以前这些都是大厅组件自己的 remember，外加一个组件自己的协程作用域：用户在「联机对战」
 * 等人时切到「人机对战」看一眼，大厅离开组合，房间被关、等人的协程被取消，切回来又是一张新码。
 * 现在状态和协程都挂在 [scope]（棋盘页的作用域）上，切 tab 不丢；离开整个棋盘页时由
 * [rememberOnlineLobbyState] 统一收尾。
 */
@Stable
class OnlineLobbyState internal constructor(internal val scope: CoroutineScope) {
    var stage by mutableStateOf(LobbyStage.CHOOSE)
        internal set
    internal var qrText by mutableStateOf<String?>(null)
    internal var statusText by mutableStateOf("")
    internal var failReason by mutableStateOf("")
    /** 房主开房时选的先后手：true = 房主（我）先走。 */
    var hostFirst by mutableStateOf(true)

    internal var room: OnlineController.HostRoom? = null
    internal var job: Job? = null

    /** 关掉开着的房间、停掉正在进行的连接，回到「创建 / 加入」。已交出去的会话不受影响。 */
    fun reset() {
        job?.cancel()
        job = null
        room?.close()
        room = null
        qrText = null
        stage = LobbyStage.CHOOSE
    }
}

/** 在棋盘页顶层调用：离开棋盘页时关房间，切 tab 不关。 */
@Composable
fun rememberOnlineLobbyState(scope: CoroutineScope): OnlineLobbyState {
    val state = remember(scope) { OnlineLobbyState(scope) }
    DisposableEffect(state) { onDispose { state.reset() } }
    return state
}

/**
 * 三种棋共用的联机入口 UI：选创建/加入房间、房主显示二维码候人、加入方扫码连接。
 * 连上之后把 [OnlineGameSession] 交给调用方，UI 本身不认识棋盘——接下来怎么把
 * 对方的着法应用到棋盘上，是每个棋各自的事。
 *
 * 只走蓝牙：校园网开了 AP 隔离，局域网直连实测连不上。
 */
@Composable
fun OnlineLobbyContent(
    state: OnlineLobbyState,
    kind: GameKind,
    /** 房主开房时的规则参数（围棋的路数等），加入方从房主的 hello 里拿，不看这个。 */
    ruleParam: String?,
    onSessionReady: (session: OnlineGameSession, amHost: Boolean, hostFirst: Boolean, ruleParam: String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
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

    fun fail(reason: String) {
        state.failReason = reason
        state.stage = LobbyStage.FAILED
    }

    fun startHost() {
        state.reset()
        val hostFirst = state.hostFirst
        val opened = OnlineController.hostGame(context, kind, ruleParam, hostFirst, state.scope)
        if (opened == null) {
            fail(
                if (!OnlinePermissions.hasAllBlePermissions(context)) "联机要用蓝牙，需要「附近设备」权限。请在系统设置里给本应用开启后重试。"
                else "蓝牙没开。打开蓝牙后再创建房间。"
            )
            return
        }
        state.room = opened
        state.qrText = opened.qrText
        state.statusText = "等待对方扫码…两台手机靠近一点"
        state.stage = LobbyStage.HOST_WAITING
        state.job = state.scope.launch {
            val session = opened.awaitGuest()
            if (state.room !== opened) { session?.close(); return@launch }   // 期间已取消或重开
            state.room = null
            if (session == null) {
                fail("3 分钟内没有人连进来，或者连上后握手没成功，房间已关闭。点「重试」可以再开一局。")
                return@launch
            }
            state.stage = LobbyStage.CHOOSE
            onSessionReady(session, true, hostFirst, ruleParam)
        }
    }

    fun startJoinWith(scanned: String) {
        // 扫码控件对着同一张码会连续回调好几次，只认第一次
        if (state.stage != LobbyStage.JOIN_SCANNING) return
        val payload = OnlineQrPayload.decode(scanned)
        if (payload == null) {
            fail("这不是本游戏的联机对局二维码，或者对方的版本太旧，换一张再扫。")
            return
        }
        state.stage = LobbyStage.JOIN_CONNECTING
        state.statusText = "正在通过蓝牙连接房主…"
        state.job = state.scope.launch {
            OnlineController.joinGame(context, payload, kind, state.scope) { attempt ->
                when (attempt) {
                    OnlineController.JoinAttempt.Connecting -> state.statusText = "正在通过蓝牙连接房主…"
                    OnlineController.JoinAttempt.Handshaking -> state.statusText = "已连上，正在和房主确认对局…"
                    is OnlineController.JoinAttempt.Failed -> fail(attempt.message)
                    is OnlineController.JoinAttempt.Success -> {
                        state.stage = LobbyStage.CHOOSE
                        // joinGame 只在握手完成后才报 Success，这时 resolved* 已是房主给的真实值
                        onSessionReady(attempt.session, false, attempt.session.resolvedHostFirst, attempt.session.resolvedRuleParam)
                    }
                }
            }
        }
    }

    val cancel = { state.reset(); onCancel() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
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
                Button(onClick = { withBlePermission { state.stage = LobbyStage.JOIN_SCANNING } }, modifier = Modifier.fillMaxWidth()) {
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

/** 大厅里的一行选项：左边名称，右边一排互斥的小块。 */
@Composable
fun LobbyOptionRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(64.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun LobbyChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
        )
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
