package com.xjtu.toolbox.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.auth.LibraryLogin
import com.xjtu.toolbox.auth.LoginState
import com.xjtu.toolbox.util.CredentialStore
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 图书馆定时抢座前台服务
 *
 * 执行流程：
 * 1. 显示前台通知 "正在准备抢座…"
 * 2. 重新认证图书馆 Session（提前 60 秒被闹钟唤醒）
 * 3. 精确等待到目标时刻
 * 4. 按优先级遍历目标座位，逐个尝试 bookSeat
 * 5. 失败重试（指数退避）
 * 6. 推送结果通知 → stopSelf()
 */
class SeatGrabService : Service() {

    companion object {
        private const val TAG = "SeatGrabService"
        const val CHANNEL_ID = "seat_grab_channel"
        const val CHANNEL_NAME = "图书馆抢座"
        private const val NOTIFICATION_ID_FOREGROUND = 5001
        private const val NOTIFICATION_ID_RESULT = 5002
        /** 服务总超时 3 分钟（shortService 限制）*/
        private const val TOTAL_TIMEOUT_MS = 3 * 60 * 1000L
    }

    // 每次 onCreate 新建 scope，避免 cancel 后复用问题
    private lateinit var serviceScope: CoroutineScope
    // 并发保护：防止重复启动
    private val isRunning = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")

