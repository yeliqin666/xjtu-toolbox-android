package com.xjtu.toolbox.schedule

import kotlinx.serialization.json.decodeFromJsonElement
import android.content.Context
import android.util.Log
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.jsonArray
import com.xjtu.toolbox.attendance.KqPortal
import com.xjtu.toolbox.attendance.KqTimetableRow
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.auth.siteKey
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.data.DataCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ScheduleSource"

/**
 * 当前学期课表从哪个系统拉。
 *
 * 教务一次给整学期，任何学期都能查，调停课也合在里面，是默认源。考勤系统是签到设备
 * 实际用的那一份排课，门户把本科、研究生课程合在一起，研究生课表只有它能给。
 *
 * 旧版设置里存的 `"jwapp"`（移动教务，和教务同一个库）、`"js"`（智慧教室平台）已不作课表源，
 * 由 [fromKey] 落回默认源。
 */
enum class ScheduleSource(val key: String, val label: String, val summary: String) {
    JWXT(CredentialStore.SCHEDULE_SOURCE_JWXT, "教务系统", "一次拉整学期，含调停课"),
    ATTENDANCE(CredentialStore.SCHEDULE_SOURCE_ATTENDANCE, "考勤系统", "本科、研究生课程合并，只有当前学期");

    companion object {
        val DEFAULT = JWXT

        fun fromKey(key: String?): ScheduleSource = entries.firstOrNull { it.key == key } ?: DEFAULT

        fun of(context: Context): ScheduleSource =
            fromKey(CredentialStore(context).scheduleSource)
    }
}

/**
 * 按用户选的来源取整学期课表。
 *
 * 两条硬规则：
 * 1. **历史学期永远走教务**。非教务源不一定认得别的学期，查了要么报错，
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
        userInitiated: Boolean = false,
    ): List<CourseItem> {
        val source = ScheduleSource.of(context)
        if (source == ScheduleSource.JWXT || manager == null) return jwxtSchedule(context, jwxt, termCode)

        val alternative = try {
            withContext(Dispatchers.IO) {
                when (source) {
                    ScheduleSource.ATTENDANCE -> fromAttendance(manager, termCode, userInitiated)
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

    /**
     * [term]（默认本学期）的课表、开学日期缺哪样补哪样。装新包会清掉缓存，
     * 首页和屁岱不能等用户进日程页才有课表。
     */
    suspend fun ensureCached(
        context: Context,
        cache: DataCache,
        api: ScheduleApi,
        manager: SessionManager?,
        term: String? = null,
    ) {
        val code = term ?: ScheduleCache.readCurrentTerm(cache)
            ?: api.getCurrentTerm().also { ScheduleCache.writeCurrentTerm(cache, it) }
        if (ScheduleCache.readTermList(cache).isEmpty()) ScheduleCache.writeTermList(cache, listOf(code))
        runCatching {
            if (ScheduleTermStore.read(cache).isEmpty()) api.getTermList()
            ScheduleTermStore.merge(cache, api.termNames())
        }
        val start = ScheduleCache.readStartDate(cache, code)
            ?: api.getStartOfTerm(code).also { ScheduleCache.writeStartDate(cache, code, it, api.termWeeksOf(code)) }
        if (ScheduleCache.readCourses(cache, code) == null) {
            val fresh = getSchedule(context, api, code, manager)
            ScheduleCache.writeRawCourses(cache, code, fresh)
            // 和日程页一样剔除节假日，否则它下次落地会误报「日程有更新」
            ScheduleCache.writeOptimizedCourses(cache, code, ScheduleCache.filterByHolidays(fresh, start, HolidayApi.peekCached(context)))
        }
    }

    private suspend fun jwxtSchedule(
        context: Context,
        jwxt: ScheduleApi,
        termCode: String,
    ): List<CourseItem> {
        val result = withContext(Dispatchers.IO) { jwxt.getSchedule(termCode) }
        remember(context, ScheduleSource.JWXT)
        rememberChanges(context, termCode, result.changes)
        return result.courses
    }

    /**
     * 最近一次课表**实际**来自哪个系统。
     *
     * 与用户在设置里选的那个未必相同：选了考勤但这次退回了教务，数据形状就是教务的。
     * [ScheduleDiff] 按这个值分开存快照，否则退回的那一次会把每门课都报成"变了"。
     */
    fun servedSource(context: Context): ScheduleSource =
        ScheduleSource.fromKey(prefs(context, PREFS_SERVED).getString(KEY_SERVED, null))

    private fun remember(context: Context, source: ScheduleSource) {
        prefs(context, PREFS_SERVED).edit().putString(KEY_SERVED, source.key).apply()
    }

    /** 这两份记录随账号走：换账号后别拿上一个人的来源和调课理由去比。 */
    private fun prefs(context: Context, name: String) =
        context.applicationContext.getSharedPreferences(name + AccountContext.safeSuffix(), Context.MODE_PRIVATE)

    private const val PREFS_SERVED = "schedule_source"
    private const val KEY_SERVED = "served"

    /**
     * 这学期最近一次拉到的调课/停课/补课记录。只有教务给；别的源服务时写空表，
     * 免得拿上一份记录去对不相干的课表。
     */
    fun changeEvents(context: Context, termCode: String): List<ScheduleChangeEvent> {
        if (termCode.isBlank()) return emptyList()
        val json = prefs(context, PREFS_CHANGES).getString(termCode, null) ?: return emptyList()
        // 逐条解码：缺了 kind 的条目单独丢掉，不连累整份
        return runCatching { AppJson.parseToJsonElement(json).jsonArray }.getOrNull().orEmpty()
            .mapNotNull { runCatching { AppJson.decodeFromJsonElement<ScheduleChangeEvent>(it) }.getOrNull() }
    }

    private fun rememberChanges(context: Context, termCode: String, events: List<ScheduleChangeEvent>) {
        if (termCode.isBlank()) return
        val edit = prefs(context, PREFS_CHANGES).edit()
        if (events.isEmpty()) edit.remove(termCode) else edit.putString(termCode, AppJson.encodeToString(events))
        edit.apply()
    }
    private const val PREFS_CHANGES = "schedule_changes"

    /**
     * 考勤课表源：读考勤门户的本研合并课表（见 [KqPortal]），本科、研究生两侧的课拼在一起。
     * 门户只给当前学期，对不上就走教务。设置里的键值沿用旧版考勤的 "bkkq"，
     * 见 [CredentialStore.SCHEDULE_SOURCE_ATTENDANCE]。
     *
     * 先登考勤站点：门户会话要靠它刚建立的统一认证登录态免密换来，校外还要它的 WebVPN 网关会话。
     */
    private suspend fun fromAttendance(
        manager: SessionManager,
        termCode: String,
        userInitiated: Boolean,
    ): SourceSchedule? {
        val site = manager.siteOrNull(LoginType.ATTENDANCE, userInitiated) ?: return null
        val sides = KqPortal.currentSemester(site).filter { sameTerm(it.termCode, termCode) }
        if (sides.isEmpty()) {
            Log.d(TAG, "考勤门户的当前学期不是 $termCode，走教务")
            return null
        }
        return SourceSchedule(KqTimetableRow.toCourses(sides.flatMap { it.rows }))
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
        ensureSite(type.siteKey(), userInitiated = userInitiated, silent = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.d(TAG, "ensureSite(${type.siteKey()}) 不可用：${e.javaClass.simpleName} ${e.message}")
        null
    }
}
