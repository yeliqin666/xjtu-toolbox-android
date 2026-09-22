package com.xjtu.toolbox.game.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 局域网通道：一行一条 JSON，用换行分帧——普通 TCP socket，不涉及任何"改网络状态"的操作
 * （不开热点、不切 Wi-Fi）。
 *
 * NSD 广播用 [LanNsd] 单独处理；这个文件只管"连上之后怎么收发一行行文本"。
 */
private class SocketLineTransport(
    private val socket: Socket,
    override val channelLabel: String = "局域网",
) : OnlineTransport {
    private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

    override val incoming: Flow<String> = flow {
        try {
            while (true) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                if (line.isNotEmpty()) emit(line)
            }
        } catch (_: IOException) {
            // socket 被关闭/对方断开都会在这里落地，交给上层的心跳超时/EOF 统一按"断线"处理
        }
    }

    override suspend fun send(line: String) = withContext(Dispatchers.IO) {
        try {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        } catch (_: IOException) {
            // 发送失败等心跳超时兜底判掉线，这里不重复抛异常打断调用方的协程
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * 房主侧：开一个随机端口的 [ServerSocket] 等加入方连进来。
 *
 * 只接受第一个连接——联机对局是一对一的，第二个连进来的直接关掉（走口令校验也会在
 * 协议层被拒，这里提前短路只是省一次握手）。
 */
class LanHostServer {
    private var serverSocket: ServerSocket? = null

    /** 开始监听，返回实际分配到的端口；失败（比如端口资源耗尽）返回 null。 */
    fun listen(): Int? = try {
        val s = ServerSocket(0)
        serverSocket = s
        s.localPort
    } catch (_: IOException) {
        null
    }

    /** 阻塞等待一个连接；[timeoutMs] 到了还没人连就返回 null。调用方需要切到 IO 线程调用。 */
    suspend fun acceptOne(timeoutMs: Int): OnlineTransport? = withContext(Dispatchers.IO) {
        val s = serverSocket ?: return@withContext null
        try {
            s.soTimeout = timeoutMs
            val client = s.accept()
            SocketLineTransport(client)
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}

/** 加入方侧：直接拿二维码里的 ip:port 连，不走 NSD 发现——见文件顶部/README 里的设计取舍说明。 */
object LanClientConnector {
    suspend fun connect(ip: String, port: Int, timeoutMs: Int = 3_000): OnlineTransport? =
        withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                SocketLineTransport(socket)
            } catch (_: IOException) {
                null
            }
        }
}

/**
 * 只注册（`registerService`），不做发现/解析（`discoverServices` + `resolveService`）——顺带
 * 绕开了 `resolveService` 在 API 34 起被弃用、改用 `registerServiceInfoCallback` /
 * `unregisterServiceInfoCallback` 这件事：minSdk 31 要兼容两套 API 才能用发现功能，
 * 而这里根本不需要发现，也就不用为一个不走的路径分支两套实现。
 *
 * NSD 广播：满足 plan.md 要求的"用 NSD 广播服务"，但加入方**不**靠 mDNS 浏览来发现房主——
 * 二维码里已经直接带了 ip:port，campus Wi-Fi 常见的组播/mDNS 被隔离/丢弃问题不会因为
 * "多一次发现"而变得更可靠，反而是直连 TCP 更省一次不确定的网络往返。
 * 这里注册服务，主要是为了让同一局域网内的其它 mDNS 感知工具（以及未来可能的"局域网内直接
 * 发现，不用扫码"功能）能看到这个房间，属于面向未来的补全，不在当前加入流程的关键路径上。
 *
 * `MulticastLock` 只在联机页停留期间持有，一离开页面就释放——部分机型不持有它收不到组播包，
 * 但长期持有会额外耗电，所以生命周期严格跟着"联机页是否可见"走。
 */
class LanNsd(context: Context) {
    private val appContext = context.applicationContext
    private var nsdManager: android.net.nsd.NsdManager? = null
    private var registrationListener: android.net.nsd.NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun register(port: Int, serviceNameHint: String) {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("xjtu-toolbox-game-nsd")?.apply {
            setReferenceCounted(true)
            try {
                acquire()
            } catch (_: SecurityException) {
                // 极少数定制 ROM 会在这里抛权限异常，拿不到锁也不影响直连 TCP，只是部分机型
                // 收不到组播广播——反正加入方走的是二维码里的直连 IP，不依赖这把锁。
            }
        }

        val mgr = appContext.getSystemService(Context.NSD_SERVICE) as? android.net.nsd.NsdManager ?: return
        nsdManager = mgr
        val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
            serviceName = serviceNameHint
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: android.net.nsd.NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: android.net.nsd.NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: android.net.nsd.NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: android.net.nsd.NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { mgr.registerService(serviceInfo, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun unregister() {
        registrationListener?.let { l -> runCatching { nsdManager?.unregisterService(l) } }
        registrationListener = null
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        multicastLock = null
    }

    companion object {
        const val SERVICE_TYPE = "_xjtutoolboxgame._tcp"
    }
}

/**
 * 本机在当前 Wi-Fi / 热点下的 IPv4 地址，房主用它拼进二维码。拿不到返回 null。
 *
 * 以前取「第一个非回环 IPv4」：手机同时开着移动数据时，枚举顺序里排在前面的常常是
 * rmnet/ccmni（运营商内网地址，对方根本连不到），开着 VPN 时还可能是 tun0。二维码里的
 * 地址一错，加入方的 TCP 必然超时，表现就是「怎么都连不上」。
 *
 * 现在按网卡名挑：Wi-Fi（wlan*）和本机开的热点（ap* / swlan* / wlan1 等）优先，有线其次，
 * 蜂窝、VPN、点对点等一律排除；同一类里只要局域网私有地址。
 */
fun localLanIPv4Address(): String? = runCatching {
    val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
        .mapNotNull { nif ->
            val rank = lanInterfaceRank(nif.name) ?: return@mapNotNull null
            val addr = nif.inetAddresses.toList()
                .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
                ?: return@mapNotNull null
            rank to addr.hostAddress
        }
    candidates.minByOrNull { it.first }?.second
}.getOrNull()

/** 网卡名 → 优先级（小的优先）；不该用来局域网直连的网卡返回 null。 */
internal fun lanInterfaceRank(name: String): Int? {
    val n = name.lowercase()
    return when {
        EXCLUDED_IFACE_PREFIXES.any { n.startsWith(it) } -> null
        n.startsWith("wlan") -> 0
        n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") -> 1
        n.startsWith("eth") -> 2
        else -> 3
    }
}

private val EXCLUDED_IFACE_PREFIXES = listOf(
    "rmnet", "ccmni", "pdp", "clat", "v4-", "tun", "ppp", "ipsec", "dummy", "p2p", "rndis", "bt-pan", "lo",
)
