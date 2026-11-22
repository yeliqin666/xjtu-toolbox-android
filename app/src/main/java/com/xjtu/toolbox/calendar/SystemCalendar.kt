package com.xjtu.toolbox.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.xjtu.toolbox.schedule.SystemCalendarEvent
import com.xjtu.toolbox.util.CampusTimeZone
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 读系统日历（各日历账户里的日程），合进日程页的今日时间轴和「接下来」。
 *
 * 默认关：要用户在设置里打开并授予「读取日历」权限。只读，不往系统日历里写任何东西。
 */
object SystemCalendar {
    private const val TAG = "SystemCalendar"
    private const val PREFS = "system_calendar"
    private const val KEY_ENABLED = "enabled"

    /** 本 App「导出日历 (ICS)」写的 UID 后缀（见 ScheduleExport）。导进系统日历的那份课表不再显示一遍。 */
    private const val OWN_UID_SUFFIX = "@xjtu-toolbox"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private val PROJECTION = arrayOf(
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        CalendarContract.Instances.UID_2445,
        CalendarContract.Instances.SELF_ATTENDEE_STATUS,
    )

    /**
     * [from]..[to] 之间的日程，按开始时间排好。走 `Instances` 表：重复日程已经按次展开。
     * 只取用户在日历 App 里设为显示的日历；已取消的、自己已拒绝的邀请不算。
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
            context.contentResolver.query(uri, PROJECTION, selection, args, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val uid = c.getString(6).orEmpty()
                            if (uid.endsWith(OWN_UID_SUFFIX)) continue
                            if (!c.isNull(7) && c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                            val allDay = c.getInt(3) == 1
                            val begin = Instant.fromEpochMilliseconds(c.getLong(1))
                            val end = Instant.fromEpochMilliseconds(c.getLong(2)).coerceAtLeast(begin)
                            add(
                                SystemCalendarEvent(
                                    title = c.getString(0)?.trim().orEmpty().ifEmpty { "（无标题）" },
                                    begin = if (allDay) begin.allDayToCampus() else begin,
                                    end = if (allDay) end.allDayToCampus() else end,
                                    allDay = allDay,
                                    location = c.getString(4)?.trim().orEmpty(),
                                    calendarName = c.getString(5)?.trim().orEmpty(),
                                ),
                            )
                        }
                    }
                }
                .orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "read calendar failed", e)
            emptyList()
        }
    }

    /** 全天日程在日历库里存的是 UTC 那天的 0 点；换成校园时区同一天的 0 点。 */
    private fun Instant.allDayToCampus(): Instant =
        toLocalDateTime(TimeZone.UTC).date.atStartOfDayIn(CampusTimeZone)
}
