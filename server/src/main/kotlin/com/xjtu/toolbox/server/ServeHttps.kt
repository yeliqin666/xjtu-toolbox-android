package com.xjtu.toolbox.server

import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import kotlinx.coroutines.runBlocking

/**
 * **HTTPS 形态**（D11）的装配：CIO 的明文监听只绑回环（本进程内部用），[TlsFront] 对
 * `config.host:config.port` 讲 TLS。
 *
 * ## 为什么不是「给 CIO 配一个 sslConnector」
 *
 * Ktor 的 `sslConnector` 只有 Netty / Jetty / Tomcat 这几个引擎认。**CIO 引擎不接受 TLS 连接**
 * （上游 issue KTOR-694；本仓实测：把 `sslConnector` 配在 CIO 上，它照样只在那个端口上讲明文 ——
 * 一个**静默降级**，正是这一批最不能有的东西）。`ktor-server-netty` 不在本仓的依赖里，而
 * `server/build.gradle.kts` 的文件头写着为什么选 CIO（纯 Kotlin 协程、没有 native 传输层依赖、
 * 体积与内存都小得多）。两条加起来的结论：**TLS 由 JDK 终结，明文回环给 CIO**。
 *
 * ## 它的形状（与 `stunnel` 同一种做法）
 *
 * ```
 *   浏览器 ──TLS──> [TlsFront: SSLServerSocket] ──明文 TCP──> [CIO: 127.0.0.1:<系统挑的端口>]
 * ```
 *
 * 转发是**字节级**的（不解析 HTTP）：HTTP/1.1 的 keep-alive、分块编码、SSE 长连接在这一层都是
 * 「一段字节流」，原样过去即可。两个必然的结果，也都是对的：
 *
 * - Ktor 看到的是 `http`（`call.request.origin.scheme`）：所以「这一个是 HTTPS」这件事**不能**问请求，
 *   只能问配置（`ServeConfig.tls` 非 null）—— cookie 的 `Secure` 就是这么定的
 *   （[AccessToken.setCookieHeader]）。
 * - 明文监听**只绑 `127.0.0.1`**，且端口由系统挑：`--host 0.0.0.0 --https` 时对外只有 TLS 那一个口，
 *   明文那一份连同一台机器的别人都连不上（这一层不因为开了 HTTPS 而多出暴露面）。
 *
 * ## 生命周期
 *
 * [start] 起两条监听（内层 CIO + 前端），[close] 一起收掉；[port] 是**真正**在对外服务的端口
 * （`--port 0` 时那是系统挑的）。生产与测试都走这一个入口 —— 测试验的就是用户跑的那条路。
 */
internal class ServeHttps private constructor(
    private val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
    private val front: TlsFront,
) : AutoCloseable {

    /** 真正在对外服务的端口（HTTPS 那一个）。 */
    val port: Int get() = front.boundPort

    override fun close() {
        front.close()
        server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
    }

    companion object {
        /** 关停的口径与 `:server:test` 既有的那几处一致（先宽限、再超时）。 */
        private const val GRACE_MILLIS = 100L
        private const val TIMEOUT_MILLIS = 500L

        /**
         * 起一个 HTTPS 形态的 serve。
         *
         * 明文那一层**永远**绑 [ServeConfig.DEFAULT_HOST]（回环）与端口 `0`（系统挑）——
         * 它就是前端与 Ktor 之间那条进程内通道，不该、也不需要对外。
         *
         * @param sslContext 由 [ServeTls.prepare] 建好的上下文（证书/密码的报错都在它那一步发生）
         * @throws IllegalArgumentException [ServeConfig.tls] 是 null（调用方该走 [serveServer]）
         * @throws IOException 外部那个 TLS 端口绑不上（端口被占）—— 这时内层明文监听已经收掉了
         */
        fun start(
            config: ServeConfig,
            accessToken: String,
            session: ServeSession,
            sslContext: SSLContext,
        ): ServeHttps {
            requireNotNull(config.tls) { "ServeHttps 只用于 HTTPS 形态（ServeConfig.tls 非 null）" }
            val inner = serveServer(
                config.copy(host = ServeConfig.DEFAULT_HOST, port = 0),
                accessToken,
                session,
            )
            inner.start(wait = false)
            val backendPort = runBlocking { inner.engine.resolvedConnectors().single().port }
            val front = TlsFront(
                host = config.host,
                port = config.port,
                sslContext = sslContext,
                backendPort = backendPort,
            )
            try {
                front.start()
            } catch (e: IOException) {
                // 绑不上外部端口 ⇒ 把已经起来的明文监听一起收掉，别留一个半开的实例
                inner.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
                throw e
            }
            return ServeHttps(inner, front)
        }
    }
}

