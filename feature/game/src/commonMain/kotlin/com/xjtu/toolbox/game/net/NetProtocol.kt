package com.xjtu.toolbox.game.net

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable

/** 协议里所有消息的类型标识。 */
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
 * 用一个扁平的"胖信封"而不是每种消息一个类 + 多态序列化：一行 JSON、十种消息类型的协议，
 * 字段是否该出现全靠 [type] 一个字段兜底，也能和旧版本互通。
 *
 * 每条消息编码后必须是不含换行符的一行文本（BLE 通道分片发送、重组后按一整条处理），[NetCodec] 保证这一点。
 */
@Serializable
data class NetEnvelope(
    val type: String = "",
    // hello：**由加入方先发**，把二维码里的口令出示给房主。房主在验证通过前一个字节都不发，
    // 所以"抢在真对手之前连上 GATT"这件事不再能换到口令。
    val token: String? = null,
    val game: String? = null,
    // hello_ack：房主验证通过后回给加入方，顺带下发对局参数——加入方扫二维码时只知道
    // 连接方式和口令，棋种之外的规则参数（围棋路数等）和先后手只能由房主告知。
    val rule: String? = null,
    val hostFirst: Boolean? = null,
    // hello_ack 的结果位；[reason] 是失败时能直接展示给用户的一句话。
    val ok: Boolean? = null,
    val reason: String? = null,
    // move
    val seq: Int? = null,
    val move: String? = null,
    // resume：断线重连后告诉对方"我这边已经确认到第几步"，对方据此补发缺的部分
    val ackSeq: Int? = null,
)

object NetCodec {
    /** 编码成一行文本；调用方（LAN/BLE 传输层）负责加换行或分片，这里只管内容本身干净。 */
    fun encode(envelope: NetEnvelope): String = AppJson.encodeToString(envelope).replace("\n", " ").replace("\r", " ")

    /** 解析失败（不是合法 JSON、缺 type 字段）返回 null，调用方按"这条消息忽略"处理，不整个断开。 */
    fun decode(line: String): NetEnvelope? =
        runCatching { AppJson.decodeFromString<NetEnvelope>(line) }.getOrNull()?.takeIf { it.type.isNotEmpty() }

    fun hello(token: String, game: GameKind) =
        NetEnvelope(NetMsgType.HELLO, token = token, game = game.wireId)

    fun helloAck(ok: Boolean, rule: String? = null, hostFirst: Boolean? = null, reason: String? = null) =
        NetEnvelope(NetMsgType.HELLO_ACK, ok = ok, rule = rule, hostFirst = hostFirst, reason = reason)

    fun move(seq: Int, code: String) = NetEnvelope(NetMsgType.MOVE, seq = seq, move = code)

    fun simple(type: String) = NetEnvelope(type)

    fun resume(ackSeq: Int) = NetEnvelope(NetMsgType.RESUME, ackSeq = ackSeq)

    fun error(reason: String) = NetEnvelope(NetMsgType.ERROR, reason = reason)
}
