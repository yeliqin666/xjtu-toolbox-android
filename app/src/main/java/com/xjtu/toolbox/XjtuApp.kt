package com.xjtu.toolbox

import android.app.Application
import com.xjtu.toolbox.notification.AppNotificationChannels

/**
 * 应用入口。仅承担"启动即建"职责：
 *
 * - 8.0+ NotificationChannel 必须先于第一条通知注册，否则系统丢弃。
 *   在 [Application.onCreate] 建一次保证比任何业务 push 都早。
 *
 * 其余启动钩子（崩溃日志 / 性能打点 / 渠道开关）保持空，给后续 PR 留增量。
 */
class XjtuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppNotificationChannels.ensureChannels(this)
    }
}