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

/** HTTPS 形态的装配：CIO 的明文监听只绑回环（本进程内部用），[TlsFront] 对`config.host:config.port` 讲 TLS。 */
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
        /** 关停：先宽限、再超时。 */
        private const val GRACE_MILLIS = 100L
        private const val TIMEOUT_MILLIS = 500L

        /** 起一个 HTTPS 形态的 serve。 */
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

/** TLS 前端：JDK 的 [SSLServerSocket] 终结 TLS，然后把字节原样转发给回环上的明文监听。 */
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

    /** 把 [from] 上读到的字节写给 [to]，直到 [from] 说完了（EOF）。 */
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
