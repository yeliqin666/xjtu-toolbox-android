package com.xjtu.toolbox.game.net

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 联机对局的入口编排：房主页"开局候人"、加入方"扫码连接"这两条流程，把 LAN/BLE
 * 两条传输通道、[OnlineGameSession] 握手都串起来，UI 只需要调这里的两个函数。
 */
object OnlineController {

    /** 房主允许对方最多花多久扫码+连接；超过这个时间还没人连上就放弃，不能无限等下去。 */
    private const val HOST_WAIT_TIMEOUT_MS = 180_000L

    /** 加入方局域网优先尝试的超时——plan.md §4.9 规定的 3 秒。 */
    private const val JOIN_LAN_TIMEOUT_MS = 3_000
    /** 局域网失败后退到 BLE 的超时——plan.md §4.9 规定的 10 秒。 */
    private const val JOIN_BLE_TIMEOUT_MS = 10_000L

    data class HostSession(val qrText: String, val awaitGuest: suspend () -> OnlineGameSession?)

    /**
     * 房主开局：起 LAN 监听 + （权限齐全时）起 BLE 广播，立即拼好二维码文本返回，
     * UI 拿到就能马上显示二维码，不用等任何一条通道真的连上——「显示二维码」和
     * 「等对方连进来」是两件事，不能因为等待连接而让二维码迟迟不出来。
     *
     * 真正等待连接是 [HostSession.awaitGuest]，调用方在显示完二维码之后另起协程调用它。
     */
    fun hostGame(
        context: Context,
        kind: GameKind,
        ruleParam: String?,
        hostFirst: Boolean,
        scope: CoroutineScope,
    ): HostSession {
        val token = OneTimeToken.generate()
        val lanServer = LanHostServer()
        val port = lanServer.listen()
        val ip = if (port != null) localLanIPv4Address() else null
        val lanInfo = if (port != null && ip != null) OnlineQrPayload.LanInfo(ip, port) else null

        val bleAvailable = OnlinePermissions.hasAllBlePermissions(context) &&
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                ?.adapter?.isEnabled == true
        val serviceUuid = UUID.randomUUID()
        val bleServer = if (bleAvailable) BleGattHostServer(context) else null
        val bleInfo = if (bleAvailable) OnlineQrPayload.BleInfo(serviceUuid.toString()) else null

        val qrText = OnlineQrPayload.encode(
            OnlineQrPayload(game = kind.wireId, lan = lanInfo, ble = bleInfo, token = token)
        )

        bleServer?.start(serviceUuid)

        val nsd = if (port != null) LanNsd(context) else null
        if (port != null && nsd != null) nsd.register(port, "xjtu-game-${kind.wireId}")

        val awaitGuest: suspend () -> OnlineGameSession? = await@{
            val transport = withTimeoutOrNull(HOST_WAIT_TIMEOUT_MS) {
                coroutineScope {
                    val lanWait = async { lanServer.acceptOne((HOST_WAIT_TIMEOUT_MS).toInt()) }
                    val bleWait = async { bleServer?.waitForReady() }
                    // 谁先连上用谁：两条通道谁先握手成功都行，另一条随之放弃。
                    val result = CompletableDeferred<OnlineTransport?>()
                    launch {
                        val t = lanWait.await()
                        if (t != null) result.complete(t) else if (bleWait.await() == null) result.complete(null)
                    }
                    launch {
                        val t = bleWait.await()
                        if (t != null && !result.isCompleted) result.complete(t)
                    }
                    result.await()
                }
            }
            nsd?.unregister()
            if (transport == null) {
                lanServer.close()
                bleServer?.stop()
                return@await null
            }
            // 两条通道谁先连上就用谁，另一条不再需要，及时关掉——尤其是 BLE 广播，
            // 继续开着既耗电，也可能让第二台设备误扫到同一个服务 UUID。
            when (transport.channelLabel) {
                "局域网" -> bleServer?.stop()
                "蓝牙" -> lanServer.close()
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
            session
        }

        return HostSession(qrText, awaitGuest)
    }

    sealed class JoinAttempt {
        object TryingLan : JoinAttempt()
        object TryingBle : JoinAttempt()
        data class Failed(val message: String) : JoinAttempt()
        data class Success(val session: OnlineGameSession) : JoinAttempt()
    }

    /**
     * 加入方扫到二维码之后的连接流程：先局域网（3 秒），失败自动转 BLE（10 秒），
     * 都失败给出明确、可执行的提示——不允许无限转圈。
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
                onAttempt(JoinAttempt.Failed("局域网连接失败，且尚未授予蓝牙权限，无法尝试蓝牙兜底。请在系统设置里给本应用开启附近设备权限后重试。"))
                return
            }
            onAttempt(JoinAttempt.TryingBle)
            val uuid = runCatching { UUID.fromString(payload.ble.serviceUuid) }.getOrNull()
            transport = uuid?.let { BleGattClientCentral(context).connect(it, JOIN_BLE_TIMEOUT_MS) }
        }

        if (transport == null) {
            onAttempt(
                JoinAttempt.Failed(
                    "局域网和蓝牙都没能连上对方。最常见的原因是两台手机不在同一个网络/校园网做了" +
                        "客户端隔离——请两人连接同一个热点后重试，或者互相靠近一点、都打开蓝牙再试一次。"
                )
            )
            return
        }

        val session = OnlineGameSession(
            role = OnlineRole.GUEST,
            token = payload.token,
            gameKind = kind,
            ruleParam = null,
            hostFirst = true,
            transport = transport,
            scope = scope,
        )
        session.start()
        onAttempt(JoinAttempt.Success(session))
    }
}
