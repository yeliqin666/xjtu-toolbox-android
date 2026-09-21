package com.xjtu.toolbox.game.net

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/** 协议里所有消息的类型标识，对应 plan.md §4.9 列出的那一份。 */
object NetMsgType {
    const val HELLO = "hello"
    const val HELLO_ACK = "hello_ack"
    const val MOVE = "move"
    const val UNDO_REQ = "undo_req"
    const val UNDO_ACK = "undo_ack"
    const val RESIGN = "resign"
    const val DRAW_REQ = "draw_req"
    const val DRAW_ACK = "draw_ack"
    const val PING = "ping"
    const val PONG = "pong"
    /** 不在 plan.md 的列表里，但断线重连"按序号补发"需要一条"我这边收到几步了"的消息，加在这。 */
    const val RESUME = "resume"
    /** 收到非法着法 / 口令错误等，断开前给对方一句能显示的理由。 */
    const val ERROR = "error"
}

/**
 * 协议信封：所有消息类型共用一个数据类，按需要填字段，未用到的留 null。
 *
 * 用一个"胖信封"而不是每种消息一个类 + 多态序列化，是因为 Gson 对 sealed class 的支持
 * 要么侵入式加 TypeAdapter，要么每次都要手写 JsonSerializer——对一行 JSON、十种消息类型
 * 的协议来说，维护一个扁平数据类明显更省事，字段是否该出现也全靠 [type] 一个字段兜底，
 * 不会因为拼错某个可选字段名就整条解析失败。
 *
 * 每条消息编码后必须是不含换行符的一行文本（局域网通道按行分帧，BLE 通道分片重组后
 * 还原成一整条），[NetCodec] 保证这一点——JSON 本身不会带 `\n`，这里只是防御性再检查一次。
 */
data class NetEnvelope(
    val type: String,
    // hello
    val token: String? = null,
    val game: String? = null,
    val rule: String? = null,
    val hostFirst: Boolean? = null,
    // hello_ack（token 在这里回传一遍，房主据此核实"接进来的确实是拿到同一份二维码的那台设备"，
    // 而不是恰好先连上 socket/GATT 的第三台设备——见 OnlineGameSession 里 hostToken 的用法）
    val ok: Boolean? = null,
    val reason: String? = null,
    // move
    val seq: Int? = null,
    val move: String? = null,
    // resume：断线重连后告诉对方"我这边已经确认到第几步"，对方据此补发缺的部分
    val ackSeq: Int? = null,
)

object NetCodec {
    private val gson = Gson()

    /** 编码成一行文本；调用方（LAN/BLE 传输层）负责加换行或分片，这里只管内容本身干净。 */
    fun encode(envelope: NetEnvelope): String = gson.toJson(envelope).replace("\n", " ").replace("\r", " ")

    /** 解析失败（不是合法 JSON、缺 type 字段）返回 null，调用方按"这条消息忽略"处理，不整个断开。 */
    fun decode(line: String): NetEnvelope? = try {
        val e = gson.fromJson(line, NetEnvelope::class.java)
        if (e?.type.isNullOrEmpty()) null else e
    } catch (_: JsonSyntaxException) {
        null
    } catch (_: Exception) {
        null
    }

    fun hello(token: String, game: GameKind, rule: String?, hostFirst: Boolean) =
        NetEnvelope(NetMsgType.HELLO, token = token, game = game.wireId, rule = rule, hostFirst = hostFirst)

    fun helloAck(ok: Boolean, token: String? = null, reason: String? = null) =
        NetEnvelope(NetMsgType.HELLO_ACK, ok = ok, token = token, reason = reason)

    fun move(seq: Int, code: String) = NetEnvelope(NetMsgType.MOVE, seq = seq, move = code)

    fun simple(type: String) = NetEnvelope(type)

    fun resume(ackSeq: Int) = NetEnvelope(NetMsgType.RESUME, ackSeq = ackSeq)

    fun error(reason: String) = NetEnvelope(NetMsgType.ERROR, reason = reason)
}
