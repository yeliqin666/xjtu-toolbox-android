package com.xjtu.toolbox.game.net

import com.xjtu.toolbox.platform.secureRandomInt

/**
 * 一次性口令"只能用一次"的这一半：房主生成一个随机口令塞进二维码，第一个证明自己知道它
 * 的设备把它消费掉，之后同一份码再来连接一律拒绝。
 *
 * 校验口令本身不在这里——链路上一律不传明文口令，房主验的是 [HandshakeAuth.guestProof]，
 * 验过之后才来 [consume] 记账。"知道口令"和"用过这张码"是两件事，各归各管。
 *
 * 断线重连是个例外——同一个对手掉线后重连，不该被当成"第三台设备"拒之门外，所以重连
 * 走的是"同一个 socket/GATT 连接已经验证过一次"的信任，不会再次调用 [consume]；
 * 只有全新连接的首次握手才需要过这一关。
 */
class OneTimeToken {
    private var consumed = false

    /** 第一次调用返回 true（并标记已用），此后恒为 false。 */
    fun consume(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    fun isConsumed(): Boolean = consumed

    companion object {
        /**
         * 口令长度就是熵：12 位 × 32 个字符表 ≈ 60 bit。抓到握手的人只能离线爆破这段证明，
         * 原来 8 位（40 bit）在专业设备上是几小时量级，60 bit 才够看；二维码里多四个字符，
         * 容量和肉眼识别都没影响。
         */
        private const val LENGTH = 12

        /** 生成一段够随机、又不会长到二维码装不下的口令。 */
        fun generate(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 去掉容易看混的 0/O/1/I
            return buildString(LENGTH) {
                repeat(LENGTH) { append(chars[secureRandomInt(chars.length)]) }
            }
        }
    }
}
