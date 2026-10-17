package com.xjtu.toolbox.media

import com.sun.net.httpserver.HttpExchange

/** 假的回放视频直链（`video-download.xjtu.edu.cn`，明文 http） */
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

        /** 假视频的字节。 */
        val BYTES: ByteArray get() = fileBytes

        private val fileBytes: ByteArray = ByteArray(SIZE_BYTES) { index ->
            when (index) {
                // `ftyp` box 的头 8 字节：00 00 00 18 66 74 79 70（"ftyp" 在中间）
                in 0..3 -> 0
                4 -> 0x66
                5 -> 0x74
                6 -> 0x79
                7 -> 0x70
                else -> ((index * 31 + 7) % 251).toByte()
            }
        }
    }
    /** 夹具真被请求过几次（只算那两条夹具直链）。 */
    val requests = java.util.concurrent.atomic.AtomicLong()

    /** 其中几次带了 `Range` 头（断点续传那条路真的被走到过的证据）。 */
    val rangeRequests = java.util.concurrent.atomic.AtomicLong()

    /** 按目标路径答一份字节流。 */
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
