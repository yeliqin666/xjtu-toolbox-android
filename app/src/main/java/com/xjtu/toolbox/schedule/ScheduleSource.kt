package com.xjtu.toolbox.schedule

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.xjtu.toolbox.attendance.AttendanceScheduleApi
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.jwapp.JwappScheduleApi
import com.xjtu.toolbox.util.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ScheduleSource"

/**
 * 当前学期课表从哪个系统拉。
 *
 * 三套系统的数据各有取舍：教务一次给整学期，最快也最全；移动教务和考勤系统都只能
 * 按周查，整学期要发十几个请求，但它们是任课老师和签到设备实际用的那一份，
 * 学校临时调课时通常更新得更早。
 */
enum class ScheduleSource(val key: String, val label: String, val summary: String) {
    JWXT(CredentialStore.SCHEDULE_SOURCE_JWXT, "教务系统", "一次拉整学期，最快"),
    JWAPP(CredentialStore.SCHEDULE_SOURCE_JWAPP, "移动教务", "按周拉，含分钟级上下课时间"),
    BKKQ(CredentialStore.SCHEDULE_SOURCE_BKKQ, "考勤系统", "按周拉，与刷卡签到同一份排课");

    companion object {
        val DEFAULT = JWAPP

        fun fromKey(key: String?): ScheduleSource = entries.firstOrNull { it.key == key } ?: DEFAULT

        fun of(context: Context): ScheduleSource =
            fromKey(CredentialStore(context).scheduleSource)
    }
}

/**
 * 按用户选的来源取整学期课表。
 *
 * 两条硬规则：
 * 1. **历史学期永远走教务**。移动教务和考勤系统都只认当前学期，查别的学期要么报错，
 *    要么把当前学期的课当成那个学期的返回——后者比报错更糟。这里不靠外部判断"是不是
 *    当前学期"，而是问来源自己当前学期是哪个，对不上就换教务。
 * 2. **非教务源出任何问题都退回教务**。用户看不到课表是最坏的结果，比看到一份来自
 *    次选来源的课表糟得多；换源本身也不该成为看不到课表的新理由。
 */
object ScheduleSourceRouter {

