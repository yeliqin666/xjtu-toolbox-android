package com.xjtu.toolbox

import android.app.Application
import com.xjtu.toolbox.error.ErrorReporting
import com.xjtu.toolbox.error.FileErrorReporter
import com.xjtu.toolbox.notification.AppNotificationChannels

/**
 * 应用入口。仅承担"启动即建"职责：
 *
 * - 8.0+ NotificationChannel 必须先于第一条通知注册，否则系统丢弃。
 *   在 [Application.onCreate] 建一次保证比任何业务 push 都早。
 * - 错误上报接口注入：[FileErrorReporter] 落 cache/error_reports/，
 *   后续接入 Crashlytics 替换实现即可。
 * - 后台预热加密存储与屁岱贴合表：前者创建要做 Keystore 派生（实测首次约 137ms），
 *   后者类加载时构建约 80ms，而两者都在首帧路径上。见 [ColdStartPreloader]。
 *
 * 其余启动钩子（崩溃日志 / 性能打点 / 渠道开关）保持空。
 */
class XjtuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppNotificationChannels.ensureChannels(this)
        ErrorReporting.install(FileErrorReporter(this))
        com.xjtu.toolbox.notification.NoticeWatchScheduler.apply(this)
        // 后台预热，抵消首帧路径上的 Keystore 派生与贴合表构建
        com.xjtu.toolbox.startup.ColdStartPreloader.preloadInBackground(this)
    }
}