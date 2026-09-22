package com.xjtu.toolbox.game.net

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 联机对局的入口编排：房主「开房候人」、加入方「扫码连接」两条流程。
 * 把 LAN / BLE 两条传输、[OnlineGameSession] 的握手串起来，**握手完成后**才把会话交给 UI。
 */
object OnlineController {

    /** 房主最多等多久；超过还没人连上就关房间，不能无限等下去。 */
    private const val HOST_WAIT_TIMEOUT_MS = 180_000L

    /** 加入方局域网直连的超时。 */
    private const val JOIN_LAN_TIMEOUT_MS = 3_000

    /** 局域网失败后退到 BLE 的超时（含扫描、连接、服务发现、订阅）。 */
    private const val JOIN_BLE_TIMEOUT_MS = 12_000L

    /** 通道连上之后，hello / hello_ack 往返的超时。 */
    private const val HANDSHAKE_TIMEOUT_MS = 8_000L

    private const val CHANNEL_LAN = "局域网"

    /**
     * 一个开着的房间。二维码 [qrText] 一创建就有，UI 立刻能显示；
     * 真正等人是 [awaitGuest]；用户取消或离开页面必须调 [close]，把端口和蓝牙广播都收回来。
     */
    class HostRoom internal constructor(
        val qrText: String,
        /** 局域网监听起来了没有：没起来（没连 Wi-Fi）时 UI 要提醒只能靠蓝牙。 */
        val lanReady: Boolean,
        val bleReady: Boolean,
        private val lanServer: LanHostServer,
        private val bleServer: BleGattHostServer?,
        private val nsd: LanNsd?,
        private val token: String,
        private val kind: GameKind,
        private val ruleParam: String?,
        private val hostFirst: Boolean,
        private val scope: CoroutineScope,
    ) {
        @Volatile private var closed = false

        /**
         * 等对方连上并完成握手；超时、握手失败、房间被关都返回 null。
         *
         * 两条通道同时等，谁先连上用谁，另一条**立刻关掉**。以前把两边包在一个
         * `coroutineScope` 里各开一个 async，拿到先到的结果后就想返回——可 `coroutineScope`
         * 要等所有子协程结束才返回：蓝牙先连上时，局域网那边还阻塞在 `accept()` 里（阻塞 IO
         * 不响应取消），房主一直卡到 180 秒超时，对方那头 15 秒收不到 hello 就判了掉线。
         * 局域网先连上时同理要等蓝牙那边超时。现在输的一方靠「关掉 ServerSocket / 停掉 GATT」
         * 解除阻塞，再统一取消。
         */
        suspend fun awaitGuest(): OnlineGameSession? {
            val transport = coroutineScope {
                val winner = CompletableDeferred<OnlineTransport?>()
                val lanJob = launch {
                    if (!lanReady) return@launch
                    val t = lanServer.acceptOne(HOST_WAIT_TIMEOUT_MS.toInt())
                    if (t != null && !winner.complete(t)) t.close()
                }
                val bleJob = launch {
                    val t = bleServer?.waitForReady(HOST_WAIT_TIMEOUT_MS) ?: return@launch
                    if (!winner.complete(t)) t.close()
                }
                launch {
                    lanJob.join()
                    bleJob.join()
                    winner.complete(null)
                }
                val t = winner.await()
                // 输的一方解除阻塞：accept() 只有关掉 ServerSocket 才会抛出来
                if (t == null || t.channelLabel != CHANNEL_LAN) lanServer.close()
                if (t == null || t.channelLabel == CHANNEL_LAN) bleServer?.stop()
                coroutineContext.cancelChildren()
                t
            }
            nsd?.unregister()
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

        /** 关房间：端口、蓝牙广播、NSD 全部收回。可重复调用；已经交出去的会话不受影响。 */
        fun close() {
            closed = true
            lanServer.close()
            bleServer?.stop()
            nsd?.unregister()
        }
    }

    /**
     * 房主开房：起 LAN 监听 +（权限和蓝牙都在时）起 BLE 广播，立即拼好二维码。
     * 调用前 UI 应已申请过蓝牙权限，否则这一局只有局域网一条路。
     */
    fun hostGame(
        context: Context,
        kind: GameKind,
        ruleParam: String?,
        hostFirst: Boolean,
        scope: CoroutineScope,
    ): HostRoom {
        val token = OneTimeToken.generate()
        val lanServer = LanHostServer()
        val port = lanServer.listen()
        val ip = if (port != null) localLanIPv4Address() else null
        val lanInfo = if (port != null && ip != null) OnlineQrPayload.LanInfo(ip, port) else null
        if (lanInfo == null) lanServer.close()

        val bleAvailable = OnlinePermissions.hasAllBlePermissions(context) &&
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                ?.adapter?.isEnabled == true
        val serviceUuid = UUID.randomUUID()
        val bleServer = if (bleAvailable) BleGattHostServer(context).also { it.start(serviceUuid) } else null
        val bleInfo = if (bleServer != null) OnlineQrPayload.BleInfo(serviceUuid.toString()) else null

        val nsd = if (lanInfo != null) LanNsd(context).also { it.register(lanInfo.port, "xjtu-game-${kind.wireId}") } else null

        val qrText = OnlineQrPayload.encode(
            OnlineQrPayload(game = kind.wireId, lan = lanInfo, ble = bleInfo, token = token)
        )
        return HostRoom(
            qrText = qrText,
            lanReady = lanInfo != null,
            bleReady = bleServer != null,
            lanServer = lanServer,
            bleServer = bleServer,
            nsd = nsd,
            token = token,
            kind = kind,
            ruleParam = ruleParam,
            hostFirst = hostFirst,
            scope = scope,
        )
    }

    sealed class JoinAttempt {
        object TryingLan : JoinAttempt()
        object TryingBle : JoinAttempt()
        object Handshaking : JoinAttempt()
        data class Failed(val message: String) : JoinAttempt()
        data class Success(val session: OnlineGameSession) : JoinAttempt()
    }

    /**
     * 加入方扫到二维码后的连接流程：先局域网，失败自动转 BLE，通道通了再等握手。
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

        var transport: OnlineTransport? = null

        if (payload.lan != null) {
            onAttempt(JoinAttempt.TryingLan)
            transport = LanClientConnector.connect(payload.lan.ip, payload.lan.port, JOIN_LAN_TIMEOUT_MS)
        }

        if (transport == null && payload.ble != null) {
            if (!OnlinePermissions.hasAllBlePermissions(context)) {
                onAttempt(JoinAttempt.Failed("局域网没连上，而蓝牙兜底需要「附近设备」权限。请在系统设置里给本应用开启后重试。"))
                return
            }
            val adapterOn = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                ?.adapter?.isEnabled == true
            if (!adapterOn) {
                onAttempt(JoinAttempt.Failed("局域网没连上，而本机蓝牙没开。打开蓝牙后再扫一次码。"))
                return
            }
            onAttempt(JoinAttempt.TryingBle)
            val uuid = runCatching { UUID.fromString(payload.ble.serviceUuid) }.getOrNull()
            transport = uuid?.let { BleGattClientCentral(context).connect(it, JOIN_BLE_TIMEOUT_MS) }
        }

        if (transport == null) {
            onAttempt(JoinAttempt.Failed(joinFailureHint(payload)))
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

    /** 两条路都失败时，按二维码里实际带了哪条通道给提示。 */
    private fun joinFailureHint(payload: OnlineQrPayload): String = when {
        payload.lan != null && payload.ble != null ->
            "局域网和蓝牙都没连上。校园网通常禁止手机之间互连——请两人连同一个手机热点再试，" +
                "或者靠近一点、两边都打开蓝牙重新开房。"
        payload.lan != null ->
            "局域网没连上，房主那边也没开蓝牙。请两人连同一个手机热点（校园网禁止手机互连），" +
                "或者让房主打开蓝牙、授予附近设备权限后重新开房。"
        else ->
            "房主没连 Wi-Fi，只能走蓝牙，但蓝牙没连上。请靠近一点重试，或者两人连同一个热点后让房主重新开房。"
    }
}
