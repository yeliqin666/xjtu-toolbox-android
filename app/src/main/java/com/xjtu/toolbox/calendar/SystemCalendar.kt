package com.xjtu.toolbox.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.xjtu.toolbox.schedule.SystemCalendarEvent
import com.xjtu.toolbox.schedule.SystemCalendarRow
import com.xjtu.toolbox.schedule.SystemCalendarRules
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * 读系统日历（各日历账户里的日程），合进日程页的今日和「接下来」。
 *
 * 默认关：要用户在设置里打开并授予「读取日历」权限。只读，不往系统日历里写任何东西。
 */
object SystemCalendar {
    private const val TAG = "SystemCalendar"
    private const val PREFS = "system_calendar"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private val PROJECTION = arrayOf(
        CalendarContract.Instances.TITLE, // 0
        CalendarContract.Instances.BEGIN, // 1
        CalendarContract.Instances.END, // 2
        CalendarContract.Instances.ALL_DAY, // 3
        CalendarContract.Instances.EVENT_LOCATION, // 4
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME, // 5
        CalendarContract.Instances.UID_2445, // 6
        CalendarContract.Instances.SELF_ATTENDEE_STATUS, // 7
        CalendarContract.Instances.STATUS, // 8
        CalendarContract.Instances.VISIBLE, // 9
    )

    /**
     * [from]..[to] 之间（和这段时间有交集即算，含在 [from] 之前开始、跨进来的）的日程，按开始时间排好。
     * 走 `Instances` 表：重复日程由日历库按这个时间段展开成每一次。
     * 过滤与全天换算见 [SystemCalendarRules.toEvents]；SQL 里也先筛掉隐藏日历和已取消的，少搬数据。
     * 没打开或没授权时返回空表；读失败也只记日志、返回空表，不影响日程页其它内容。
     */
    fun events(context: Context, from: Instant, to: Instant): List<SystemCalendarEvent> {
        if (!isEnabled(context) || !hasPermission(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilliseconds())
            ContentUris.appendId(it, to.toEpochMilliseconds())
        }.build()
        val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
            "(${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ?)"
        val args = arrayOf(CalendarContract.Events.STATUS_CANCELED.toString())
        return try {
            val rows = context.contentResolver.query(uri, PROJECTION, selection, args, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(
                                SystemCalendarRow(
                                    title = c.getString(0),
                                    beginMillis = c.getLong(1),
                                    endMillis = if (c.isNull(2)) c.getLong(1) else c.getLong(2),
                                    allDay = c.getInt(3) == 1,
                                    location = c.getString(4),
                                    calendarName = c.getString(5),
                                    uid = c.getString(6),
                                    selfAttendeeStatus = if (c.isNull(7)) null else c.getInt(7),
                                    status = if (c.isNull(8)) null else c.getInt(8),
                                    visible = c.isNull(9) || c.getInt(9) == 1,
                                ),
                            )
                        }
                    }
                }
                .orEmpty()
            SystemCalendarRules.toEvents(rows, deviceZone = TimeZone.currentSystemDefault())
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "read calendar failed", e)
            emptyList()
        }
    }

    /**
     * 日历库变化信号：收集时（且已打开、已授权）在 `CalendarContract.CONTENT_URI` 上注册 [ContentObserver]，
     * 连带子路径（Events / Instances / Calendars）；取消收集时注销。没打开或没授权时是空流。
     */
    fun changes(context: Context): Flow<Unit> = flow {
        if (!isEnabled(context) || !hasPermission(context)) return@flow
        emitAll(
            callbackFlow {
                val resolver = context.contentResolver
                val observer = object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
                val registered = try {
                    resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
                    true
                } catch (e: SecurityException) {
                    Log.w(TAG, "observe calendar failed", e)
                    false
                }
                if (!registered) close()
                awaitClose { if (registered) resolver.unregisterContentObserver(observer) }
            },
        )
    }
}
