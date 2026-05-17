package com.xjtu.toolbox.auth

import android.util.Log
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

/**
 * CAS 凭据提交全局闸门（防风控 / 防封号）。
 *
 * 学校统一认证近期风控策略：同账号短时间内多平台并发登录、或登录过于频繁会被标记甚至封号。
 * 客户端侧所有「携带密码的请求」（/cas/login 表单 POST、/cas/mfa/detect）必须经过本闸门：
 *
 * 1. **全局串行**：同一时刻全 app 只允许一个凭据提交在途，多个子系统并发重登时排队，
 *    第一个成功建立 TGC 后，其余子系统走 SSO 免密通过，不再重复提交密码。
 * 2. **最小间隔**：相邻两次凭据提交至少间隔 [MIN_INTERVAL_MS]，把突发登录平滑成串行慢速流。
 * 3. **失败退避**：连续失败 ≥3 次后指数退避（30s 起，封顶 10 分钟），阻断「失败→立刻重试」
 *    的风控触发模式。任何一次成功即清零。
 * 4. **密码熔断联动**：[passwordLatch] 返回 true（密码已确认失效）时直接拒绝提交，
 *    避免后台保活/重登用错误密码反复撞认证接口。
 *
 * 注意：SSO 复用（GET cas/login 且 TGC 有效直接 302）**不经过**本闸门——那类请求不带密码，
 * 不会触发风控，限制它们只会拖慢体验。
 */
object CasGate {
    private const val TAG = "CasGate"
    private const val MIN_INTERVAL_MS = 4_000L
    private const val BACKOFF_BASE_MS = 30_000L
    private const val BACKOFF_MAX_MS = 10 * 60_000L
    private const val FAILURE_THRESHOLD = 3

    /** 公平锁：先到先得，避免某个子系统饿死。 */
    private val lock = ReentrantLock(true)

    /** 由 AppLoginState 注入：返回 true 表示密码已确认失效（全局熔断中）。 */
    @Volatile
    var passwordLatch: (() -> Boolean)? = null

    private var lastPostAt = 0L
    private var consecutiveFailures = 0
    private var backoffUntil = 0L

    /** 被闸门拒绝（熔断 / 退避期内）。调用方不应将其当作普通网络错误重试。 */
    class ThrottledException(message: String) : IOException(message)

    /**
     * 在闸门保护下执行一次「携带密码的认证请求」。
     * 会阻塞当前线程做间隔平滑（调用方均在 IO 线程，安全）。
     */
    @Throws(ThrottledException::class)
    fun <T> withCredentialPost(block: () -> T): T {
        checkAllowed()
        lock.lock()
        try {
            checkAllowed() // 等锁期间状态可能已变化（他人失败触发退避 / 密码熔断）
            val sinceLast = SystemClock.elapsedRealtime() - lastPostAt
            if (sinceLast in 0 until MIN_INTERVAL_MS) {
                val wait = MIN_INTERVAL_MS - sinceLast
                Log.d(TAG, "spacing credential post by ${wait}ms")
                try {
                    Thread.sleep(wait)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("登录请求等待被取消", e)
                }
            }
            lastPostAt = SystemClock.elapsedRealtime()
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun checkAllowed() {
        if (passwordLatch?.invoke() == true) {
            throw ThrottledException("密码已失效，已暂停自动登录以保护账号")
        }
        lock.lock()
        try {
            val now = SystemClock.elapsedRealtime()
            if (now < backoffUntil) {
                val remainingSeconds = ((backoffUntil - now) + 999L) / 1000L
                throw ThrottledException("登录尝试过于频繁，已暂停 $remainingSeconds 秒以保护账号")
            }
        } finally {
            lock.unlock()
        }
    }

    /** 凭据提交后认证成功时调用：清空失败计数与退避。 */
    fun recordSuccess() {
        lock.lock()
        try {
            consecutiveFailures = 0
            backoffUntil = 0L
        } finally {
            lock.unlock()
        }
    }

    /** 凭据提交后认证失败（错密 / CAS 拒绝）时调用：累计失败并按需进入退避。 */
    fun recordFailure() {
        lock.lock()
        try {
            val n = ++consecutiveFailures
            if (n >= FAILURE_THRESHOLD) {
                val shift = (n - FAILURE_THRESHOLD).coerceAtMost(5)
                val backoff = (BACKOFF_BASE_MS shl shift).coerceAtMost(BACKOFF_MAX_MS)
                backoffUntil = SystemClock.elapsedRealtime() + backoff
                Log.w(TAG, "consecutive failures=$n, backing off ${backoff / 1000}s")
            }
        } finally {
            lock.unlock()
        }
    }
}
