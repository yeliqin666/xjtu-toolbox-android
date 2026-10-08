package com.xjtu.toolbox.game.net

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 联机大厅：**流程**在这里（与棋种无关），**界面**在平台侧。
 *
 * 为什么界面不能一起搬进来：大厅 UI 要用到三样平台专属的东西 —— 运行时权限弹窗
 * （`rememberLauncherForActivityResult`）、扫码控件（`QrScannerView`，是个 Android View）、
 * 以及把二维码字符串画成图片（`android.graphics.Bitmap`）。浏览器里这三样都不存在，
 * 而且**浏览器没有 BLE**，Web 端根本不可能开房/加入 ⇒ Web 的做法是「整个联机入口不出现」
 * （`OnlineLobby` 传 null），而不是做一个假大厅。
 *
 * 所以这条缝的粒度是「联机这件事」：`:core` 出 [OnlineLobbyState] + [OnlineLobbyHost] +
 * [OnlineLobby]，`:app` 用蓝牙实现 [OnlineLobbyHost]、用 Android 控件实现 [OnlineLobby.Content]，
 * 棋盘屏只认 [OnlineLobby]。**Android 的行为逐字不变**（大厅 UI 一行没改，只是从屏内挪到了接口后面）。
 */

enum class LobbyStage { CHOOSE, HOST_WAITING, JOIN_SCANNING, JOIN_CONNECTING, FAILED }

/** 房主开着的房间。实现方是平台（Android = BLE 广播）。 */
interface HostedRoom {
    /** 二维码内容，房间一创建就可用。 */
    val qrText: String

    /** 等对方连上并完成握手；超时、握手失败、房间被关都返回 null。 */
    suspend fun awaitGuest(): OnlineGameSession?

    /** 关房间（停广播）。可重复调用；已经交出去的会话不受影响。 */
    fun close()
}

/** 加入方的连接进度。 */
sealed class JoinAttempt {
    data object Connecting : JoinAttempt()
    data object Handshaking : JoinAttempt()
    data class Failed(val message: String) : JoinAttempt()
    data class Success(val session: OnlineGameSession) : JoinAttempt()
}

/**
 * 联机大厅的宿主能力（**只有 Android 有实现**，因为只有 Android 有 BLE）。
 *
 * 三个方法各自返回「能不能做」而不是抛异常：权限没给、蓝牙没开都是正常状态，
 * 要变成一句用户能照着做的提示，不是崩溃。
 */
interface OnlineLobbyHost {
    /** 现在能不能开房（蓝牙开着 + 权限齐）。 */
    fun canHost(): Boolean

    /** 开不了房时给用户的那句话。 */
    fun hostFailureReason(): String

    /** 开房；开不了返回 null（原因见 [hostFailureReason]）。 */
    fun host(kind: GameKind, ruleParam: String?, hostFirst: Boolean, scope: CoroutineScope): HostedRoom?

    /** 加入：连上房主的通道并完成握手，每一步都通过 [onAttempt] 汇报。 */
    suspend fun join(
        payload: OnlineQrPayload,
        kind: GameKind,
        scope: CoroutineScope,
        onAttempt: (JoinAttempt) -> Unit,
    )
}

/**
 * 平台提供的联机入口：一个 [OnlineLobbyHost] + 一段大厅界面。
 *
 * 棋盘屏只认这个接口；Web 传 null ⇒ 模式切换里**没有**「联机对战」这一格。
 */
interface OnlineLobby {
    val host: OnlineLobbyHost

    /** 三种棋共用的联机入口 UI（选创建/加入、房主显示二维码、加入方扫码）。 */
    @Composable
    fun Content(
        state: OnlineLobbyState,
        kind: GameKind,
        /** 房主开房时的规则参数（围棋的路数等）；加入方从房主的 hello 里拿，不看这个。 */
        ruleParam: String?,
        onSessionReady: (session: OnlineGameSession, amHost: Boolean, hostFirst: Boolean, ruleParam: String?) -> Unit,
        onCancel: () -> Unit,
        /** 房主开房前可选的规则（比如围棋路数），画在「创建房间」上面。 */
        hostOptions: (@Composable () -> Unit)? = null,
    )
}

