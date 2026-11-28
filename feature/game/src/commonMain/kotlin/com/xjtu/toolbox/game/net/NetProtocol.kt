package com.xjtu.toolbox.game.net

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable

/** 协议里所有消息的类型标识。 */
object NetMsgType {
    const val HELLO = "hello"
    /** 房主的挑战：nonce + 证明"我知道口令"，加入方据此排掉冒充房主的外设。 */
    const val CHALLENGE = "challenge"
    /** 加入方对挑战的应答：证明"我知道口令"，且对着房主刚给的 nonce 算，抓包重放不出去。 */
    const val AUTH = "auth"
    /** 房主的确认：对局参数 + 证明（参数进 MAC，中继改不了）。 */
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
    // 握手四步（见 [HandshakeAuth] 的说明）：hello → challenge → auth → hello_ack。
    // **口令本身永远不上链路**，每一步传的都是"用口令算出来的证明"，且必须绑对方刚给的随机数。
    // 曾经这里是明文口令，问题不止被动嗅探：加入方按服务 UUID 认房主，而这个 UUID 是公开广播的，
    // 谁伪造一台同 UUID 的外设，加入方就把口令送给谁，对方随后能拿着它去连真房主冒充对手。
    val nonce: String? = null,
    val proof: String? = null,
    val game: String? = null,
    // hello_ack：房主确认后才下发对局参数——加入方扫二维码时只知道连接方式和口令，
    // 棋种之外的规则参数（围棋路数等）和先后手只能由房主告知。这两个字段都在 [NetEnvelope.proof] 里。
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

    /** 第 1 步：加入方打招呼，只带自己的 nonce 和棋种，不含任何凭据。 */
    fun hello(nonce: String, game: GameKind) =
        NetEnvelope(NetMsgType.HELLO, nonce = nonce, game = game.wireId)

    /** 第 2 步：房主给新鲜挑战，并用证明告诉加入方"我确实知道口令"。 */
    fun challenge(nonce: String, proof: String) =
        NetEnvelope(NetMsgType.CHALLENGE, nonce = nonce, proof = proof)

    /** 第 3 步：加入方对着房主的 nonce 作答。 */
    fun auth(proof: String) = NetEnvelope(NetMsgType.AUTH, proof = proof)

    /**
     * 第 4 步：房主下发对局参数。[proof] 把参数一起盖住，加入方验不过就不采用。
     * 失败时 [ok]=false 且不带证明（拒绝的理由给一句能显示的话即可，不含任何秘密）。
     */
    fun helloAck(
        ok: Boolean,
        proof: String? = null,
        rule: String? = null,
        hostFirst: Boolean? = null,
        reason: String? = null,
    ) = NetEnvelope(
        NetMsgType.HELLO_ACK,
        ok = ok,
        proof = proof,
        rule = rule,
        hostFirst = hostFirst,
        reason = reason,
    )

    fun move(seq: Int, code: String) = NetEnvelope(NetMsgType.MOVE, seq = seq, move = code)

    fun simple(type: String) = NetEnvelope(type)

    fun resume(ackSeq: Int) = NetEnvelope(NetMsgType.RESUME, ackSeq = ackSeq)

    fun error(reason: String) = NetEnvelope(NetMsgType.ERROR, reason = reason)
}
