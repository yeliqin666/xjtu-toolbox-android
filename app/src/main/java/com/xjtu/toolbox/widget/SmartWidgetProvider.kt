package com.xjtu.toolbox.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.account.restoreIfNeeded
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.util.CampusZoneId
import com.xjtu.toolbox.util.RequestCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** 卡片此刻要显示的一屏：场景标签、主信息、补充、底部去处和点开去哪儿。 */
internal data class SmartWidgetScene(
    val label: String,
    val title: String,
    val detail: String,
    val action: String,
    val route: AppRoute,
)

/**
 * 智能卡片（#408）：只读本机缓存，按此刻挑一屏。
 * - 课前 [CLASS_LEAD_MIN] 分钟到下课：这节课，点开看考勤（考勤要登录站点，卡片里不查）；
 * - 饭点：余额，点开付款码（付款码不画在桌面上，谁都能扫）；
 * - 其他时间：今天下一节课，没有了就显示余额。
 */
object SmartWidgetUpdater {
    const val ACTION_REFRESH = "com.xjtu.toolbox.widget.ACTION_REFRESH_SMART_WIDGET"
    private const val CLASS_LEAD_MIN = 20

    /** 早、午、晚饭的窗口（距 00:00 的分钟）。 */
    private val MEALS = listOf(
        Triple("早饭时间", 6 * 60 + 30, 9 * 60),
        Triple("午饭时间", 11 * 60, 13 * 60 + 30),
        Triple("晚饭时间", 17 * 60, 19 * 60 + 30),
    )

    fun requestUpdate(context: Context) {
        context.sendBroadcast(Intent(context, SmartWidgetProvider::class.java).setAction(ACTION_REFRESH))
    }

    internal fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        com.xjtu.toolbox.account.AccountContext.restoreIfNeeded()
        val now = LocalDateTime.now(CampusZoneId)
        val nowMinute = now.hour * 60 + now.minute
        val courses = runCatching { ScheduleWidgetUpdater.todayCourses(context) }.getOrDefault(emptyList())
        val scene = sceneOf(courses, nowMinute, balanceText(context))
        val launch = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, scene.route.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, RequestCodes.SMART_WIDGET, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_smart)
            views.setTextViewText(R.id.widget_smart_label, scene.label)
            views.setTextViewText(R.id.widget_smart_title, scene.title)
            views.setTextViewText(R.id.widget_smart_detail, scene.detail)
            views.setTextViewText(R.id.widget_smart_action, scene.action)
            views.setOnClickPendingIntent(R.id.widget_smart_root, pending)
            manager.updateAppWidget(id, views)
        }
        scheduleNext(context, courses, now)
    }

    internal fun sceneOf(courses: List<WidgetCourse>, nowMinute: Int, balance: String): SmartWidgetScene {
        val inClass = courses.firstOrNull { nowMinute >= it.startMinute - CLASS_LEAD_MIN && nowMinute < it.endMinute }
        if (inClass != null) {
            val started = nowMinute >= inClass.startMinute
            val label = if (started) "上课中" else "${inClass.startMinute - nowMinute} 分钟后上课"
            return SmartWidgetScene(label, inClass.name, timeAndPlace(inClass), "查看考勤 ›", AppRoute.Attendance)
        }
        MEALS.firstOrNull { (_, start, end) -> nowMinute in start until end }?.let { (label, _, _) ->
            return SmartWidgetScene(label, "付款码", "校园卡余额 $balance", "打开付款码 ›", AppRoute.PaymentCode)
        }
        val next = courses.firstOrNull { it.startMinute > nowMinute }
        if (next != null) {
            return SmartWidgetScene("下一节 ${clock(next.startMinute)}", next.name, timeAndPlace(next), "查看日程 ›", AppRoute.Schedule)
        }
        val label = if (courses.isEmpty()) "今天没课" else "今天的课上完了"
        return SmartWidgetScene(label, "校园卡 $balance", "饭点会自动换成付款码入口", "查看校园卡 ›", AppRoute.CampusCard)
    }

    /** 下一个场景边界：课前提醒起点、上课、下课、饭点起止；今天没有了就到明天零点。 */
    private fun scheduleNext(context: Context, courses: List<WidgetCourse>, now: LocalDateTime) {
        val nowMinute = now.hour * 60 + now.minute
        val boundaries = courses.flatMap { listOf(it.startMinute - CLASS_LEAD_MIN, it.startMinute, it.endMinute) } +
            MEALS.flatMap { listOf(it.second, it.third) }
        val at = boundaries.filter { it > nowMinute }.minOrNull()
            ?.let { now.toLocalDate().atStartOfDay().plusMinutes(it.toLong()) }
            ?: now.toLocalDate().plusDays(1).atStartOfDay()
        val millis = at.atZone(CampusZoneId).toInstant().toEpochMilli()
        // 非精准闹钟就够：场景切换晚一两分钟无妨，不必为此申请精准闹钟权限
        context.getSystemService(AlarmManager::class.java)
            ?.setWindow(AlarmManager.RTC, millis, 2 * 60_000L, refreshIntent(context))
    }

    internal fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(refreshIntent(context))
    }

    private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RequestCodes.SMART_WIDGET_REFRESH,
        Intent(context, SmartWidgetProvider::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun balanceText(context: Context): String {
        val balance = com.xjtu.toolbox.card.CampusCardCache.cardPrefs(context).getFloat("card_balance_cache", -1f)
        return if (balance < 0f) "--" else "¥%.2f".format(balance)
    }

    private fun timeAndPlace(c: WidgetCourse): String =
        listOf("${clock(c.startMinute)}–${clock(c.endMinute)}", c.location).filter { it.isNotBlank() }.joinToString(" · ")

    private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
}

/** 渲染要读缓存、查 Room，goAsync 后放到 IO 线程做，和课表小组件一样。 */
class SmartWidgetProvider : AppWidgetProvider() {
    private fun updateAsync(context: Context, ids: () -> IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmartWidgetUpdater.update(app, AppWidgetManager.getInstance(app), ids())
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAsync(context) { appWidgetIds }
    }

    override fun onDisabled(context: Context) = SmartWidgetUpdater.cancel(context)

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        if (intent?.action == SmartWidgetUpdater.ACTION_REFRESH) {
            updateAsync(context) {
                AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, SmartWidgetProvider::class.java))
            }
        }
    }
}
