package com.xjtu.toolbox.server

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import java.io.File
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * serve 模式**内置 HTTPS**（`docs/desktop-port-plan.md` §16.1 的 D11 / §16.3 批④）的验收，
 * 契约见 `docs/api-contract.md` §3 / §3.2。
 *
 * ## 两条同样重要的判据
 *
 * 1. **不给新参数 = 明文，且逐字不变**：用户在用的那台 `0.0.0.0:40961` 的启动方式不能被这一批碰到
 *    —— 所以这里既有「默认值一个都不变」的断言，也有「明文那一档的 cookie 一个属性都不多」的断言。
 * 2. **给了 `--https` = 真 HTTPS，而且不许静默回落成明文**：真 socket（TLS 握手 + 建连 + 收发），
 *    测试侧**只信那一张自签证书**（不是 trust-all：那样连「证书没生效」都验不出来）；证书缺了、
 *    路径不存在、密码不对、文件不是证书库，四种情况各有一句明确报错。
 *
 * ## 手法与纪律
 *
 * - 起实例走的是**生产入口** [ServeHttps.start]（不是测试自造的等价物）—— 用户跑的就是这一条路；
 * - 端口一律 `0`（系统挑）：绝不抢 8123 / 40961（那两个端口上可能正跑着用户在用的 serve）；
 * - 数据根一律临时目录（`dataRootOverride`）：证书、密码、令牌都不落用户真实目录
 *   （证书默认路径就是「数据根 + `tls/`」，所以换根等于把整包材料一起隔离）；
 * - 明文那一档由 [ServeServerTest] 钉着（默认只绑回环、令牌闸门、静态托管），这里只管
 *   「明文形态的口径没有被 HTTPS 这条支路改动」。
 */
class ServeHttpsTest {

    private var started: ServeHttps? = null

    @AfterTest
    fun stopServer() {
        // 每条测试自己起一个真实例（临时端口），跑完必须关掉 —— 否则 JVM 退不掉
        started?.close()
        started = null
        dataRootOverride = null
    }

    // ── ① 默认：明文，一字不改 ────────────────────────────────────────────────────

    @Test
    fun `不给新参数就是明文，默认值与 cookie 逐字不变`() {
        val root = Files.createTempDirectory("xjtu-serve-https-default").toFile()
        dataRootOverride = root

        // 默认值就是对外承诺（契约 §3）：D11 一个都不许动
        val defaults = ServeConfig.parse(emptyArray())
        assertNull(defaults.tls, "不给 --https 就是明文形态")
        assertEquals("127.0.0.1", defaults.host)
        assertEquals(8123, defaults.port)
        assertEquals("web/build/dist/wasmJs/productionExecutable", defaults.distDir.path)
        assertFalse(defaults.exposed)

        // 证书默认路径：**与访问令牌同一个数据根**（`tls/` 那一层），而且是算路径、不建目录
        assertEquals(File(File(root, "tls"), "serve.p12"), ServeConfig.defaultTlsKeyStore())
        assertFalse(File(root, "tls").exists(), "明文形态不该碰证书目录")

        // 明文那一档的 cookie：一个属性都不多（`Secure` 只是 HTTPS 形态的收紧）
        val token = AccessToken.newToken()
        assertEquals(
            "${AccessToken.COOKIE_NAME}=$token; Path=/; HttpOnly; SameSite=Lax",
            AccessToken.setCookieHeader(token),
        )
    }

    // ── ② 给了 --https：真 HTTPS 往返 ──────────────────────────────────────────────

