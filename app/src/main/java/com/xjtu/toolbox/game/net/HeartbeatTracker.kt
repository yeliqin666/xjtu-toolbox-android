package com.xjtu.toolbox.game.net

/**
 * 心跳判活：3 秒发一次 ping，15 秒收不到任何回应（pong 或者对方发来的其它消息都算"活着"）
 * 就判定掉线。
 *
 * 时间源由调用方传入（而不是内部读系统时钟），纯粹是为了能在 JVM 单测里用假时钟
 * 一步步推进，不用真的 sleep 15 秒。
 */
class HeartbeatTracker(
    private val pingIntervalMs: Long = 3_000,
    private val timeoutMs: Long = 15_000,
) {
    private var lastSeenAt: Long = 0
    private var lastPingSentAt: Long = 0
    private var started = false

    fun start(nowMs: Long) {
        lastSeenAt = nowMs
        lastPingSentAt = nowMs
        started = true
    }

    /** 收到任何一条对方消息（不一定是 pong）都算"还活着"，跟 ping/pong 的发送节奏分开算。 */
    fun onMessageReceived(nowMs: Long) {
        lastSeenAt = nowMs
    }

    /** 是否到点该发一个 ping 了。 */
    fun shouldSendPing(nowMs: Long): Boolean = started && nowMs - lastPingSentAt >= pingIntervalMs

    fun onPingSent(nowMs: Long) {
        lastPingSentAt = nowMs
    }

    fun isTimedOut(nowMs: Long): Boolean = started && nowMs - lastSeenAt >= timeoutMs
}