/**
 * 联机大厅的状态：放在**棋盘页**（而不是大厅组件）里记住。
 *
 * 以前这些都是大厅组件自己的 remember，外加一个组件自己的协程作用域：用户在「联机对战」
 * 等人时切到「人机对战」看一眼，大厅离开组合，房间被关、等人的协程被取消，切回来又是一张新码。
 * 现在状态和协程都挂在 [scope]（棋盘页的作用域）上，切 tab 不丢；离开整个棋盘页时由
 * [rememberOnlineLobbyState] 统一收尾。
 */
@Stable
class OnlineLobbyState(
    val scope: CoroutineScope,
    val host: OnlineLobbyHost,
) {
    var stage by mutableStateOf(LobbyStage.CHOOSE)
        internal set

    var qrText: String? by mutableStateOf(null)
        private set
    var statusText: String by mutableStateOf("")
        private set
    var failReason: String by mutableStateOf("")
        private set

    /** 房主开房时选的先后手：true = 房主（我）先走。 */
    var hostFirst by mutableStateOf(true)

    internal var room: HostedRoom? = null
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

    fun fail(reason: String) {
        failReason = reason
        stage = LobbyStage.FAILED
    }

    /** 用户点了「加入房间」：进扫码页（真正开扫由平台的扫码控件回调驱动）。 */
    fun beginScanning() {
        stage = LobbyStage.JOIN_SCANNING
    }

    /** 房主开房。UI 先要权限，拿到之后调这里。 */
    fun startHost(
        kind: GameKind,
        ruleParam: String?,
        onSessionReady: (session: OnlineGameSession, hostFirst: Boolean, ruleParam: String?) -> Unit,
    ) {
        reset()
        val hostFirst = this.hostFirst
        val opened = host.host(kind, ruleParam, hostFirst, scope)
        if (opened == null) {
            fail(host.hostFailureReason())
            return
        }
        room = opened
        qrText = opened.qrText
        statusText = "等待对方扫码…两台手机靠近一点"
        stage = LobbyStage.HOST_WAITING
        job = scope.launch {
            val session = opened.awaitGuest()
            if (room !== opened) {
                // 期间已取消或重开：这一局作废，别把会话交给 UI
                session?.close()
                return@launch
            }
            room = null
            if (session == null) {
                fail("3 分钟内没有人连进来，或者连上后握手没成功，房间已关闭。点「重试」可以再开一局。")
                return@launch
            }
            stage = LobbyStage.CHOOSE
            onSessionReady(session, hostFirst, ruleParam)
        }
    }

    /** 加入方扫到码之后调这里。 */
    fun startJoinWith(
        scanned: String,
        kind: GameKind,
        onSessionReady: (session: OnlineGameSession, hostFirst: Boolean, ruleParam: String?) -> Unit,
    ) {
        // 扫码控件对着同一张码会连续回调好几次，只认第一次
        if (stage != LobbyStage.JOIN_SCANNING) return
        val payload = OnlineQrPayload.decode(scanned)
        if (payload == null) {
            fail("这不是本游戏的联机对局二维码，或者对方的版本太旧，换一张再扫。")
            return
        }
        stage = LobbyStage.JOIN_CONNECTING
        statusText = "正在通过蓝牙连接房主…"
        job = scope.launch {
            host.join(payload, kind, scope) { attempt ->
                when (attempt) {
                    JoinAttempt.Connecting -> statusText = "正在通过蓝牙连接房主…"
                    JoinAttempt.Handshaking -> statusText = "已连上，正在和房主确认对局…"
                    is JoinAttempt.Failed -> fail(attempt.message)
                    is JoinAttempt.Success -> {
                        stage = LobbyStage.CHOOSE
                        // join 只在握手完成后才报 Success，这时 resolved* 已是房主给的真实值
                        onSessionReady(attempt.session, attempt.session.resolvedHostFirst, attempt.session.resolvedRuleParam)
                    }
                }
            }
        }
    }
}

/** 在棋盘页顶层调用：离开棋盘页时关房间，切 tab 不关。 */
@Composable
fun rememberOnlineLobbyState(scope: CoroutineScope, host: OnlineLobbyHost): OnlineLobbyState {
    val state = remember(scope, host) { OnlineLobbyState(scope, host) }
    DisposableEffect(state) { onDispose { state.reset() } }
    return state
}

/**
 * 大厅里的一行选项：左边名称，右边一排互斥的小块。
 *
 * 从 :app 的 `OnlineLobby.kt` 搬进 commonMain：围棋屏要在房主开房前画「棋盘路数」，
 * 那段 `hostOptions` 是共享屏自己拼的，所以这两个小组件必须跟它同侧。
 */
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