    @Test
    fun `给了 https 就是 HTTPS 监听，并且能完成一次真往返`() {
        val root = Files.createTempDirectory("xjtu-serve-https-roundtrip").toFile()
        dataRootOverride = root
        val config = ServeConfig.parse(arrayOf("--https", "--port", "0"))
        val tls = assertNotNull(config.tls, "--https ⇒ HTTPS 形态")
        assertTrue(tls.generateIfMissing, "没给 --cert ⇒ 默认路径上缺证书就生成自签")
        assertEquals(File(File(root, "tls"), "serve.p12").path, tls.keyStore.path)

        val material = ServeTls.prepare(tls, config.host)
        assertTrue(material.generated, "第一次启动：证书库是刚生成的自签")

        val token = AccessToken.newToken()
        val https = startHttps(config, token, material)
        assertTrue(https.port > 0, "--port 0 ⇒ 系统挑一个端口")
        val client = httpsClient(material)

        // ① 免令牌端点：真 TLS 往返（握手 + 请求 + 响应），并如实报「没会话」
        val status = get(client, "https://127.0.0.1:${https.port}$API_STATUS_PATH")
        assertEquals(200, status.statusCode())
        assertContains(status.headers().firstValue("content-type").orElse(""), "application/json")
        assertContains(status.body(), "\"authenticated\":false")

        // ② 令牌闸门还在（HTTPS 不等于免鉴权）：不带令牌 401、对令牌过闸门后 404（没实现的端点）
        assertEquals(401, get(client, "https://127.0.0.1:${https.port}/api/payment-code").statusCode())
        assertEquals(404, get(client, "https://127.0.0.1:${https.port}/api/payment-code", token).statusCode())

        // ③ 静态托管也走同一条路（产物目录不存在时就如实 404/文案，不是 TLS 的问题）
        assertEquals(404, get(client, "https://127.0.0.1:${https.port}/missing.js").statusCode())
    }

    @Test
    fun `HTTPS 那个端口上发明文 HTTP 必须失败（不静默回落）`() {
        val root = Files.createTempDirectory("xjtu-serve-https-nofallback").toFile()
        dataRootOverride = root
        val config = ServeConfig.parse(arrayOf("--https", "--port", "0"))
        val material = ServeTls.prepare(assertNotNull(config.tls), config.host)
        val https = startHttps(config, material = material)
        assertTrue(https.port > 0)

        // TLS 那一层会把一段明文 HTTP 当握手垃圾拒掉 ⇒ 客户端拿不到任何响应（更不该拿到 200）
        val plain = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(ProxySelector.of(null))
            .build()
        val outcome = runCatching { get(plain, "http://127.0.0.1:${https.port}$API_STATUS_PATH") }
        assertTrue(
            outcome.isFailure,
            "HTTPS 端口上发明文必须失败，实际拿到了：${outcome.getOrNull()?.statusCode()}",
        )
    }

    @Test
    fun `HTTPS 形态下令牌换 cookie 带 Secure，明文那一档不带`() {
        val root = Files.createTempDirectory("xjtu-serve-https-secure-cookie").toFile()
        dataRootOverride = root
        val config = ServeConfig.parse(arrayOf("--https", "--port", "0"))
        val material = ServeTls.prepare(assertNotNull(config.tls), config.host)
        val token = AccessToken.newToken()
        val https = startHttps(config, token, material)

        val response = get(httpsClient(material), "https://127.0.0.1:${https.port}$SESSION_PATH", bearer = token)
        assertEquals(200, response.statusCode())
        val setCookie = response.headers().firstValue("set-cookie").orElse("")
        assertContains(setCookie, "${AccessToken.COOKIE_NAME}=$token")
        // 换 cookie 的语义没变（仍是 HttpOnly + SameSite=Lax + Path=/），只多一个 Secure
        assertContains(setCookie, "HttpOnly")
        assertContains(setCookie, "SameSite=Lax")
        assertContains(setCookie, "Path=/")
        assertContains(setCookie, "Secure")
        // 明文形态（默认参数）仍然是那一行**逐字**——收紧只发生在 HTTPS 形态
        assertEquals(
            "${AccessToken.COOKIE_NAME}=$token; Path=/; HttpOnly; SameSite=Lax",
            AccessToken.setCookieHeader(token, secure = false),
        )
    }

    @Test
    fun `一条 TLS 连接上连打两发（字节转发不破坏 keep-alive）`() {
        val root = Files.createTempDirectory("xjtu-serve-https-keepalive").toFile()
        dataRootOverride = root
        val config = ServeConfig.parse(arrayOf("--https", "--port", "0"))
        val material = ServeTls.prepare(assertNotNull(config.tls), config.host)
        val https = startHttps(config, material = material)

        val socket = clientContext(material).socketFactory.createSocket("127.0.0.1", https.port)
        socket.use { raw ->
            // 超时兜底：真出问题时宁可失败，也不让测试吊在那里
            raw.soTimeout = SOCKET_TIMEOUT_MS
            val output = raw.getOutputStream()
            val input = raw.getInputStream().bufferedReader()
            repeat(2) { index ->
                output.write(
                    "GET $API_STATUS_PATH HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n"
                        .toByteArray(),
                )
                output.flush()
                val statusLine = readHttpStatusLine(input)
                assertTrue(statusLine.startsWith("HTTP/1.1 200"), "第 ${index + 1} 发必须复用同一条连接，实际：$statusLine")
                readHttpResponseBody(input)
            }
        }
    }

