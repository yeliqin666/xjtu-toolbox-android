package com.xjtu.toolbox

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * 假上游**对外的那个端口**：一个裸 TCP 前置 + 两个 `com.sun.net.httpserver` 后端（明文 / TLS）。
 *
 * ## 为什么不能只有一个 `HttpServer`（假上游原来那样）
 *
 * 原来假上游自己就是「代理」：走代理时请求行是 absolute-form，`requestURI.host` 就是目标域名。
 * 这一套对 **http** 站点成立（图书馆座位系统 `rg.lib.xjtu.edu.cn:8086`、统一认证都是 http），
 * 对 **https** 站点不成立 —— okhttp 对 https 先说 `CONNECT host:443 HTTP/1.1`，隧道打通后
 * 才在隧道里做 TLS。`com.sun.net.httpserver` 不认 CONNECT（实测：客户端在隧道里读不到响应，
 * 10 秒后 `SocketTimeoutException`）。于是：
 *
 * ```
 *   客户端 ──TCP──▶ [前置：裸 ServerSocket]  ── CONNECT tyxylp…:443 ──▶ [HttpsServer + 自签证书]
 *                        │                                              （同一张 host 分派表）
 *                        └────────── 其余（absolute-form http）─────────▶ [HttpServer]
 * ```
 *
 * 两个后端跑**同一个** `handler`：分派表只有一份，https 与 http 的差别只在传输。
 *
 * ## 自签证书与信任库（[installTrustStore]）
 *
 * 证书由 `keytool` 现生成（`:testkit` 只用 JDK，不加任何依赖），SAN 就是 [httpsHost]。
 * 客户端要信任它，就得在 `javax.net.ssl.trustStore` 上指一份「系统 cacerts 的副本 + 这枚证书」
 * ——**只增加**一个受信任的主机，其余真实站点照旧。
 *
 * ⚠️ 它必须在**建任何 `OkHttpClient` 之前**装好：`OkHttpClient.Builder.build()` 那一刻就把
 * 平台默认的 trust manager 抄进客户端（实测：晚一步就 `PKIX path building failed`，
 * 而且 `SSLContext.setDefault` 也救不回来 —— okhttp 抄的是 trust manager 实例，不是默认上下文）。
 * 所以它是 `FakeCampusProxy.installFakeUpstreams()` 的两件前置之一。
 */
