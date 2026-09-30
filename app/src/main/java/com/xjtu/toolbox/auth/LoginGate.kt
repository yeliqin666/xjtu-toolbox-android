package com.xjtu.toolbox.auth

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一个 backend 上和统一认证打交道的登录（网关认证、各站点 CAS 登录）同一时刻只跑一个：
 * 它们共用这一边的 TGC，并发时考勤拿不到回调令牌、风控还会连发几条短信。两个 backend 的 TGC
 * 互相独立，各用各的闸门，互不等待。
 *
 * 前台（用户在等）的登录排在后台静默登录前面：后台的拿锁前先让，拿到锁时发现有前台在等也让出。
 * 不可重入。
 */
class LoginGate {
    private val mutex = Mutex()
    private val foregroundWaiting = AtomicInteger()

    suspend fun <T> withLock(foreground: Boolean, block: suspend () -> T): T {
        if (foreground) {
            foregroundWaiting.incrementAndGet()
            try {
                mutex.lock()
            } finally {
                foregroundWaiting.decrementAndGet()
            }
        } else {
            while (true) {
                while (foregroundWaiting.get() > 0) delay(YIELD_POLL_MS)
                mutex.lock()
                if (foregroundWaiting.get() == 0) break
                mutex.unlock()
            }
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    private companion object {
        const val YIELD_POLL_MS = 50L
    }
}
