package com.xjtu.toolbox.desktop.media

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一路要经回环代理去取的上游流。
 *
 * [headers] 是「不给就 403」的那几个请求头（LMS 的 `Origin` / `Referer`，将来可能还有 `Cookie`）：
 * 系统播放器只吃一个 URL、不会替我们带它们，所以由代理带上。
 */
internal data class ProxiedStream(
    val upstreamUrl: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 桌面端的「鉴权头」补丁：一个只绑 `127.0.0.1` 的取流代理。
 *
 * 为什么需要它：桌面端没有内置播放器，只能把地址交给系统播放器（`openWithSystemProgram`），
 * 而系统播放器拿到的只是一个 URL —— [ProxiedStream.headers] 里的东西它不会带，于是
 * 「要登录态/同源头才能拉」的回放流在桌面直接 403。这里的做法是把播放器指向本机，
 * 由本机带上那些头去上游取，再把响应**原样流式**搬回来。
 *
 * 几条关键约定：
 * - **只绑 127.0.0.1**、端口交给系统分配（0）；每条流一个随机 token 的路径，
 *   别人扫不到也猜不着，token 只在这一次播放的这个实例里有效。
 * - **Range 全链路透传**：客户端（播放器）的 `Range` 转发给上游，上游的
 *   `206` / `Content-Range` / `Accept-Ranges` 连同 `Content-Type` / `Content-Length` 一起回给播放器。
 *   拖动进度条就是靠这条：播放器发新的 `Range`、掐断旧连接，代理照做即可。
 * - **字节透明**：不解压、不改写、不整体缓冲 —— 上游多少字节、什么顺序，就搬多少。
 * - [close] 之后端口立刻不再监听；重复调用是安全的。
 *
 * 已知边界（诚实记在这里）：一次只代理「一条 URL」。HLS 播放列表（`.m3u8`）里的相对分片地址
 * 是播放器按播放列表地址自己拼的，不会经过这里 —— 那种情况下要改写播放列表才能连分片一起代理，
 * 本轮没做（见 `/tmp/w4-progress.md`）。
 */
internal class LoopbackStreamProxy private constructor(
    private val server: HttpServer,
    private val dispatcher: ExecutorService,
    private val client: HttpClient,
    /** token -> 上游流 */
    private val byToken: Map<String, ProxiedStream>,
    /** 上游流 -> token */
    private val byStream: Map<ProxiedStream, String>,
) : Closeable {

    private val closed = AtomicBoolean(false)

    /** 绑定后才是真端口（`start` 之前请求的是 0）。 */
    val port: Int get() = server.address.port

    /** [stream] 在本次代理里的回环地址；没在这里登记过就是 `null`。 */
    fun localUrl(stream: ProxiedStream): String? =
        byStream[stream]?.let { token -> "http://$LOOPBACK_HOST:$port/$token" }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // stop(0) = 立刻断开监听，不在这一跳上等优雅收尾（播放结束/离开页面时不该卡住 UI）。
        runCatching { server.stop(0) }
        runCatching { dispatcher.shutdownNow() }
        // shutdownNow 而不是 close：close 会等正在进行的取流结束，我们等的恰好是「别再流了」。
        runCatching { client.shutdownNow() }
    }

    // ── 请求进来 ────────────────────────────────────────────────────────────

    private fun handle(exchange: HttpExchange) {
        try {
            val token = exchange.requestURI.path.orEmpty().trim('/')
            val stream = byToken[token]
            if (stream == null) {
                // 不存在的 token 一律 404：不把上游地址、失败原因之类的信息漏给本机的其他程序。
                exchange.respondText(404, "unknown stream")
                return
            }
            val method = exchange.requestMethod.uppercase()
            if (method != "GET" && method != "HEAD") {
                exchange.responseHeaders.set("Allow", "GET, HEAD")
                exchange.respondText(405, "only GET / HEAD")
                return
            }
            relay(exchange, stream, method)
        } catch (e: IOException) {
            // 播放器 seek / 关播放器会直接掐断这条连接 —— 正常路径，不是错误。
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun relay(exchange: HttpExchange, stream: ProxiedStream, method: String) {
        val upstream = runCatching { URI.create(stream.upstreamUrl) }.getOrNull()
        if (upstream == null || upstream.host.isNullOrEmpty() ||
            (upstream.scheme != "http" && upstream.scheme != "https")
        ) {
            exchange.respondText(400, "upstream url is not http(s)")
            return
        }

        val response = try {
            client.send(
                upstreamRequest(exchange, stream, method, upstream),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        } catch (e: IOException) {
            exchange.respondText(502, "upstream unreachable")
            return
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }

        val body = response.body()
        try {
            copyResponseHeaders(response, exchange)
            val noBody = method == "HEAD" || response.statusCode() == 204 || response.statusCode() == 304
            // sendResponseHeaders：-1 = 没有 body；0 = chunked（上游没给长度）；>0 = 定长。
            val length = if (noBody) {
                -1L
            } else {
                response.headers().firstValueAsLong("content-length").orElse(0L)
            }
            exchange.sendResponseHeaders(response.statusCode(), length)
            if (!noBody) body.pumpTo(exchange.responseBody)
        } catch (e: IOException) {
            // 同上：客户端中途走了。上游那条连接在 finally 里关。
        } finally {
            runCatching { body.close() }
        }
    }

    /**
     * 拼上游请求：上游要求的头优先（`Origin` / `Referer` / `Cookie` …），
     * 再把播放器自己的头接上 —— **`Range` 就是从这条路过去的**，seek 全靠它。
     */
    private fun upstreamRequest(
        exchange: HttpExchange,
        stream: ProxiedStream,
        method: String,
        upstream: URI,
    ): HttpRequest {
        val builder = HttpRequest.newBuilder(upstream).method(method, HttpRequest.BodyPublishers.noBody())
        // 已经带上去的那些（小写）—— 同一个头不重复带，免得上游收到两份 Origin。
        val sent = HashSet<String>()
        stream.headers.forEach { (name, value) ->
            val key = name.lowercase()
            if (value.isNotBlank() && key !in DROPPED_REQUEST_HEADERS) {
                builder.header(name, value)
                sent.add(key)
            }
        }
        exchange.requestHeaders.forEach { (name, values) ->
            val key = name.lowercase()
            if (key in DROPPED_REQUEST_HEADERS || key in sent) return@forEach
            values.forEach { value -> if (value.isNotBlank()) builder.header(name, value) }
        }
        return builder.build()
    }

    /**
     * 把上游的响应头搬回去。
     *
     * 这里**必须**留着 `Content-Range` / `Accept-Ranges` / `Content-Type` / `Content-Encoding`：
     * 播放器靠它们认「这是同一个文件的一部分」，不认就只能在第一段之后停住。
     * 只丢掉「这一跳」的长度/连接语义 —— 那是 [HttpServer] 按 [HttpExchange.sendResponseHeaders] 自己写的。
     */
    private fun copyResponseHeaders(response: HttpResponse<*>, exchange: HttpExchange) {
        response.headers().map().forEach { (name, values) ->
            if (name.lowercase() in DROPPED_RESPONSE_HEADERS) return@forEach
            values.forEach { value -> exchange.responseHeaders.add(name, value) }
        }
    }

    private fun HttpExchange.respondText(code: Int, message: String) {
        runCatching {
            val payload = message.toByteArray(Charsets.UTF_8)
            responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            sendResponseHeaders(code, payload.size.toLong())
            responseBody.use { it.write(payload) }
        }
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"

        /**
         * 起一个代理；[streams] 里每条流分到一条随机 token 的路径（进程内、仅本次播放有效）。
         *
         * 调用方负责 [close]。
         */
        fun start(streams: List<ProxiedStream>): LoopbackStreamProxy {
            require(streams.isNotEmpty()) { "至少要有一条要走代理的流" }

            val byToken = LinkedHashMap<String, ProxiedStream>()
            val byStream = LinkedHashMap<ProxiedStream, String>()
            streams.distinct().forEach { stream ->
                // UUID v4 本身就来自 SecureRandom，拿去当一次性的地址凭据够用。
                val token = UUID.randomUUID().toString().replace("-", "")
                byToken[token] = stream
                byStream[stream] = token
            }

            // 守护线程：万一忘了 close，也不会把整个进程吊在这个代理上。
            val dispatcher = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "loopback-stream-proxy").apply { isDaemon = true }
            }
            val client = HttpClient.newBuilder()
                // 只掐「连不上」，不掐整次交换：视频是长连接，设请求超时会把正在播的流打断。
                .connectTimeout(Duration.ofSeconds(20))
                // 3xx 由代理自己跟：要是把 302 交给播放器，它跟过去就是「不带请求头」的下一跳，照样 403。
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()

            // ⚠️ 只绑 127.0.0.1（不是 0.0.0.0）：这个端口是带凭据的，不能给同网段的人用。
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), 0)
            val proxy = LoopbackStreamProxy(server, dispatcher, client, byToken, byStream)

            try {
                server.createContext("/", HttpHandler { exchange -> proxy.handle(exchange) })
                server.executor = dispatcher
                server.start()
            } catch (e: Throwable) {
                proxy.close()
                throw e
            }
            return proxy
        }
    }
}

