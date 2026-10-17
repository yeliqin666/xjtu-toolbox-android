package com.xjtu.toolbox

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.ByteArrayOutputStream
import java.io.File
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

/** 假上游对外的那个端口：一个裸 TCP 前置 + 两个 `com.sun.net.httpserver` 后端（明文 / TLS）。 */
class FakeUpstreamFront(
    /** 明文与 TLS 两条入口共用的那一张 host 分派表。 */
    private val handler: (HttpExchange) -> Unit,
    /** 用自签证书扮演的那些 https 域名 —— CONNECT 只认它们，别的目标响亮地 502。 */
    private val httpsHosts: Set<String>,
) : AutoCloseable {

    private val plain: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val tls: HttpsServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val front: ServerSocket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val threads: ExecutorService = Executors.newCachedThreadPool()

    @Volatile
    private var running = false

    /** 对外端口（`start()` 之后有效；`FakeUpstreamProxySelector` 指向的就是它）。 */
    val port: Int get() = front.localPort

    fun start(): FakeUpstreamFront {
        plain.createContext("/", handler)
        plain.executor = threads
        plain.start()
        tls.setHttpsConfigurator(HttpsConfigurator(FakeUpstreamTls.sslContext(httpsHosts)))
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

    /** CONNECT：只认 [httpsHosts]（别的目标 502，别让「连错了域名」看起来像成功）。 */
    private fun tunnelToTls(client: Socket, head: ByteArray) {
        val target = head.toString(Charsets.ISO_8859_1).substringAfter("CONNECT ").substringBefore(' ').substringBefore(':')
        if (httpsHosts.none { it.equals(target, ignoreCase = true) }) {
            respondAndClose(
                client,
                "502 Bad Gateway",
                "fake upstream only plays ${httpsHosts.sorted().joinToString(",") { "https://$it" }} (CONNECT $target)",
            )
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

    /** 读一个请求的头（读到空行为止）。 */
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

/** 自签证书那一套（keytool + `javax.net.ssl`），按域名集合缓存一份。 */
internal object FakeUpstreamTls {

    private const val ALIAS = "fake-upstream"
    private const val STORE_PASS = "fake-campus"

    private val materials = ConcurrentHashMap<String, Material>()

    fun sslContext(hosts: Set<String>): SSLContext = material(hosts).sslContext

    /** 把 [hosts] 那枚自签证书装进进程的信任库（系统 cacerts 的副本 + 这枚证书）。 */
    fun installTrustStore(hosts: Set<String>) {
        val trust = material(hosts).trustStore
        System.setProperty("javax.net.ssl.trustStore", trust.toString())
        System.setProperty("javax.net.ssl.trustStorePassword", TRUST_STORE_PASS)
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")
    }

    private fun material(hosts: Set<String>): Material =
        materials.getOrPut(hosts.map { it.lowercase() }.sorted().joinToString(",")) { Material(hosts.sorted()) }
    /** 系统信任库的口令（JDK 的 cacerts 就是它；我们拷的是副本，不动原件）。 */
    private const val TRUST_STORE_PASS = "changeit"

    private class Material(private val hosts: List<String>) {
        private val dir: Path = Files.createTempDirectory("fake-upstream-tls")
        private val keyStore: Path = dir.resolve("server.p12")

        /** 「系统 cacerts 的副本 + 这枚自签证书」：只增加这几个受信任的主机。 */
        val trustStore: Path = dir.resolve("trust.p12")

        init {
            keytool(
                "-genkeypair", "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-dname", "CN=${hosts.first()}",
                "-ext", "SAN=${hosts.joinToString(",") { "dns:$it" }}", "-storetype", "PKCS12",
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
            val keytool = Paths.get(System.getProperty("java.home"), "bin", if (File.separatorChar == '\\') "keytool.exe" else "keytool").toFile()
            check(keytool.canExecute()) { "假上游要生成自签证书，但 $keytool 不存在（测试用的 JDK 得带 keytool）" }
            val process = ProcessBuilder(listOf(keytool.absolutePath) + args).redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().decodeToString()
            val code = process.waitFor()
            check(code == 0) { "keytool ${args.first()} 失败（退出码 $code）：$output" }
        }
    }
}
