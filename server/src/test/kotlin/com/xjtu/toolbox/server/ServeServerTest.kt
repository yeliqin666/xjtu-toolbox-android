package com.xjtu.toolbox.server

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** serve 模式默认安全配置的验收（契约见 `docs/api-contract.md` §3）。 */
class ServeServerTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        // 每条测试自己起一个真服务器（临时端口），跑完必须关掉 —— 否则 JVM 退不掉
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
        dataRootOverride = null
    }

    @Test
    fun `默认只监听回环地址`() {
        val defaults = ServeConfig.parse(emptyArray())
        assertEquals("127.0.0.1", defaults.host)
        assertEquals(8123, defaults.port)
        assertEquals("web/build/dist/wasmJs/productionExecutable", defaults.distDir.path)
        assertFalse(defaults.exposed)

        val harness = start(distDir = tempDist())
        assertEquals("127.0.0.1", harness.host, "默认必须只绑回环地址")

        // ③ 从本机非回环地址必须连不上（没有非回环地址时跳过）。
        val external = externalIpv4()
        if (external != null) {
            val refused = runCatching {
                Socket().use { it.connect(InetSocketAddress(external, harness.port), CONNECT_TIMEOUT_MS) }
            }
            assertTrue(
                refused.exceptionOrNull() is IOException,
                "绑定 127.0.0.1 时，从 $external 连过来必须失败，实际：$refused",
            )
        }
    }

    @Test
    fun `状态端点免令牌且不含身份信息`() {
        val harness = start(distDir = tempDist())

        // 不带任何令牌 —— §3.4：它是唯一免令牌端点
        val response = harness.get("/api/status")
        assertEquals(200, response.statusCode())
        assertContains(response.headers().firstValue("content-type").orElse(""), "application/json")

        // 裸对象（没有 {code,data} 信封），字段集合就是这几个 —— 多一个字段都要在这里过一遍
        val body = response.body().asJsonObject()
        assertEquals(
            setOf("authenticated", "uptimeSeconds"),
            body.keys,
            "§3.4：/api/status 只报「活着 + 有没有会话」，不许出现学号/姓名之类的身份字段",
        )
        assertEquals(false, body.getValue("authenticated").jsonPrimitive.boolean)
        assertTrue(body.getValue("uptimeSeconds").jsonPrimitive.content.toLong() >= 0)

        // 身份信息连**字符串**都不许出现在响应体里（学号/姓名的键名与典型值）
        for (forbidden in listOf("username", "name", "studentId", "学号", "姓名")) {
            assertFalse(response.body().contains(forbidden), "响应体里不该出现「$forbidden」：${response.body()}")
        }
    }

    @Test
    fun `api 令牌闸门`() {
        val token = AccessToken.newToken()
        val harness = start(distDir = tempDist(), token = token)

        val anonymous = harness.get("/api/anything")
        assertEquals(401, anonymous.statusCode())
        assertContains(anonymous.headers().firstValue("content-type").orElse(""), "application/json")
        val envelope = anonymous.body().asJsonObject()
        assertEquals(401, envelope.getValue("code").jsonPrimitive.content.toInt())
        assertTrue(envelope.getValue("message").jsonPrimitive.content.any { it.code in 0x4e00..0x9fff }, anonymous.body())

        // 错令牌 → 同样 401（不许因为「像令牌」就放行）
        assertEquals(401, harness.get("/api/anything", bearer = "not-the-token").statusCode())
        assertEquals(401, harness.get("/api/anything", bearer = token.dropLast(1)).statusCode())
        assertEquals(401, harness.get("/api/session").statusCode())

        // 对令牌 → 过闸门：拿一个还没实现的端点（`/api/payment-code`）当证据，过闸门后是 404。
        val allowed = harness.get("/api/payment-code", bearer = token)
        assertEquals(404, allowed.statusCode())
        assertEquals(404, allowed.body().asJsonObject().getValue("code").jsonPrimitive.content.toInt())

        // cookie 形态（契约 §3.2 的第二种）：令牌换 cookie 之后随请求带
        assertEquals(404, harness.get("/api/payment-code", cookie = token).statusCode())
        assertEquals(401, harness.get("/api/payment-code", cookie = "wrong").statusCode())

        // `/api/session*` 也已经挂上了（逐条形状在 `ServeSessionTest` 里）：过闸门就是 200 的信封
        assertEquals(200, harness.get("/api/session", bearer = token).statusCode())
        assertEquals(200, harness.get("/api/session", cookie = token).statusCode())

        // 免令牌端点不受令牌影响：带错令牌也照常 200（它不是「有条件免令牌」）
        assertEquals(200, harness.get("/api/status", bearer = "not-the-token").statusCode())
    }

    @Test
    fun `静态产物按 dist 托管且 api 优先`() {
        val dist = tempDist()
        File(dist, "app.js").writeText("console.log(1)\n")
        File(dist, "app.wasm").writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6d))
        // 产物目录里**故意**放一份同名的 api 路径：用来证明 /api/… 走的是闸门，不是静态
        File(dist, "api").mkdirs()
        File(dist, "api/secret.txt").writeText("decoy")
        File(dist, "composeResources").mkdirs()
        File(dist, "composeResources/index.html").writeText("<html>resources index</html>")

        val harness = start(distDir = dist)

        // / → index.html
        val index = harness.get("/")
        assertEquals(200, index.statusCode())
        assertContains(index.body(), "XJTU ToolBox 静态产物")
        assertContains(index.headers().firstValue("content-type").orElse(""), "text/html")

        // 目录请求同样落到它的 index.html
        assertContains(harness.get("/composeResources/").body(), "resources index")

        // Kotlin/Wasm 的产物必须是 application/wasm（mime 发错 = 托管 :web 白做）
        val wasm = harness.get("/app.wasm")
        assertEquals(200, wasm.statusCode())
        assertEquals("application/wasm", wasm.headers().firstValue("content-type").orElse(""))
        assertContains(harness.get("/app.js").headers().firstValue("content-type").orElse(""), "javascript")

        // webp 不能被发成 octet-stream
        File(dist, "badge.webp").writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00))
        assertEquals(
            "image/webp",
            harness.get("/badge.webp").headers().firstValue("content-type").orElse(""),
        )

        File(dist, "manifest.webmanifest").writeText("{}")
        assertEquals(
            "application/manifest+json",
            harness.get("/manifest.webmanifest").headers().firstValue("content-type").orElse(""),
        )

        // 找不到的文件 → 404
        assertEquals(404, harness.get("/missing.js").statusCode())

        // /api/… 优先于静态：产物目录里的 api/secret.txt 取不到，落在闸门上
        assertEquals(401, harness.get("/api/secret.txt").statusCode())
        assertEquals(200, harness.get("/api/status").statusCode())

        // 目录穿越挡死（编码过的 `..`：客户端不会替我们规范化掉）。同级那份 secret.txt 是**对照**：
        // 真穿出去就读得到它，所以这几条 404 才是「被挡住了」而不是「碰巧没这个文件」。
        for (traversal in listOf("/%2e%2e/secret.txt", "/%2e%2e%2fsecret.txt", "/..%2fsecret.txt")) {
            val response = harness.get(traversal)
            assertEquals(404, response.statusCode(), "$traversal 必须被挡住")
            assertFalse(response.body().contains("不该被读到"), "$traversal 读到了托管根之外的东西")
        }
    }

    @Test
    fun `令牌落盘且下次启动沿用同一枚`() {
        val first = Files.createTempDirectory("xjtu-serve-token-a").toFile()
        val second = Files.createTempDirectory("xjtu-serve-token-b").toFile()
        val storeFile = "serve.properties"
        try {
            // ── 第一次启动：文件还不存在 ⇒ 生成 + 落盘 ──
            dataRootOverride = first
            wipeSecureStore(AccessToken.STORE_NAME)
            val tokenA = AccessToken.loadOrCreate()

            val fileA = File(first, storeFile)
            assertTrue(fileA.isFile, "第一次启动必须把令牌落盘：$fileA")
            // 令牌形状：Base64URL、无填充（它要出现在命令行与 cookie 里）
            assertTrue(tokenA.matches(Regex("[A-Za-z0-9_-]{43}")), "令牌形状不对：$tokenA")
            // 落盘的**就是**那一枚（用纯 java.util.Properties 读，证明格式与键名是稳定的）
            assertEquals(tokenA, Properties().also { p -> fileA.inputStream().use { p.load(it) } }.getProperty(AccessToken.KEY))
            if (fileA.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(fileA.toPath()),
                )
            }

            fileA.copyTo(File(second, storeFile))
            wipeSecureStore(AccessToken.STORE_NAME)
            dataRootOverride = second
            val tokenB = AccessToken.loadOrCreate()

            assertEquals(tokenA, tokenB, "下一次启动必须沿用同一枚令牌（否则每次重启都要重新贴）")
            // 而且是**读**出来的，不是又生成了一枚覆盖上去：文件内容原样不变
            assertEquals(tokenA, File(second, storeFile).readText().let { raw ->
                Properties().also { p -> p.load(raw.reader()) }.getProperty(AccessToken.KEY)
            })
        } finally {
            dataRootOverride = null
            wipeSecureStore(AccessToken.STORE_NAME)
        }
    }

    @Test
    fun `参数解析`() {
        assertEquals("/tmp/x", ServeConfig.parse(arrayOf("--dist", "/tmp/x")).distDir.path)
        assertEquals(18234, ServeConfig.parse(arrayOf("--port=18234")).port)
        assertEquals("0.0.0.0", ServeConfig.parse(arrayOf("--host", "0.0.0.0")).host)
        assertTrue(ServeConfig.parse(arrayOf("--host", "0.0.0.0")).exposed)
        assertFalse(ServeConfig.parse(arrayOf("--host", "localhost")).exposed)
        // 认不得的参数要**响亮失败**，不能静默起在默认端口上
        assertFailsWith<IllegalArgumentException> { ServeConfig.parse(arrayOf("--prot", "18234")) }
        assertFailsWith<IllegalArgumentException> { ServeConfig.parse(arrayOf("--port", "abc")) }
        assertFailsWith<IllegalArgumentException> { ServeConfig.parse(arrayOf("--port")) }
    }

    // ── 测试脚手架 ────────────────────────────────────────────────────────────────

    private fun start(distDir: File, token: String = AccessToken.newToken()): Harness {
        // 端口 0 = 系统挑空闲端口；数据根指到临时目录，不碰真数据。
        dataRootOverride = Files.createTempDirectory("xjtu-serve-session").toFile()
        // ⚠️ 建会话之前先装进程级前置：`HttpClients.base` 只抄一次 selector 与 trust manager，否则同 JVM 后跑的假上游测试会 502。
        FakeCampusProxy.installFakeUpstreams()
        // 也要清进程级会话态：同 JVM 先跑的测试类会把凭据留在缓存里。
        wipeSecureStore(JvmCredentialStore.FILE_NAME)
        val config = ServeConfig(port = 0, distDir = distDir)
        val server = serveServer(
            config,
            token,
            ServeSession(),
            startedAtMillis = System.currentTimeMillis() - 5_000,
        )
        server.start(wait = false)
        started = server
        val connector = runBlocking { server.engine.resolvedConnectors().single() }
        return Harness(connector.host, connector.port)
    }

    /** 造一个临时产物目录：`<tmp>/dist/` 是托管根，同级放一份 `secret.txt`。 */
    private fun tempDist(): File {
        val root = Files.createTempDirectory("xjtu-serve").toFile()
        File(root, "secret.txt").writeText("不该被读到")
        val dist = File(root, "dist")
        dist.mkdirs()
        File(dist, "index.html").writeText("<!doctype html><title>XJTU ToolBox 静态产物</title>\n")
        return dist
    }

    /** 本机第一个**非回环**的 IPv4 地址；没有就返回 null（纯离线容器）。 */
    private fun externalIpv4(): String? = NetworkInterface.getNetworkInterfaces()
        .toList()
        .asSequence()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { it.inetAddresses.toList().asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull()
        ?.hostAddress

    /** 起好的实例 + 一个真 HTTP 客户端（JDK 自带的，不引依赖）。 */
    private class Harness(val host: String, val port: Int) {
        private val client: HttpClient = HttpClient.newBuilder()
            // 明文 HTTP 上不必去试 h2c 升级：CIO 只讲 HTTP/1.1
            .version(HttpClient.Version.HTTP_1_1)
            .build()

        fun get(path: String, bearer: String? = null, cookie: String? = null): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().apply {
                bearer?.let { header("Authorization", "Bearer $it") }
                cookie?.let { header("Cookie", "${AccessToken.COOKIE_NAME}=$it") }
            }.build()
            return client.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 1_000
    }
}

/** 响应体按 JSON 对象读（本文件的断言到处都是它）。 */
private fun String.asJsonObject(): JsonObject = Json.parseToJsonElement(this).jsonObject
