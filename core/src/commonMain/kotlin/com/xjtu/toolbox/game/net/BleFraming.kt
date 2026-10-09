package com.xjtu.toolbox.game.net

import kotlin.math.ceil

/**
 * BLE 单次 GATT 写入/通知有长度上限（MTU 协商前只有 20 字节出头，协商后常见也就
 * 到 200～500 字节），而协议是"一行 JSON"，稍微长一点的着法/规则参数就装不下一片，
 * 所以 BLE 通道要在应用层自己分片重组，不能像局域网 TCP 那样直接按行读写。
 *
 * 帧格式：`[msgId 1B][total 1B][index 1B][payload...]`——3 字节头，msgId 0~255 循环，
 * total/index 也是 0~255（够用：极端情况下一条几百 KB 的消息才会超过 255 片，
 * 这里的消息不会有那么大）。
 */
object BleFraming {
    const val HEADER_SIZE = 3

    /** 把 [payload] 按 [mtu]（一次能发的总字节数，含头）切片。 */
    fun fragment(msgId: Int, payload: ByteArray, mtu: Int): List<ByteArray> {
        val chunkSize = (mtu - HEADER_SIZE).coerceAtLeast(1)
        val total = ceil(payload.size.coerceAtLeast(1) / chunkSize.toDouble()).toInt().coerceIn(1, 255)
        return (0 until total).map { i ->
            val start = i * chunkSize
            val end = minOf(start + chunkSize, payload.size)
            val chunk = if (start < payload.size) payload.copyOfRange(start, end) else ByteArray(0)
            ByteArray(HEADER_SIZE + chunk.size).also {
                it[0] = msgId.toByte()
                it[1] = total.toByte()
                it[2] = i.toByte()
                chunk.copyInto(it, HEADER_SIZE)
            }
        }
    }
}

/**
 * 分片重组，能扛乱序和缺片：凑不齐就一直缓着，不会因为一片来晚了就拼错。
 *
 * 每条完整消息占一个 msgId；msgId 是 0~255 循环使用的，一局对局里同时"在途"的分片消息
 * 理论上不会超过这个量级（着法/心跳都是单发单收，串行等对方确认），复用到同一个 msgId
 * 时按"total 对不上就是旧消息复用了这个 id，扔掉重开"处理，避免旧的残留片污染新消息。
 */
class BleReassembler {
    private class Pending(val total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
    }

    private val pendingByMsgId = HashMap<Int, Pending>()

    /** 喂一片；凑齐时返回完整报文，否则返回 null。片本身格式不对（太短/越界）直接丢弃。 */
    fun feed(fragment: ByteArray): ByteArray? {
        if (fragment.size < BleFraming.HEADER_SIZE) return null
        val msgId = fragment[0].toInt() and 0xFF
        val total = fragment[1].toInt() and 0xFF
        val index = fragment[2].toInt() and 0xFF
        if (total <= 0 || index >= total) return null

        var pending = pendingByMsgId[msgId]
        if (pending == null || pending.total != total) {
            pending = Pending(total)
            pendingByMsgId[msgId] = pending
        }
        if (pending.parts[index] == null) {
            pending.parts[index] = fragment.copyOfRange(BleFraming.HEADER_SIZE, fragment.size)
            pending.received++
        }
        if (pending.received < total) return null

        pendingByMsgId.remove(msgId)
        var size = 0
        for (p in pending.parts) size += p!!.size
        val out = ByteArray(size)
        var offset = 0
        for (p in pending.parts) {
            p!!.copyInto(out, offset)
            offset += p.size
        }
        return out
    }

    /** 清空所有未拼完的半成品——断线重连时用，避免旧连接的残片和新连接的分片混在一起。 */
    fun reset() {
        pendingByMsgId.clear()
    }
}