private const val BUFFER_BYTES = 64 * 1024

/** 攒够这么多才 flush 一次：既不会让播放器等到天荒地老，也不至于每 64KB 就 syscall 一次。 */
private const val FLUSH_BYTES = 256 * 1024

/** 见 [LoopbackStreamProxy] 的类注释。 */
private fun InputStream.pumpTo(target: OutputStream) {
    val buffer = ByteArray(BUFFER_BYTES)
    var pending = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        target.write(buffer, 0, read)
        pending += read
        if (pending >= FLUSH_BYTES) {
            target.flush()
            pending = 0
        }
    }
    target.flush()
}

/**
 * 不往上游带的请求头：
 * - `HttpClient` 直接禁止设置的几个（`host` / `content-length` / `connection` / `expect` / `upgrade`），设了会抛异常；
 * - 逐跳（hop-by-hop）头，它们是「这一跳」的语义，转给上游只会造成错位；
 * - `accept-encoding`：故意不带。只搬字节、不解压，上游直接给 identity 最省事。
 */
private val DROPPED_REQUEST_HEADERS = setOf(
    "host",
    "content-length",
    "connection",
    "expect",
    "upgrade",
    "keep-alive",
    "transfer-encoding",
    "te",
    "trailer",
    "proxy-connection",
    "accept-encoding",
)

/**
 * 不往回搬的响应头：长度与分块方式由 [HttpServer] 按 [HttpExchange.sendResponseHeaders] 的入参自己写，
 * 照抄上游的这两行会把响应拼坏（`Content-Length` 是逐字节抄不了的，`Transfer-Encoding` 更是）。
 */
private val DROPPED_RESPONSE_HEADERS = setOf(
    "content-length",
    "transfer-encoding",
    "connection",
    "keep-alive",
    "trailer",
    "te",
    "upgrade",
)
