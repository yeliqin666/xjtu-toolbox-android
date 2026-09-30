package com.xjtu.toolbox.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 装完新包立刻重画桌面小组件。
 *
 * 系统换包后把小组件重置成初始布局却不发更新，定好的刷新闹钟也随之作废：
 * 课表、校园卡要等 30 分钟一次的周期更新，通知小组件没有周期更新，会一直空到用户打开 App。
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            ScheduleWidgetUpdater.requestUpdate(context, resetToToday = false)
            CampusCardWidgetUpdater.requestUpdate(context)
            NoticeWidgetUpdater.requestUpdate(context)
        }
    }
}