class FakeUpstreamFront(
    /** 明文与 TLS 两条入口共用的那一张 host 分派表。 */
    private val handler: (HttpExchange) -> Unit,
    /** 用自签证书扮演的那个 https 域名 —— CONNECT 只认它，别的目标响亮地 502。 */
    private val httpsHost: String,
) : AutoCloseable {

    private val plain: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val tls: HttpsServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val front: ServerSocket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val threads: ExecutorService = Executors.newCachedThreadPool()

    @Volatile
    private var running = false

    /** 对外端口（`start()` 之后有效；`UpstreamSelector` 指向的就是它）。 */
    val port: Int get() = front.localPort

    fun start(): FakeUpstreamFront {
        plain.createContext("/", handler)
        plain.executor = threads
        plain.start()
        tls.setHttpsConfigurator(HttpsConfigurator(FakeUpstreamTls.sslContext(httpsHost)))
        tls.createContext("/", handler)
        tls.executor = threads
        tls.start()
        running = true
        threads.submit(::acceptLoop)
        return this
    }

    override fun close() {
        running = false
        runCatching { front.close() }
        plain.stop(0)
        tls.stop(0)
        threads.shutdownNow()
    }

    private fun acceptLoop() {
        while (running) {
            val client = try {
                front.accept()
            } catch (_: Exception) {
                return // close() 关掉 listen socket 就是这个结果
            }
            threads.submit { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        try {
            val head = readHead(client.getInputStream())
            if (head == null) {
                client.close() // 对端什么都没发就关了
                return
            }
            when {
                head.toString(Charsets.ISO_8859_1).startsWith("CONNECT ") -> tunnelToTls(client, head)
                else -> tunnelToPlain(client, head)
            }
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }

    /** CONNECT：只认 [httpsHost]（别的目标 502，别让「连错了域名」看起来像成功）。 */
    private fun tunnelToTls(client: Socket, head: ByteArray) {
        val target = head.toString(Charsets.ISO_8859_1).substringAfter("CONNECT ").substringBefore(' ').substringBefore(':')
        if (!target.equals(httpsHost, ignoreCase = true)) {
            respondAndClose(client, "502 Bad Gateway", "fake upstream only plays https://$httpsHost (CONNECT $target)")
            return
        }
        client.getOutputStream().apply {
            write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            flush()
        }
        tunnel(client, Socket("127.0.0.1", tls.address.port))
    }

    /** 明文：请求行是 absolute-form（走代理时 okhttp 就是这么发的），补回去程的头之后纯透传。 */
    private fun tunnelToPlain(client: Socket, head: ByteArray) {
        val backend = Socket("127.0.0.1", plain.address.port)
        backend.getOutputStream().apply {
            write(head)
            flush()
        }
        tunnel(client, backend)
    }

    /** 双向透传；任一方向结束就两边一起关（两个线程各自 copy 一个方向）。 */
    private fun tunnel(client: Socket, backend: Socket) {
        threads.submit { copy(client, backend) }
        copy(backend, client)
        runCatching { client.close() }
        runCatching { backend.close() }
    }

    private fun copy(from: Socket, to: Socket) {
        try {
            val buffer = ByteArray(16 * 1024)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
            // 对端关掉/被 close 都走这里：下面统一收尾
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }

    /**
     * 读一个请求的头（读到空行为止）。只读到头的末尾，**不越过**：POST 的 body 之后靠透传。
     * 对端直接关掉（什么都没读到）返回 null。
     */
    private fun readHead(input: InputStream): ByteArray? {
        val head = ByteArrayOutputStream()
        var state = 0
        while (head.size() < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return head.toByteArray().takeIf { it.isNotEmpty() }
            head.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return head.toByteArray()
        }
        return head.toByteArray()
    }

    private fun respondAndClose(client: Socket, status: String, body: String) {
        val bytes = body.toByteArray()
        client.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
        runCatching { client.close() }
    }

    private companion object {
        const val MAX_HEAD_BYTES = 64 * 1024
    }
}

/**
 * 自签证书那一套（keytool + `javax.net.ssl`），按域名缓存一份。
 *
 * 一个进程里只会为每个域名生成一次：生成要走 `keytool`（约 1 秒），而每个测试类都要用同一个
 * 生成结果 —— 信任库与 TLS 服务端必须来自**同一对**密钥。
 */
internal object FakeUpstreamTls {

    private const val ALIAS = "fake-upstream"
    private const val STORE_PASS = "fake-campus"

    private val materials = ConcurrentHashMap<String, Material>()

    fun sslContext(host: String): SSLContext = material(host).sslContext

    /**
     * 把 [host] 的自签证书装进进程的信任库（系统 cacerts 的副本 + 这枚证书）。
     * 幂等；**必须在建任何 `OkHttpClient` 之前**调（见 [FakeUpstreamFront] 的类 KDoc）。
     */
    fun installTrustStore(host: String) {
        val trust = material(host).trustStore
        System.setProperty("javax.net.ssl.trustStore", trust.toString())
        System.setProperty("javax.net.ssl.trustStorePassword", TRUST_STORE_PASS)
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")
    }

    private fun material(host: String): Material = materials.getOrPut(host) { Material(host) }

    /** 系统信任库的口令（JDK 的 cacerts 就是它；我们拷的是副本，不动原件）。 */
    private const val TRUST_STORE_PASS = "changeit"

    private class Material(private val host: String) {
        private val dir: Path = Files.createTempDirectory("fake-upstream-tls")
        private val keyStore: Path = dir.resolve("server.p12")

        /** 「系统 cacerts 的副本 + 这枚自签证书」：只增加一个受信任的主机。 */
        val trustStore: Path = dir.resolve("trust.p12")

        init {
            keytool(
                "-genkeypair", "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-dname", "CN=$host", "-ext", "SAN=dns:$host", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", STORE_PASS, "-keypass", STORE_PASS,
            )
            val cert = dir.resolve("server.crt")
            keytool(
                "-exportcert", "-alias", ALIAS, "-keystore", keyStore.toString(),
                "-storepass", STORE_PASS, "-file", cert.toString(),
            )
            Files.copy(
                Paths.get(System.getProperty("java.home"), "lib", "security", "cacerts"),
                trustStore,
                StandardCopyOption.REPLACE_EXISTING,
            )
            keytool(
                "-importcert", "-noprompt", "-trustcacerts", "-alias", ALIAS,
                "-file", cert.toString(), "-keystore", trustStore.toString(),
                "-storepass", TRUST_STORE_PASS, "-storetype", "PKCS12",
            )
        }

        val sslContext: SSLContext = run {
            val store = KeyStore.getInstance("PKCS12")
            Files.newInputStream(keyStore).use { store.load(it, STORE_PASS.toCharArray()) }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(store, STORE_PASS.toCharArray()) }
            SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        }

        private fun keytool(vararg args: String) {
            val keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool").toFile()
            check(keytool.canExecute()) { "假上游要生成自签证书，但 $keytool 不存在（测试用的 JDK 得带 keytool）" }
            val process = ProcessBuilder(listOf(keytool.absolutePath) + args).redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().decodeToString()
            val code = process.waitFor()
            check(code == 0) { "keytool ${args.first()} 失败（退出码 $code）：$output" }
        }
    }
}