        // 并发保护：如果已在执行中，忽略重复启动
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "抢座已在执行中，忽略重复启动")
            // 部分 ROM 要求每次 startForegroundService 后必须调用 startForeground
            startForeground(NOTIFICATION_ID_FOREGROUND, buildProgressNotification("抢座进行中..."))
            return START_NOT_STICKY
        }

        // 立即转为前台服务
        val foregroundNotification = buildProgressNotification("正在准备抢座...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID_FOREGROUND, foregroundNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID_FOREGROUND, foregroundNotification)
        }

        // 启动抢座协程（带总超时保护）
        serviceScope.launch {
            try {
                withTimeout(TOTAL_TIMEOUT_MS) {
                    executeGrab()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "抢座超时（${TOTAL_TIMEOUT_MS / 1000}s）")
                notifyResult(false, "抢座超时，请检查网络或重新设定")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "抢座异常", e)
                notifyResult(false, "抢座异常: ${e.message}")
            } finally {
                isRunning.set(false)
                SeatGrabScheduler.cancel(applicationContext) // 闹钟已触发，清除 PendingIntent 避免 isScheduled 过期
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    // ══════════════════════ 核心抢座逻辑 ══════════════════════

    private suspend fun executeGrab() {
        val config = SeatGrabConfigStore.load(applicationContext)
        if (config.targetSeats.isEmpty()) {
            notifyResult(false, "未配置目标座位")
            return
        }

        // ── 1. 认证预热 ──
        updateProgress("正在登录图书馆系统...")
        val libraryLogin = authenticateLibrary()
        if (libraryLogin == null) {
            return // authenticateLibrary 内部已发送通知
        }

        if (!libraryLogin.seatSystemReady) {
            val ok = libraryLogin.reAuthenticate()
            if (!ok) {
                notifyResult(false, "座位系统未就绪: ${libraryLogin.diagnosticInfo}")
                return
            }
        }

        val api = LibraryApi(libraryLogin)
        Log.i(TAG, "认证成功，座位系统已就绪")

        // ── 2. 连接预热（提前 GET /seat/ 预热 TCP） ──
        updateProgress("认证成功，等待抢座时刻...")
        try { api.getSeats(config.targetSeats.first().areaCode) } catch (_: Exception) {}

        // ── 3. 精确等待到目标时刻（使用 epoch millis 避免跨午夜问题） ──
        waitUntilTargetTime(config.triggerTimeStr)

        // ── 4. 执行抢座 ──
        updateProgress("正在抢座...")
        var lastResult: BookResult? = null

        for (retry in 0..config.maxRetries) {
            for (seat in config.targetSeats.sortedBy { it.priority }) {
                updateProgress("正在尝试 ${seat.seatId}... (第${retry + 1}轮)")
                Log.i(TAG, "尝试预约: seatId=${seat.seatId}, area=${seat.areaCode}, retry=$retry")

                val result = try {
                    api.bookSeat(seat.seatId, seat.areaCode, autoSwap = config.autoSwap)
                } catch (e: Exception) {
                    BookResult(false, "网络异常: ${e.message}")
                }

                Log.i(TAG, "预约结果: success=${result.success}, msg=${result.message}")

                if (result.success) {
                    notifyResult(true, result.message)
                    SeatGrabConfigStore.save(applicationContext, config.copy(enabled = false))
                    return
                }

                lastResult = result

                // 硬性限制（30分钟内不能重复预约），直接退出
                if (result.message.contains("30分钟") || result.message.contains("重复")) {
                    notifyResult(false, result.message)
                    return
                }
            }

            // 重试间隔（指数退避：2s → 4s → 8s，上限 30s）
            if (retry < config.maxRetries) {
                val delayMs = (config.retryIntervalMs * (1L shl retry.coerceAtMost(3)))
                    .coerceAtMost(30_000L)
                delay(delayMs)
            }
        }

        notifyResult(false, lastResult?.message ?: "所有目标座位均不可用")
    }

    // ══════════════════════ 认证 ══════════════════════

    /**
     * 创建 LibraryLogin 并认证（同步方法，调用方需保证在 IO 线程）。
     * 针对 MFA/验证码/账户选择等后台无法完成的状态给出明确提示。
     */
    private fun authenticateLibrary(): LibraryLogin? {
        val credentialStore = CredentialStore(applicationContext)
        val creds = credentialStore.load()
        val visitorId = credentialStore.loadFpVisitorId()

        if (creds == null) {
            Log.w(TAG, "凭据未保存")
            notifyResult(false, "账号凭据未保存，请先在应用内登录一次")
            return null
        }

        val (username, password) = creds
        try {
            val login = LibraryLogin(visitorId = visitorId)
            val result = login.login(username, password)

            when (result.state) {
                LoginState.SUCCESS -> {
                    // 登录成功，executeGrab() 会后续检查 seatSystemReady
                    return login
                }
                LoginState.REQUIRE_MFA -> {
                    notifyResult(false, "登录需要两步验证(MFA)，后台无法完成。\n请先在应用内登录一次以信任此设备")
                    return null
                }
                LoginState.REQUIRE_CAPTCHA -> {
                    notifyResult(false, "登录需要验证码，后台无法完成。\n请先在应用内重新登录")
                    return null
                }
                LoginState.REQUIRE_ACCOUNT_CHOICE -> {
                    notifyResult(false, "登录需要选择账户类型，请先在应用内登录一次")
                    return null
                }
                LoginState.FAIL -> {
                    notifyResult(false, "图书馆登录失败: ${result.message.ifEmpty { "未知原因" }}")
                    return null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "login failed", e)
            notifyResult(false, "图书馆登录异常: ${e.message}")
        }

        return null
    }

    // ══════════════════════ 等待 ══════════════════════

    /**
     * 精确等待到目标时刻。
     * 使用 epoch millis 做时间比较，正确处理跨午夜场景。
     */
    private suspend fun waitUntilTargetTime(triggerTimeStr: String) {
        val targetLocalTime = try {
            LocalTime.parse(triggerTimeStr, DateTimeFormatter.ofPattern("HH:mm:ss"))
        } catch (_: Exception) {
            LocalTime.of(22, 0, 0)
        }

        // 计算最近的目标 epoch millis（如果今天已过则取明天）
        var targetDateTime = LocalDate.now().atTime(targetLocalTime)
        val targetEpochMs = targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val finalTargetMs = if (targetEpochMs < System.currentTimeMillis() - 600_000L) {
            // 已过了 10 分钟以上 → 算明天的（放宽阈值以兼容 Doze/OEM 延迟）
            targetDateTime.plusDays(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } else {
            targetEpochMs
        }

        while (true) {
            val remainMs = finalTargetMs - System.currentTimeMillis()

            if (remainMs <= 0) {
                Log.i(TAG, "已到达目标时刻 $triggerTimeStr")
                break
            }

            when {
                remainMs > 5000 -> {
                    updateProgress("等待中... ${remainMs / 1000}秒后开抢")
                    delay(1000)
                }
                remainMs > 100 -> {
                    // 100ms~5s: 用 delay 精细等待
                    delay(10)
                }
                else -> {
                    // 最后 100ms: busy-wait 保证精度
                    while (System.currentTimeMillis() < finalTargetMs) {
                        Thread.yield() // 让出 CPU 时间片，减少电池消耗
                    }
                    break
                }
            }
        }
    }

    // ══════════════════════ 通知 ══════════════════════

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "图书馆定时抢座通知"
            enableLights(true)
            enableVibration(true)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildProgressNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_seat)
            .setContentTitle("图书馆抢座")
            .setContentText(text)
            .setContentIntent(tapPending)
            .setOngoing(true)
            .build()
    }

    private fun updateProgress(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID_FOREGROUND, buildProgressNotification(text))
    }

    private fun notifyResult(success: Boolean, message: String) {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (success) "✓ 抢座成功" else "✗ 抢座失败"

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_seat)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID_RESULT, notification)

        Log.i(TAG, "通知已推送: $title — $message")
    }
}
