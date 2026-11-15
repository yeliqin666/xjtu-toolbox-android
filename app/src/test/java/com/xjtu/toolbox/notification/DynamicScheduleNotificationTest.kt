package com.xjtu.toolbox.notification

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.CourseItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = Application::class)
class DynamicScheduleNotificationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = 1_800_000_000_000L
    private fun plan(ongoing: Boolean = false): DynamicSchedulePlan {
        val item = DynamicScheduleItem(
            CourseItem(courseName = "高等数学", location = "中二-1200"), now + 60_000L, now + 6_000_000L, 480, 580,
        )
        return DynamicSchedulePlan(item, ongoing, 2, 1, if (ongoing) item.endAt else item.startAt)
    }

    @Before fun channels() = AppNotificationChannels.ensureChannels(context)

    @Test fun `静默私密渠道 不弹窗不计角标`() {
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(AppNotificationChannels.CHANNEL_DYNAMIC_SCHEDULE)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.canShowBadge())
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
    }

    @Test fun `使用系统倒计时 开始与结束切换 且过期自动撤下`() {
        for (ongoing in listOf(false, true)) {
            val plan = plan(ongoing)
            val notification = DynamicScheduleNotifier.build(context, plan, now)!!
            assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            assertEquals(if (ongoing) plan.current!!.endAt else plan.current!!.startAt, notification.`when`)
            assertEquals(plan.nextRefreshAt - now, notification.timeoutAfter)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertFalse(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("2 项课程 · 1 项日程"))
            assertEquals(AppRoute.Schedule.id, shadowOf(notification.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_LAUNCH_ROUTE))
        }
    }

    @Test fun `无下一项或已过边界不生成旧通知`() {
        assertNull(DynamicScheduleNotifier.build(context, plan().copy(current = null), now))
        assertNull(DynamicScheduleNotifier.build(context, plan().copy(nextRefreshAt = now), now))
    }

    @Test fun `只占一个通知 开始后更新而非堆叠 取消连同闹钟清理`() {
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        DynamicScheduleNotifier.post(context, plan(), now)
        DynamicScheduleNotifier.post(context, plan(true), now)
        assertEquals(1, shadowOf(nm).allNotifications.size)
        DynamicScheduleScheduler.schedule(context, now + 60_000L)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertNotNull(alarms.peekNextScheduledAlarm())
        DynamicScheduleScheduler.cancel(context)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test fun `未授予精确权限仍可排程 授权后改为精确闹钟`() {
        val manager = context.getSystemService(AlarmManager::class.java)
        val alarms = shadowOf(manager)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        DynamicScheduleScheduler.schedule(context, now + 60_000L)
        assertEquals(now + 60_000L, alarms.peekNextScheduledAlarm()!!.triggerAtMs)
        assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, alarms.peekNextScheduledAlarm()!!.windowLengthMs)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        DynamicScheduleScheduler.schedule(context, now + 120_000L)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarms.peekNextScheduledAlarm()!!.windowLengthMs)
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test fun `通知权限撤回后不再展示且清掉已有内容`() {
        val app = RuntimeEnvironment.getApplication()
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        DynamicScheduleNotifier.post(context, plan(), now)
        assertEquals(1, shadowOf(nm).allNotifications.size)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        DynamicScheduleNotifier.post(context, plan(), now)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun `渠道被关闭时不绕过 其他提醒渠道仍独立`() {
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        nm.createNotificationChannel(android.app.NotificationChannel(
            AppNotificationChannels.CHANNEL_DYNAMIC_SCHEDULE, "动态课程与日程", NotificationManager.IMPORTANCE_NONE,
        ))
        DynamicScheduleNotifier.post(context, plan(), now)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(AppNotificationChannels.CHANNEL_REMINDER).importance)
        assertFalse(DynamicScheduleScheduler.isEnabled(context))
    }
}
