package com.xjtu.toolbox.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/**
 * 一个 backend 上和统一认证打交道的登录（网关认证、各站点 CAS 登录）同一时刻只跑一个：
 * 它们共用这一边的 TGC，并发时考勤拿不到回调令牌、风控还会连发几条短信。
 *
 * 两个 backend 的 TGC 互相独立，各用各的闸门、互不等待。按账号算的风控仍然兜得住：
 * 提交密码这一步由 [CasGate] 全局串行并限速，两边共用同一个设备指纹。
 *
 * 前台（用户在等）的登录排在后台静默登录前面：后台的拿锁前先让，拿到锁时发现有前台在等也让出。
 * 不可重入。
 */
class LoginGate {
    private val mutex = Mutex()
    private val foregroundWaiting = MutableStateFlow(0)

    suspend fun <T> withLock(foreground: Boolean, block: suspend () -> T): T {
        if (foreground) {
            foregroundWaiting.update { it + 1 }
            try {
                mutex.lock()
            } finally {
                foregroundWaiting.update { it - 1 }
            }
        } else {
            while (true) {
                foregroundWaiting.first { it == 0 }
                mutex.lock()
                if (foregroundWaiting.value == 0) break
                mutex.unlock()
            }
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