    // ── ③ 自签生成这条路 ──────────────────────────────────────────────────────────

    @Test
    fun `自签证书能生成一条可用路径，而且第二次启动复用同一张`() {
        val root = Files.createTempDirectory("xjtu-serve-https-selfsigned").toFile()
        dataRootOverride = root
        val settings = TlsSettings(keyStore = ServeConfig.defaultTlsKeyStore(), generateIfMissing = true)

        val first = ServeTls.prepare(settings, "127.0.0.1")
        assertTrue(first.generated)
        assertTrue(settings.keyStore.isFile, "证书库要真落盘：${settings.keyStore}")

        // 密码文件就在库旁边，两个文件都只有本用户可读写（与令牌那份 serve.properties 同一口径）
        val passwordFile = ServeTls.passwordFile(settings.keyStore)
        assertTrue(passwordFile.isFile, "密码要落盘（命令行上不接受密码）：$passwordFile")
        for (file in listOf(settings.keyStore, passwordFile)) {
            if (file.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file.toPath()),
                    "$file 必须是 0600",
                )
            }
        }

        // 库是能读的：别名在、证书是自签的、名字对得上、有效期是往后十年那一档
        val certificate = ServeTls.certificate(settings.keyStore, settings.alias)
        certificate.checkValidity()
        assertContains(certificate.subjectX500Principal.name, "CN=localhost")
        val san = certificate.subjectAlternativeNames.toString()
        assertContains(san, "localhost")
        assertContains(san, "127.0.0.1")
        assertEquals("dns:localhost,ip:127.0.0.1", ServeTls.sanOf("127.0.0.1"))
        assertEquals("dns:localhost,ip:127.0.0.1,ip:192.168.1.5", ServeTls.sanOf("192.168.1.5"))
        assertEquals("dns:localhost,ip:127.0.0.1", ServeTls.sanOf("0.0.0.0"))

        // 第二次启动：**不**重生成（每次启动换一张证书会让浏览器每次都要重新接受一次）
        val fingerprint = fingerprintOf(certificate)
        val second = ServeTls.prepare(settings, "127.0.0.1")
        assertFalse(second.generated, "库在了就沿用，不重新签")
        assertEquals(fingerprint, fingerprintOf(ServeTls.certificate(settings.keyStore, settings.alias)))
    }

    // ── ④ 参数与材料的四种失败：一律明确报错 ──────────────────────────────────────

    @Test
    fun `证书参数离了 https 就是明确报错（不静默忽略）`() {
        val withoutHttps = assertFailsWith<IllegalArgumentException> {
            ServeConfig.parse(arrayOf("--cert", "/tmp/does-not-matter.p12"))
        }
        assertContains(withoutHttps.message.orEmpty(), "--https")
        val aliasWithoutHttps = assertFailsWith<IllegalArgumentException> {
            ServeConfig.parse(arrayOf("--cert-alias", "other"))
        }
        assertContains(aliasWithoutHttps.message.orEmpty(), "--https")
        // --https 是开关，不带值
        assertFailsWith<IllegalArgumentException> { ServeConfig.parse(arrayOf("--https=1")) }
        assertFailsWith<IllegalArgumentException> { ServeConfig.parse(arrayOf("--cert")) }

        // 带上 --https 就合法：用户给的库**只读**（缺了那是 ServeTls 的报错，不在这里生成）
        val withHttps = assertNotNull(ServeConfig.parse(arrayOf("--https", "--cert", "/tmp/mine.p12", "--cert-alias", "other")).tls)
        assertEquals(File("/tmp/mine.p12"), withHttps.keyStore)
        assertEquals("other", withHttps.alias)
        assertFalse(withHttps.generateIfMissing, "--cert 指过来的库不做自签生成")
    }

    @Test
    fun `证书库不存在 密码没有 格式不对 别名不对都是明确报错`() {
        val root = Files.createTempDirectory("xjtu-serve-https-errors").toFile()
        dataRootOverride = root

        // ① --cert 指向一个不存在的路径：报错，而且**不**在那儿捂着生成
        val missing = File(root, "missing.p12")
        val absent = assertFailsWith<ServeTlsException> {
            ServeTls.prepare(TlsSettings(keyStore = missing, generateIfMissing = false), "127.0.0.1")
        }
        assertContains(absent.message.orEmpty(), missing.path)
        assertFalse(missing.exists(), "路径不存在不许捂着生成，更不许回落成明文")

        // 先有一份**能读**的库（自签），后面几条都拿它当对照
        val settings = TlsSettings(keyStore = ServeConfig.defaultTlsKeyStore(), generateIfMissing = true)
        val good = ServeTls.prepare(settings, "127.0.0.1")

        // ② 别名不在库里：消息要点出别名与库里有的那几个
        val wrongAlias = assertFailsWith<ServeTlsException> {
            ServeTls.prepare(TlsSettings(keyStore = good.keyStore, alias = "nope"), "127.0.0.1")
        }
        assertContains(wrongAlias.message.orEmpty(), "nope")
        assertContains(wrongAlias.message.orEmpty(), ServeConfig.DEFAULT_TLS_ALIAS)

        // ③ 文件在、密码也有、但它根本不是证书库（PKCS12 与 JKS 都读不了）
        val garbage = File(root, "garbage.p12")
        garbage.writeText("这不是一个证书库\n")
        ServeTls.passwordFile(garbage).writeText("whatever\n")
        val notAKeyStore = assertFailsWith<ServeTlsException> {
            ServeTls.prepare(TlsSettings(keyStore = garbage), "127.0.0.1")
        }
        assertContains(notAKeyStore.message.orEmpty(), garbage.path)

        // ④ 密码文件没了（也没设环境变量）：消息里要给得出下一步 —— 环境变量叫什么
        val passwordFile = ServeTls.passwordFile(good.keyStore)
        assertTrue(passwordFile.delete())
        val noPassword = assertFailsWith<ServeTlsException> {
            ServeTls.prepare(TlsSettings(keyStore = good.keyStore), "127.0.0.1")
        }
        assertContains(noPassword.message.orEmpty(), ServeTls.ENV_PASSWORD)
    }

    // ── 测试脚手架 ────────────────────────────────────────────────────────────────

    /**
     * 起一个 HTTPS 形态的 serve（生产入口 [ServeHttps.start]）。
     *
     * 两个进程级前置与 [ServeServerTest] 同口径：[FakeCampusProxy.installFakeUpstreams] 必须在
     * 建 [ServeSession] **之前**（它一构造就把当时的 trust manager 与 ProxySelector 抄进客户端），
     * 凭据存储要清一遍（同一个 JVM 里先跑过的测试类会留下登录态，`/api/status` 就不再说「没会话」了）。
     */
    private fun startHttps(
        config: ServeConfig,
        token: String = AccessToken.newToken(),
        material: ServeTls.TlsMaterial,
    ): ServeHttps {
        FakeCampusProxy.installFakeUpstreams()
        wipeSecureStore(JvmCredentialStore.FILE_NAME)
        return ServeHttps.start(config, token, ServeSession(), material.context).also { started = it }
    }

    /**
     * 只信**这一张**自签证书的客户端。
     *
     * 不是 trust-all：那样「证书没生效 / 用了别的一张」也能过，等于把这一批要验的东西绕过去。
     * 顺带必须显式「不走代理」—— 假上游装的是进程级 ProxySelector，它会把每个连接都指到假代理。
     */
    private fun httpsClient(material: ServeTls.TlsMaterial): HttpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .sslContext(clientContext(material))
            .proxy(ProxySelector.of(null))
            .build()

    /** 客户端那一半的 SSLContext：只信这一张自签证书（不是 trust-all）。 */
    private fun clientContext(material: ServeTls.TlsMaterial): SSLContext {
        val certificate = ServeTls.certificate(material.keyStore, material.alias)
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry(material.alias, certificate)
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trust)
        }
        return SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
    }

    private fun get(client: HttpClient, url: String, bearer: String? = null): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create(url)).GET().apply {
                bearer?.let { header("Authorization", "Bearer $it") }
            }.build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun fingerprintOf(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 5_000

        /** 手工读 HTTP：只认我们自己发出去的那两发请求（状态行 + 头 + `Content-Length` 的 body）。 */
        fun readHttpStatusLine(input: java.io.BufferedReader): String = input.readLine().orEmpty()

        fun readHttpResponseBody(input: java.io.BufferedReader) {
            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            repeat(contentLength) { input.read() }
        }
    }
}
