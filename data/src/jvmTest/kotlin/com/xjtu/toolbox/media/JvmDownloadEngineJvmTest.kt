package com.xjtu.toolbox.media

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 桌面那份执行器（[JvmDownloadEngine]）在**真 HTTP** 上的行为 —— 上游是本地起的一个
 * `com.sun.net.httpserver.HttpServer`（不是假接口对象：这一层要验的就是套接字、`Range`、
 * 响应码与落盘之间的那几件事）。
 *
 * 钉住的四条与端口契约逐条对应：
 *  1. 全新下载：字节逐字节落到文件，进度先报起点（0, total）再报推进；
 *  2. 续传：带 `Range` ⇒ 服务器 206 ⇒ 从断点接着写（不是从头覆盖）；
 *  3. 服务器不认 `Range`（回 200 整份）⇒ 从头写（**不能**接在旧内容后面）；
 *  4. 本地已完整（`Range` 越界）⇒ 416 ⇒ 判成完成，文件不动；
 * 外加失败（404 抛异常）与取消（协程取消真能中断搬运）。
 *
 * 与 `:desktop:test` 的分工：那边验的是「桌面这一端的装配 + 假校园上游那个夹具文件」，
 * 这边验的是搬运循环本身（含取消与三种响应码）。
 */
class JvmDownloadEngineJvmTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    private val body: ByteArray = ByteArray(20_000) { index -> ((index * 37 + 11) % 253).toByte() }

    /** 服务器见过的 `Range` 头（验「续传那一枪真带了 Range」）。 */
    private val seenRanges = java.util.concurrent.CopyOnWriteArrayList<String>()

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file.mp4") { exchange ->
            exchange.requestHeaders.getFirst("Range")?.let { seenRanges.add(it) }
            val total = body.size.toLong()
            val start = exchange.requestHeaders.getFirst("Range")
                ?.substringAfter("bytes=", "")?.substringBefore('-')?.toLongOrNull()
            if (start != null && start >= total) {
                exchange.responseHeaders.add("Content-Range", "bytes */$total")
                exchange.sendResponseHeaders(416, -1)
                exchange.close()
                return@createContext
            }
            val from = start ?: 0L
            val slice = body.copyOfRange(from.toInt(), body.size)
            if (from > 0) exchange.responseHeaders.add("Content-Range", "bytes $from-${total - 1}/$total")
            exchange.sendResponseHeaders(if (from > 0) 206 else 200, slice.size.toLong())
            exchange.responseBody.write(slice)
            exchange.close()
        }
        // 「不认 Range 的服务器」：收到 Range 也回整份 200
        server.createContext("/ignores-range.mp4") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }
        // 慢速服务器：给「取消真能中断搬运」用（每次 8 KB 之间歇 20 ms ⇒ 200 KB 要约 500 ms）
        server.createContext("/slow.mp4") { exchange ->
            exchange.sendResponseHeaders(200, slowBody.size.toLong())
            exchange.responseBody.use { out ->
                var offset = 0
                while (offset < slowBody.size) {
                    val end = minOf(offset + 8192, slowBody.size)
                    out.write(slowBody, offset, end - offset)
                    out.flush()
                    offset = end
                    Thread.sleep(20)
                }
            }
        }
        server.createContext("/missing.mp4") { exchange ->
            val bytes = "no such file".toByteArray()
            exchange.sendResponseHeaders(404, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.executor = null
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun tempFile(name: String) = Files.createTempDirectory("xjtu-download-test")
        .resolve(name).toFile()

    @Test
    fun `全新下载：字节逐字节落盘，进度先报起点`() = runBlocking {
        val target = tempFile("fresh.mp4")
        val progress = mutableListOf<Pair<Long, Long>>()

        val outcome = JvmDownloadEngine().download(
            DownloadRequest(url = url("/file.mp4"), filePath = target.absolutePath, resumeFromBytes = 0L),
        ) { downloaded, total -> progress.add(downloaded to total) }

        assertEquals(body.size.toLong(), outcome.downloadedBytes)
        assertEquals(body.size.toLong(), outcome.totalBytes, "总大小来自 Content-Length")
        assertContentEquals(body, target.readBytes(), "磁盘上的字节要与上游逐字节相同")
        assertEquals(0L to body.size.toLong(), progress.first(), "第一发是起点（搬迁前那句写库就在这个位置）")
        assertTrue(progress.all { it.second == body.size.toLong() }, "每一发都带着总大小：$progress")
        // 进度是 500 ms 节流的（与 Android 那份同一个口径）—— 本地这份跑得比 500 ms 快，
        // 所以除了起点只可能再报几发；**整份字节数由返回值给**（状态机拿它写 completed）
        assertTrue(progress.last().first in 1..body.size.toLong(), "进度单调落在 [1, 总大小] 里：$progress")
        assertTrue(seenRanges.isEmpty(), "全新下载不该带 Range")
    }

    @Test
    fun `续传：带 Range、服务器 206，从断点接着写`() = runBlocking {
        val target = tempFile("resume.mp4")
        val head = 6_000
        target.writeBytes(body.copyOfRange(0, head))

        val outcome = JvmDownloadEngine().download(
            DownloadRequest(url = url("/file.mp4"), filePath = target.absolutePath, resumeFromBytes = head.toLong()),
        ) { _, _ -> }

        assertEquals(body.size.toLong(), outcome.downloadedBytes, "续传后的字节数是「整份」")
        assertEquals(body.size.toLong(), outcome.totalBytes, "总大小取自 Content-Range")
        assertContentEquals(body, target.readBytes())
        assertEquals(listOf("bytes=$head-"), seenRanges, "续传那一枪要带 Range")
    }

    @Test
    fun `服务器不认 Range（回 200 整份）：从头写，不接在旧内容后面`() = runBlocking {
        val target = tempFile("ignored-range.mp4")
        target.writeBytes(ByteArray(6_000) { 0x7A })

        val outcome = JvmDownloadEngine().download(
            DownloadRequest(
                url = url("/ignores-range.mp4"),
                filePath = target.absolutePath,
                resumeFromBytes = 6_000L,
            ),
        ) { _, _ -> }

        assertEquals(body.size.toLong(), outcome.downloadedBytes)
        assertEquals(body.size.toLong(), outcome.totalBytes)
        assertContentEquals(body, target.readBytes(), "旧内容必须被截掉（搬迁前那条 resumed=false 的判据）")
    }

    @Test
    fun `本地已完整：416 判成完成，文件一个字节都不动`() = runBlocking {
        val target = tempFile("complete.mp4")
        target.writeBytes(body)

        val outcome = JvmDownloadEngine().download(
            DownloadRequest(url = url("/file.mp4"), filePath = target.absolutePath, resumeFromBytes = body.size.toLong()),
        ) { _, _ -> }

        assertEquals(body.size.toLong(), outcome.downloadedBytes)
        assertEquals(body.size.toLong(), outcome.totalBytes)
        assertContentEquals(body, target.readBytes())
    }

    @Test
    fun `失败：非 2xx 直接抛，由状态机转成 failed`() = runBlocking {
        val target = tempFile("missing.mp4")
        val error = assertFailsWith<Exception> {
            JvmDownloadEngine().download(
                DownloadRequest(url = url("/missing.mp4"), filePath = target.absolutePath, resumeFromBytes = 0L),
            ) { _, _ -> }
        }
        assertTrue(error.message.orEmpty().contains("404"), "报错里要有真正的响应码：${error.message}")
    }

    @Test
    fun `取消：协程取消能中断搬运（状态机那边的暂停靠它）`() = runBlocking {
        val target = tempFile("cancel.mp4")
        val job = launch(Dispatchers.IO) {
            JvmDownloadEngine().download(
                DownloadRequest(url = url("/slow.mp4"), filePath = target.absolutePath, resumeFromBytes = 0L),
            ) { _, _ -> }
        }
        delay(150)
        // `cancelAndJoin`：等它真的结束。若搬运循环吞掉了取消，这里会一直等到整份传完，
        // 下面那条「文件不是整份」的断言就会失败 —— 那正是这条用例要钉的行为。
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(
            target.length() < slowBody.size.toLong(),
            "取消之后不该是整份文件（实际 ${target.length()} / ${slowBody.size}）",
        )
    }

    private val slowBody: ByteArray = ByteArray(200_000) { index -> (index % 251).toByte() }
}