/**
 * TLS 前端：JDK 的 [SSLServerSocket] 终结 TLS，然后把**字节**原样转发给回环上的明文监听。
 *
 * 这里刻意不解析 HTTP（不读请求行、不看 header）：一旦它开始理解协议，就得跟着 HTTP/1.1 的
 * 分块编码、升级、SSE 心跳一起演进 —— 那是把一个「转发」写成第二个 HTTP 实现。字节级转发没有
 * 这些事：它只保证「两边都关对了」。
 *
 * @param host 对外监听的地址（`--host`）
 * @param port 对外监听的端口（`--port`；`0` = 系统挑）
 * @param sslContext 证书在手的那份上下文（[ServeTls.prepare]）
 * @param backendPort 明文那一层的端口（[ServeHttps] 从 CIO 引擎问出来的那个）
 * @param backendHost 明文那一层的地址（默认回环 —— 只有本进程会去连它）
 */
internal class TlsFront(
    private val host: String,
    private val port: Int,
    private val sslContext: SSLContext,
    private val backendPort: Int,
    private val backendHost: String = ServeConfig.DEFAULT_HOST,
) : AutoCloseable {

    private val serverSocket: ServerSocket = sslContext.serverSocketFactory.createServerSocket()
    private val connections: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    /** 接受连接的那条线程（守护线程：进程退出不靠它活着，测试忘了关它也不会吊住 JVM）。 */
    private var acceptThread: Thread? = null

    @Volatile
    private var closed = false

    /** 真正绑上的端口（`0` 时是系统挑的那个）。 */
    val boundPort: Int get() = serverSocket.localPort

    /** 绑定并开始接受连接；绑不上就抛 [IOException]（端口被占）。 */
    fun start() {
        serverSocket.bind(InetSocketAddress(host, port))
        acceptThread = Thread(::acceptLoop, "serve-tls-accept").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        // 活着的连接一起断：不然对面那条 copy 线程会一直阻塞在读上（测试收尾时会看出来）
        for (socket in connections.toList()) closeQuietly(socket)
        connections.clear()
        runCatching { acceptThread?.join(CLOSE_JOIN_MILLIS) }
    }

    private fun acceptLoop() {
        while (!closed) {
            val client = try {
                serverSocket.accept()
            } catch (e: IOException) {
                // 关停时 accept 会以 SocketException 从阻塞里退出来 —— 那是正常退出；其余情况继续等
                if (closed) return else continue
            }
            connections += client
            Thread.ofVirtual().name("serve-tls-relay").start {
                try {
                    relay(client)
                } catch (e: IOException) {
                    // 一条连接出问题（TLS 握手失败、客户端半路跑了）只影响这一条
                } finally {
                    connections -= client
                    closeQuietly(client)
                }
            }
        }
    }

    /** 一条连接：连上后端，两个方向各一条虚拟线程对拷（成本可忽略，JDK 21 的虚拟线程）。 */
    private fun relay(client: Socket) {
        Socket(backendHost, backendPort).use { upstream ->
            client.tcpNoDelay = true
            upstream.tcpNoDelay = true
            val up = Thread.ofVirtual().name("serve-tls-up").start { copy(client, upstream) }
            val down = Thread.ofVirtual().name("serve-tls-down").start { copy(upstream, client) }
            up.join()
            down.join()
        }
    }

    /**
     * 把 [from] 上读到的字节写给 [to]，直到 [from] 说完了（EOF）。
     *
     * 半边关闭**如实传下去**（`shutdownOutput`）而不是直接关掉两端：HTTP/1.1 的
     * `Connection: close`、以及「客户端说完就等响应」这类形态都靠它才能正常收尾。
     * 出错时反过来把**两端都关掉** —— 对面那条线程正阻塞在读上，关掉它才会退出来。
     */
    private fun copy(from: Socket, to: Socket) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                // 小响应立刻发出去（本端不缓冲，这一句是不变量：转发不许攒着）
                output.flush()
            }
            runCatching { to.shutdownOutput() }
        } catch (e: IOException) {
            closeQuietly(from)
            closeQuietly(to)
        }
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }

    private companion object {
        /** 8 KiB：一条连接 16 KiB 两个方向 —— 一个人的浏览器的量级，不值得为此调参。 */
        const val BUFFER_BYTES = 8 * 1024

        const val CLOSE_JOIN_MILLIS = 500L
    }
}
