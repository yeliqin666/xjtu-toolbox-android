package com.xjtu.toolbox

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        appScope.launch { CrashReporter.uploadPending(this@XjtuApp) }
        // 先于一切读缓存的代码（含下面的后台调度），见方法注释
        com.xjtu.toolbox.util.DataCache.clearIfPackageChanged(this)
        AppNotificationChannels.ensureChannels(this)
        ErrorReporting.install(FileErrorReporter(this))
        com.xjtu.toolbox.notification.NoticeWatchScheduler.apply(this)
        com.xjtu.toolbox.notification.ScheduleWatchScheduler.apply(this)
        com.xjtu.toolbox.notification.LmsDeadlineScheduler.apply(this)
    }
}