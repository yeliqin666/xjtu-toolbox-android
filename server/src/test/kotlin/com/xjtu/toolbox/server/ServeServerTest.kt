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

/**
 * serve 模式第一步的验收（`docs/desktop-port-plan.md` §3 / D3 的默认安全口径，
 * 契约见 `docs/api-contract.md` §3）。
 *
 * ## 五条断言，对应文档里的五条口径
 *
 * 1. **默认只监听 `127.0.0.1`**（§3.1）—— 不是靠读代码，而是问**引擎真正绑定**到了哪儿
 *    （`resolvedConnectors()`），外加一次从非回环地址发起的连接尝试（必须连不上）；
 * 2. **`/api/status` 免令牌、裸对象、不含身份**（§3.4 + §4 的例外）；
 * 3. **令牌闸门**（§3.2）：别的 `/api/…` 无令牌 401、错令牌 401、对令牌放行；
 * 4. **静态托管**（serve 模式的另一半）：`/` → `index.html`、`.wasm` 的 mime、`/api/…` 优先于静态、
 *    目录穿越挡死；
 * 5. **令牌来自持久化存储**（§3.2）：第一次启动写下的那一枚，下一次启动读到的是同一个。
 *
 * ## 手法与纪律
 *
 * - 一律**真 socket**（CIO + JDK 的 `java.net.http`）：绑定地址、mime、401 这些都只能在真 HTTP 上验，
 *   用 `ktor-server-test-host` 那种内存引擎等于把这些恰好验掉；
 * - 端口一律 `0`（**系统挑**）：`serve-same-origin.py` 现在占着 8123（用户在用的那个），
 *   测试绝不能去抢；
 * - 令牌存储指到临时目录（`dataRootOverride`，`:data` 给测试留的缝），**绝不碰**
 *   `~/.local/share/xjtu-toolbox/` 里那份真的。
 */
class ServeServerTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        // 每条测试自己起一个真服务器（临时端口），跑完必须关掉 —— 否则 JVM 退不掉
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
        // 数据根会由 start() 换到临时目录，这里复位（与 AccessToken 那条测试的 finally 同口径）
        dataRootOverride = null
    }

    @Test
    fun `默认只监听回环地址`() {
        // ① 默认值本身：改这两个等于改安全口径
        val defaults = ServeConfig.parse(emptyArray())
        assertEquals("127.0.0.1", defaults.host)
        assertEquals(8123, defaults.port)
        assertEquals("web/build/dist/wasmJs/productionExecutable", defaults.distDir.path)
        assertFalse(defaults.exposed)

        // ② 引擎**真正绑定**到哪儿：只看 host 参数是「读了代码」，看 resolvedConnectors 才是
        //    「引擎按它绑了」。端口用 0 = 系统挑（不抢 8123）。
        val harness = start(distDir = tempDist())
        assertEquals("127.0.0.1", harness.host, "默认必须只绑回环地址")

        // ③ 「不是 0.0.0.0」的实证：从本机的**非回环**地址连过去必须连不上。
        //    （纯离线容器里没有非回环地址时跳过这一条；上面两条仍然是硬断言。）
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

        // 无令牌 → 401，且响应体是中文短句（口径与 :core 的 FriendlyError 一致）
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

        // 对令牌 → 过闸门。这一步**已经实现**的端点只有 `/api/status` 与 `/api/session*`，
        // 所以拿一个还没搬过来的端点当证据：过闸门之后是 404（信封）。
        val allowed = harness.get("/api/venue/products", bearer = token)
        assertEquals(404, allowed.statusCode())
        assertEquals(404, allowed.body().asJsonObject().getValue("code").jsonPrimitive.content.toInt())

        // cookie 形态（契约 §3.2 的第二种）：令牌换 cookie 之后随请求带
        assertEquals(404, harness.get("/api/venue/products", cookie = token).statusCode())
        assertEquals(401, harness.get("/api/venue/products", cookie = "wrong").statusCode())

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
            // 权限 0600（与 :data 的 cookie / 凭据同一口径）
            if (fileA.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(fileA.toPath()),
                )
            }

            // ── 第二次启动：把第一次**真写出来的那一份文件**搬到另一个数据根，再丢掉进程内缓存 ──
            //    （`secureKeyValueStore` 是按名字进程级缓存的；`wipeSecureStore` 把缓存与文件一起清掉，
            //    所以「新进程读到同一份文件」这个场景只能这么摆。）
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
        // 端口 0 = 系统挑一个空闲端口。**不能**用 8123：那个端口上现在跑着
        // web/tools/serve-same-origin.py（用户在用的那一个）。
        //
        // 会话装配（`ServeSession`）会读落盘凭据与 cookie 快照 ⇒ 数据根必须指到临时目录，
        // **绝不碰** ~/.local/share/xjtu-toolbox/ 里那份真的（与令牌那条测试同口径）。
        dataRootOverride = Files.createTempDirectory("xjtu-serve-session").toFile()
        // ⚠️ 两件进程级前置要在**建会话之前**装好（见 `FakeCampusProxy.installFakeUpstreams`）：
        // `ServeSession` 一构造就会把 `HttpClients.base` 建出来，而那一下会把当时的
        // `ProxySelector` 与 trust manager **抄进客户端**，全进程只生效一次。这一步测试不用假上游，
        // 但不装的话，**同一 JVM 里后跑的**那个用假上游的测试类（`ServeSessionTest`）会静默地
        // 连不上任何上游（表现是 502 —— 不装 selector 就等于绕过那个假代理）。
        FakeCampusProxy.installFakeUpstreams()
        // 进程级的**会话态**也要清：凭据是按名字全进程缓存的（`secureKeyValueStore`），
        // 同一个 JVM 里先跑的那个登录过的测试类会把它留在缓存里 ⇒ 这一轮的 `/api/status`
        // 就不再报「没会话」了（`:server:test` 的两个测试类跑在同一个 JVM —— 实测撞到过）。
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

    /**
     * 造一个临时产物目录：`<tmp>/dist/` 是托管根，**同级**放一份 `secret.txt`。
     *
     * 为什么同级要放那份文件：目录穿越的断言得有「真穿出去了会怎样」的对照 —— 只有让
     * `/../secret.txt` 在**成功穿越时确实读得到**，那条 404 才证明是被挡住了，而不是碰巧没这个文件。
     */
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
