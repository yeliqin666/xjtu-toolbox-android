package com.xjtu.toolbox

import android.app.Application
import android.content.Context
import com.xjtu.toolbox.error.CrashReporter
import com.xjtu.toolbox.error.ErrorReporting
import com.xjtu.toolbox.error.FileErrorReporter
import com.xjtu.toolbox.notification.AppNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。仅承担"启动即建"职责：
 *
 * - 8.0+ NotificationChannel 必须先于第一条通知注册，否则系统丢弃。
 *   在 [Application.onCreate] 建一次保证比任何业务 push 都早。
 * - 错误上报接口注入：[FileErrorReporter] 落 cache/error_reports/，
 *   后续接入 Crashlytics 替换实现即可。
 *
 * - 未捕获异常由 [CrashReporter] 落盘，下次启动匿名上报。
 *
 * 其余启动钩子（性能打点 / 渠道开关）保持空。
 */
class XjtuApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 已下线功能留在本机的数据。功能删了，数据留着既无用处也不该留（交晓智会话里是
     * 用户和学校 AI 的对话原文，旧考勤快照里是考勤记录），每次启动顺手清掉；
     * 都不存在时只是一次 listFiles 加两次 exists。
     */
    private fun removeRetiredFeatureData() {
        filesDir.listFiles { f -> f.isDirectory && f.name.startsWith("jiaoxiaozhi_sessions") }
            ?.forEach { dir -> runCatching { dir.deleteRecursively() } }
        // 旧版考勤快照（AttendanceCache，已随旧考勤系统移除）
        listOf("attendance_cache_undergraduate", "attendance_cache_postgraduate").forEach { name ->
            if (java.io.File(java.io.File(applicationInfo.dataDir, "shared_prefs"), "$name.xml").exists()) {
                runCatching { deleteSharedPreferences(name) }
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 进程里最早的钩子，早于所有 ContentProvider（WorkManager 自动初始化后可能立刻
        // 跑 Worker）和 onCreate。换包后的旧格式缓存必须在任何代码读到它之前清掉，见方法注释。
        com.xjtu.toolbox.data.DataCache.clearIfPackageChanged(base)
        // 首帧 AppLoginStateViewModel 要读账号表、协议版本；趁装 Provider 的空档在后台先解锁
        com.xjtu.toolbox.data.SecurePrefs.prewarm(
            base,
            com.xjtu.toolbox.account.AccountStore.FILE_NAME,
            com.xjtu.toolbox.data.CredentialStore.FILE_NAME,
        )
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        appScope.launch { CrashReporter.uploadPending(this@XjtuApp) }
        appScope.launch { removeRetiredFeatureData() }
        AppNotificationChannels.ensureChannels(this)
        ErrorReporting.install(FileErrorReporter(this))
        // 后台调度要读账号（AccountStore → 加密存储首次打开要走 keystore），不占主线程。
        // 顺带预热了 SecurePrefs 的缓存，首帧里界面再取账号时直接命中。
        appScope.launch {
            com.xjtu.toolbox.notification.NoticeWatchScheduler.apply(this@XjtuApp)
            com.xjtu.toolbox.notification.ScheduleWatchScheduler.apply(this@XjtuApp)
            com.xjtu.toolbox.notification.LmsDeadlineScheduler.apply(this@XjtuApp)
        }
    }
}