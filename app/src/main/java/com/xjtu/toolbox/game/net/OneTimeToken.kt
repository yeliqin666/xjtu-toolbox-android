package com.xjtu.toolbox.game.net

/**
 * 一次性口令：房主生成一个随机口令塞进二维码，第一个用它 hello 成功的设备"消费"掉它，
 * 之后同一网络/附近再有第三台设备拿同一份码来连，一律拒绝。
 *
 * 断线重连是个例外——同一个对手掉线后重连，不该被当成"第三台设备"拒之门外，所以重连
 * 走的是"同一个 socket/GATT 连接已经验证过一次"的信任，不会再次调用 [tryConsume]；
 * 只有全新连接的首次 hello 才需要过这一关。
 */
class OneTimeToken(private val expected: String) {
    private var consumed = false

    /** 校验并消费；第一次且口令匹配才返回 true，此后恒为 false。 */
    fun tryConsume(candidate: String?): Boolean {
        if (consumed) return false
        if (candidate != expected) return false
        consumed = true
        return true
    }

    fun isConsumed(): Boolean = consumed

    companion object {
        /** 生成一段够随机、又不会长到二维码装不下的口令。 */
        fun generate(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 去掉容易看混的 0/O/1/I
            return buildString {
                val random = java.security.SecureRandom()
                repeat(8) { append(chars[random.nextInt(chars.length)]) }
            }
        }
    }
}
