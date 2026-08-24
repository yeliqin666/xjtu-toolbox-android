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

    /**
     * 间隔平滑是**自适应**的：
     * - 上一次凭据提交成功（consecutiveFailures == 0）→ [MIN_INTERVAL_OK_MS]。
     *   风控针对的是「短时间大量失败提交」这种撞库形态，正常成功登录之间不需要秒级静默；
     *   全局串行本身已经杜绝了并发爆发。
     * - 一旦出现失败 → 立刻回到 [MIN_INTERVAL_FAIL_MS]，叠加 ≥3 次后的指数退避。
     */
    private const val MIN_INTERVAL_OK_MS = 800L
    private const val MIN_INTERVAL_FAIL_MS = 4_000L
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
     *
     * @param sameFlow true 表示本次提交与上一次提交属于**同一登录流程**
     *（如 mfa/detect 之后紧跟的登录表单 POST——浏览器也是连续发出的两个请求）。
     * 同流程内不再做间隔平滑，只保留串行 + 熔断/退避检查；
     * 最小间隔仅约束两次**独立登录流程**之间的节奏。
     */
    @Throws(ThrottledException::class)
    fun <T> withCredentialPost(sameFlow: Boolean = false, block: () -> T): T {
        checkAllowed()
        lock.lock()
        try {
            checkAllowed() // 等锁期间状态可能已变化（他人失败触发退避 / 密码熔断）
            val minInterval = if (consecutiveFailures == 0) MIN_INTERVAL_OK_MS else MIN_INTERVAL_FAIL_MS
            val sinceLast = SystemClock.elapsedRealtime() - lastPostAt
            if (!sameFlow && sinceLast in 0 until minInterval) {
                val wait = minInterval - sinceLast
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

    /**
     * 当前是否被闸门挡着，被挡的话给出人话原因；没被挡返回 null。
     *
     * 和 [checkAllowed] 的区别是**不抛异常**。给后台批量任务用：与其让它们一个个去撞锁、
     * 每个等满间隔、最后全部失败，不如开跑前先问一句，被挡就整轮不跑。
     */
    fun blockedReason(): String? {
        if (passwordLatch?.invoke() == true) return "密码已失效，自动登录已暂停"
        lock.lock()
        try {
            val remain = backoffUntil - SystemClock.elapsedRealtime()
            if (remain > 0) return "登录退避中，还需 ${(remain + 999) / 1000} 秒"
        } finally {
            lock.unlock()
        }
        return null
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
