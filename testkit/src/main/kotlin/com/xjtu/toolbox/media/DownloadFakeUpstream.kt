package com.xjtu.toolbox.media

import com.sun.net.httpserver.HttpExchange

/**
 * 假的**回放视频直链**（`video-download.xjtu.edu.cn`，**明文 http**）—— 给「真下载一个夹具文件并落盘」
 * 那条端到端测试与 `download_manager.png` 证据图用。
 *
 * ## 它为什么存在
 *
 * 下载管理这一屏在桌面端要证明的不是「屏画得出来」，而是**那条队列真能把一个文件搬下来**：
 * 发 HTTP、按 `Range` 续传、把字节写进本地文件、状态从 `pending` 走到 `completed`。
 * 真站点的回放直链动辄几百 MB、还要登录，所以这里造一个**几十 KB 的假 mp4**：
 * 字节是程序按固定公式生成的（不是截取任何真文件，也不是随机数 —— 每次跑都逐字节相同，
 * 测试才能断言「落到磁盘上的就是它」）。
 *
 * ## 支持 `Range`（这是关键）
 *
 * 共享状态机的续传语义只能靠真响应码来验：带 `Range` 且合法 ⇒ **206** + `Content-Range`；
 * 起点已经到文件尾 ⇒ **416**（「本地已完整」，不是失败）；不认 `Range` 的服务器回 200 整份 ——
 * 三种形态这里都能造（见 [handle]）。
 *
 * ## 为什么是 http
 *
 * 本地假代理是纯 HTTP 代理，给不了 CONNECT + TLS 隧道（见 [com.xjtu.toolbox.FakeUpstreamFront]）；
 * 桌面端下载走的是同一个 `HttpClients.base`（同一套 cookie/拦截器/代理），
 * 所以「取数链路是真的」这条性质不受影响。
 */
class DownloadFakeUpstream {

    companion object {
        /** 目标 host（与 `:app` 那边拼回放直链时用的域名同型；**不是**任何真地址）。 */
        const val HOST = "video-download.xjtu.edu.cn"

        /** 夹具那门课与那场活动（假名字，与 `LmsFakeUpstream` 的示例课不是同一门）。 */
        const val COURSE_NAME = "示例课（下载夹具）"
        const val ACTIVITY_TITLE = "示例活动（下载夹具）"

        /** 两条机位的直链路径（`instructor` 那一条用得多）。 */
        const val INSTRUCTOR_PATH = "/ClassReplay/instructor.mp4"
        const val ENCODER_PATH = "/ClassReplay/encoder.mp4"

        /** 「教师直播」那条直链的完整地址（**http**，见类 KDoc）。 */
        const val INSTRUCTOR_URL = "http://$HOST$INSTRUCTOR_PATH"

        /** 「电脑屏幕」那条直链的完整地址（夹具两条路答的是同一份字节，只是路径不同）。 */
        const val ENCODER_URL = "http://$HOST$ENCODER_PATH"

        /** 假视频的大小：够跨好几次 8 KB 缓冲与一次 500 ms 进度节流，又小到测试跑得快。 */
        const val SIZE_BYTES = 200_000

        /**
         * 假视频的字节。前 8 个字节是 MP4 的 `ftyp` box（只为「看起来像个 mp4」，
         * 播放器能不能懵开不在这条链路的判据里），后面按固定公式填 —— 每次跑逐字节相同。
         */
        val BYTES: ByteArray get() = fileBytes

        private val fileBytes: ByteArray = ByteArray(SIZE_BYTES) { index ->
            when (index) {
                // `ftyp` box 的头 8 字节：00 00 00 18 66 74 79 70（"ftyp" 在中间）
                in 0..3 -> 0
                4 -> 0x66
                5 -> 0x74
                6 -> 0x79
                7 -> 0x70
                // 其余：固定公式的伪内容（不是随机数，跑两次逐字节相同）
                else -> ((index * 31 + 7) % 251).toByte()
            }
        }
    }
    /** 夹具真被请求过几次（只算那两条夹具直链）。 */
    val requests = java.util.concurrent.atomic.AtomicLong()

    /** 其中几次带了 `Range` 头（断点续传那条路真的被走到过的证据）。 */
    val rangeRequests = java.util.concurrent.atomic.AtomicLong()

    /**
     * 按目标路径答一份字节流。
     *
     * ⚠️ 路径漂了就 404（不静默换成「随便给一份」）：那样「换了个 URL 但假上游照答」会被静默放过。
     */
    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        if (path != INSTRUCTOR_PATH && path != ENCODER_PATH) {
            val body = "no such fixture path: $path".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
            return
        }
        requests.incrementAndGet()

        val bytes = fileBytes
        val total = bytes.size.toLong()
        val rangeHeader = exchange.requestHeaders.getFirst("Range")
        val start = rangeHeader
            ?.substringAfter("bytes=", "")
            ?.substringBefore('-')
            ?.toLongOrNull()

        if (rangeHeader != null) rangeRequests.incrementAndGet()
        if (start != null && start >= total) {
            // 本地已经是完整文件：Range 越界 ⇒ 416（共享状态机把它判成「已完成」，不是失败）
            exchange.responseHeaders.add("Content-Range", "bytes */$total")
            exchange.sendResponseHeaders(416, -1)
            exchange.close()
            return
        }

        val from = start ?: 0L
        val slice = bytes.copyOfRange(from.toInt(), bytes.size)
        if (from > 0) {
            exchange.responseHeaders.add("Content-Range", "bytes $from-${total - 1}/$total")
            exchange.sendResponseHeaders(206, slice.size.toLong())
        } else {
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, slice.size.toLong())
        }
        exchange.responseBody.write(slice)
        exchange.close()
    }
}
