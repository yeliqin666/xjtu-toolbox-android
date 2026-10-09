package com.xjtu.toolbox.game.net

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * 联机对局的入口编排：房主「开房候人」、加入方「扫码连接」两条流程。
 * 把 BLE 传输和 [OnlineGameSession] 的握手串起来，**握手完成后**才把会话交给 UI。
 *
 * 只走蓝牙：以前是局域网 TCP 优先、蓝牙兜底，可校园网开了 AP 隔离，手机之间根本连不上，
 * 实测只有蓝牙可用；局域网那条路只会让加入方先白等一轮超时，房主还多开一个端口和 NSD 广播。
 *
 * 从 `OnlineLobby.kt` 拆出来的一部分：**协议流程**（[OnlineLobbyState]）与**界面**都进了 :core，
 * 这里只剩「蓝牙怎么开、怎么连」，并实现 :core 的 [OnlineLobbyHost]（见 [BleOnlineLobbyHost]）。
 */
object OnlineController {

    /** 房主最多等多久；超过还没人连上就关房间，不能无限等下去。 */
    private const val HOST_WAIT_TIMEOUT_MS = 180_000L

    /** 加入方蓝牙连接的超时（含扫描、连接、服务发现、订阅）。 */
    private const val JOIN_BLE_TIMEOUT_MS = 15_000L

    /** 通道连上之后，hello / hello_ack 往返的超时。 */
    private const val HANDSHAKE_TIMEOUT_MS = 8_000L

    /** 本机蓝牙能不能用来联机：三项权限都给了、蓝牙开着。 */
    fun bluetoothReady(context: Context): Boolean =
        OnlinePermissions.hasAllBlePermissions(context) &&
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                ?.adapter?.isEnabled == true

    /**
     * 一个开着的房间。二维码 [qrText] 一创建就有，UI 立刻能显示；
     * 真正等人是 [awaitGuest]；用户取消或离开页面必须调 [close]，把蓝牙广播收回来。
     */
    class HostRoom internal constructor(
        override val qrText: String,
        private val bleServer: BleGattHostServer,
        private val token: String,
        private val kind: GameKind,
        private val ruleParam: String?,
        private val hostFirst: Boolean,
        private val scope: CoroutineScope,
    ) : HostedRoom {
        @Volatile private var closed = false

        /** 等对方连上并完成握手；超时、握手失败、房间被关都返回 null。 */
        override suspend fun awaitGuest(): OnlineGameSession? {
            val transport = bleServer.waitForReady(HOST_WAIT_TIMEOUT_MS)
            if (transport == null || closed) {
                transport?.close()
                close()
                return null
            }
            val session = OnlineGameSession(
                role = OnlineRole.HOST,
                token = token,
                gameKind = kind,
                ruleParam = ruleParam,
                hostFirst = hostFirst,
                transport = transport,
                scope = scope,
            )
            session.start()
            return if (session.awaitHandshake(HANDSHAKE_TIMEOUT_MS)) session else null
        }

        /** 关房间：停掉蓝牙广播。可重复调用；已经交出去的会话不受影响。 */
        override fun close() {
            closed = true
            bleServer.stop()
        }
    }

    /**
     * 房主开房：起 BLE 广播，立即拼好二维码。蓝牙没开或没授权时返回 null，UI 提示用户处理。
     */
    fun hostGame(
        context: Context,
        kind: GameKind,
        ruleParam: String?,
        hostFirst: Boolean,
        scope: CoroutineScope,
    ): HostRoom? {
        if (!bluetoothReady(context)) return null
        val token = OneTimeToken.generate()
        val serviceUuid = UUID.randomUUID()
        val bleServer = BleGattHostServer(context).also { it.start(serviceUuid) }
        val qrText = OnlineQrPayload.encode(
            OnlineQrPayload(game = kind.wireId, ble = OnlineQrPayload.BleInfo(serviceUuid.toString()), token = token)
        )
        return HostRoom(
            qrText = qrText,
            bleServer = bleServer,
            token = token,
            kind = kind,
            ruleParam = ruleParam,
            hostFirst = hostFirst,
            scope = scope,
        )
    }

    /**
     * 加入方扫到二维码后的连接流程：蓝牙连上房主，再等握手。
     * 每一步失败都给出能照着做的提示，不允许无限转圈。
     */
    suspend fun joinGame(
        context: Context,
        payload: OnlineQrPayload,
        kind: GameKind,
        scope: CoroutineScope,
        onAttempt: (JoinAttempt) -> Unit,
    ) {
        if (payload.game != kind.wireId) {
            onAttempt(JoinAttempt.Failed("这个二维码是「${payload.game}」的联机码，跟当前棋类对不上"))
            return
        }
        if (!OnlinePermissions.hasAllBlePermissions(context)) {
            onAttempt(JoinAttempt.Failed("联机要用蓝牙，需要「附近设备」权限。请在系统设置里给本应用开启后重试。"))
            return
        }
        if (!bluetoothReady(context)) {
            onAttempt(JoinAttempt.Failed("本机蓝牙没开。打开蓝牙后再扫一次码。"))
            return
        }
        onAttempt(JoinAttempt.Connecting)
        val uuid = payload.ble?.serviceUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val transport = uuid?.let { BleGattClientCentral(context).connect(it, JOIN_BLE_TIMEOUT_MS) }
        if (transport == null) {
            onAttempt(JoinAttempt.Failed("蓝牙没连上。两台手机靠近一点、都打开蓝牙，让房主重新开房后再扫。"))
            return
        }

        onAttempt(JoinAttempt.Handshaking)
        val session = OnlineGameSession(
            role = OnlineRole.GUEST,
            token = payload.token,
            gameKind = kind,
            // 规则参数、先后手都由房主在 hello 里给，握手完成后从 session.resolved* 读
            ruleParam = null,
            hostFirst = true,
            transport = transport,
            scope = scope,
        )
        session.start()
        if (session.awaitHandshake(HANDSHAKE_TIMEOUT_MS)) {
            onAttempt(JoinAttempt.Success(session))
        } else {
            val reason = (session.state.value as? OnlineConnState.Disconnected)?.reason
            onAttempt(JoinAttempt.Failed(reason ?: "连上了对方，但握手没有完成，请让房主重新开一次房间"))
        }
    }
}

/**
 * :core 的 [OnlineLobbyHost] 的 Android 实现 —— 就是上面那个 BLE 编排。
 *
 * 这一层存在的唯一理由是「BLE 只有 Android 有」：把 [Context] 挡在共享层之外，
 * 共享层的棋盘屏只认 [OnlineLobbyHost]。Web 端不提供这个实现，联机入口整个不出现。
 */
class BleOnlineLobbyHost(private val context: Context) : OnlineLobbyHost {

    override fun canHost(): Boolean = OnlineController.bluetoothReady(context)

    override fun hostFailureReason(): String =
        if (!OnlinePermissions.hasAllBlePermissions(context)) {
            "联机要用蓝牙，需要「附近设备」权限。请在系统设置里给本应用开启后重试。"
        } else {
            "蓝牙没开。打开蓝牙后再创建房间。"
        }

    override fun host(kind: GameKind, ruleParam: String?, hostFirst: Boolean, scope: CoroutineScope): HostedRoom? =
        OnlineController.hostGame(context, kind, ruleParam, hostFirst, scope)

    override suspend fun join(
        payload: OnlineQrPayload,
        kind: GameKind,
        scope: CoroutineScope,
        onAttempt: (JoinAttempt) -> Unit,
    ) = OnlineController.joinGame(context, payload, kind, scope, onAttempt)
}