    suspend fun getSchedule(
        context: Context,
        jwxt: ScheduleApi,
        termCode: String,
        manager: SessionManager?,
        accountType: AccountType,
        userInitiated: Boolean = false,
    ): List<CourseItem> {
        val source = ScheduleSource.of(context)
        if (source == ScheduleSource.JWXT || manager == null) return jwxtSchedule(context, jwxt, termCode)

        val alternative = try {
            withContext(Dispatchers.IO) {
                when (source) {
                    ScheduleSource.JWAPP -> fromJwapp(manager, termCode, userInitiated)
                    ScheduleSource.BKKQ -> fromBkkq(manager, accountType, termCode, userInitiated)
                    ScheduleSource.JWXT -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "${source.label}取课表失败，退回教务：${e.javaClass.simpleName} ${e.message}")
            null
        }

        // 空列表也算没取到：整学期一节课都没有几乎只会是查错了学期，
        // 而教务对同一个学期真没课时也返回空，退过去不会把有课变成没课。
        val usable = alternative?.takeIf { it.courses.isNotEmpty() }
        if (usable == null) return jwxtSchedule(context, jwxt, termCode)
        remember(context, source)
        rememberChanges(context, termCode, usable.changes)
        return usable.courses
    }

    private data class SourceResult(val courses: List<CourseItem>, val changes: List<ScheduleChangeEvent> = emptyList())

    private suspend fun jwxtSchedule(
        context: Context,
        jwxt: ScheduleApi,
        termCode: String,
    ): List<CourseItem> = withContext(Dispatchers.IO) { jwxt.getSchedule(termCode) }
        .also {
            remember(context, ScheduleSource.JWXT)
            // jwxt 不返回变更记录；退回教务时把上一份非教务源留下的调课理由一并清掉，
            // 否则页面/通知会拿着已经不对应当前数据的旧理由去匹配新课表。
            rememberChanges(context, termCode, emptyList())
        }

    /**
     * 最近一次课表**实际**来自哪个系统。
     *
     * 与用户在设置里选的那个未必相同：选了移动教务但这次退回了教务，数据形状就是教务的。
     * [ScheduleDiff] 按这个值分开存快照，否则退回的那一次会把每门课都报成"变了"。
     */
    fun servedSource(context: Context): ScheduleSource =
        ScheduleSource.fromKey(
            context.applicationContext
                .getSharedPreferences(PREFS_SERVED, Context.MODE_PRIVATE)
                .getString(KEY_SERVED, null)
        )

    private fun remember(context: Context, source: ScheduleSource) {
        context.applicationContext
            .getSharedPreferences(PREFS_SERVED, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVED, source.key).apply()
    }

    private const val PREFS_SERVED = "schedule_source"
    private const val KEY_SERVED = "served"

    /**
     * 这学期最近一次从 jwapp 拉到的调课/停课事件（含官方备注）。
     *
     * 只有 jwapp 会填；教务和考勤走的分支会用空表把上一份覆盖掉，
     * 见 [jwxtSchedule] 和 [getSchedule] 里对非 usable 分支的处理。
     */
    fun changeEvents(context: Context, termCode: String): List<ScheduleChangeEvent> {
        if (termCode.isBlank()) return emptyList()
        val json = context.applicationContext
            .getSharedPreferences(PREFS_CHANGES, Context.MODE_PRIVATE)
            .getString(termCode, null) ?: return emptyList()
        return runCatching {
            gson.fromJson(json, Array<ScheduleChangeEvent>::class.java)?.toList()
        }.getOrNull().orEmpty()
    }

    private fun rememberChanges(context: Context, termCode: String, events: List<ScheduleChangeEvent>) {
        if (termCode.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_CHANGES, Context.MODE_PRIVATE)
        if (events.isEmpty()) {
            prefs.edit().remove(termCode).apply()
        } else {
            prefs.edit().putString(termCode, gson.toJson(events)).apply()
        }
    }

    private val gson = Gson()
    private const val PREFS_CHANGES = "schedule_changes"

    private suspend fun fromJwapp(
        manager: SessionManager,
        termCode: String,
        userInitiated: Boolean,
    ): SourceResult? {
        val site = manager.siteOrNull(LoginType.JWAPP, userInitiated) ?: return null
        val api = JwappScheduleApi(site)
        val basis = api.basis()
        if (!sameTerm(basis.termCode, termCode)) {
            Log.d(TAG, "移动教务当前学期是 ${basis.termCode}，要查的是 $termCode，走教务")
            return null
        }
        val result = api.getSchedule(basis.termCode, basis.maxWeekNum)
        return SourceResult(result.courses, result.changeEvents)
    }

    private suspend fun fromBkkq(
        manager: SessionManager,
        accountType: AccountType,
        termCode: String,
        userInitiated: Boolean,
    ): SourceResult? {
        val type = if (accountType == AccountType.POSTGRADUATE) {
            LoginType.POSTGRADUATE_ATTENDANCE
        } else {
            LoginType.ATTENDANCE
        }
        val site = manager.siteOrNull(type, userInitiated) ?: return null
        val api = AttendanceScheduleApi(site)
        val term = api.nearTerm()
        if (!sameTerm(term.name, termCode)) {
            Log.d(TAG, "考勤系统当前学期是 ${term.name}，要查的是 $termCode，走教务")
            return null
        }
        return SourceResult(api.getSchedule(term.bh, term.weeks))
    }

    /**
     * 两个学期标识是不是同一个学期。
     *
     * 考勤系统的学期名是 `2025-2026-2`，教务的学期代码也是 `2025-2026-2`，但两边
     * 偶有全角连字符、前后空格之类的差别。抽成纯数字再比，与 `CourseLinks` 对齐考勤
     * 学期时用的是同一个办法。
     */
    private fun sameTerm(a: String, b: String): Boolean {
        val left = a.filter { it.isDigit() }
        val right = b.filter { it.isDigit() }
        return left.isNotEmpty() && left == right
    }

    /**
     * 取一个已登录的站点，拿不到返回 null。取消必须原样抛，不能当成"站点不可用"——
     * 吞掉取消会在重组频繁时反复触发登录，把站点打进失败冷却。
     */
    private suspend fun SessionManager.siteOrNull(type: LoginType, userInitiated: Boolean) = try {
        ensureSite(type, userInitiated = userInitiated, silent = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.d(TAG, "ensureSite(${type.name}) 不可用：${e.javaClass.simpleName} ${e.message}")
        null
    }
}
