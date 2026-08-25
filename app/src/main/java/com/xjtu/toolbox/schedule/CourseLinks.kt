package com.xjtu.toolbox.schedule

import android.util.Log
import com.xjtu.toolbox.attendance.AttendanceApi
import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.classreplay.Course as ReplayCourse
import com.xjtu.toolbox.classreplay.fetchCourses
import com.xjtu.toolbox.jiaocai1.Jiaocai1Api
import com.xjtu.toolbox.jiaocai1.Jiaocai1Book
import com.xjtu.toolbox.jiaocai1.Jiaocai1SearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val TAG = "CourseLinks"

/**
 * 课表之外的东西怎么挂回一门课上。
 *
 * 这一层**只管解析，不管展示**：新旧两套日程布局用的是同一份结果，所以关联关系不能长在
 * 任何一个 Composable 里。每一项都独立可失败——教材全文查不到不影响回放，回放站点没登录
 * 不影响考勤——调用方按项渲染，缺哪项就不显示哪项。
 *
 * 所有网络路径都走 `silent = true` 的登录：用户只是点开了一门课的详情，不该因此收到一条
 * 短信验证码。撞上 MFA 就当这一项不可用。
 */
object CourseLinks {

    // ── 教材 ──────────────────────────────────────────────

    /**
     * 从已加载的教材列表里挑出这门课的。
     *
     * 用课程名而不是课程号：教务的教材接口返回里没有课程号这一列，只有课程名。
     * 名字带「（甲）」「(实验)」这类后缀时两边未必逐字相同，所以先精确匹配，
     * 不中再退到「一方包含另一方」。
     */
    fun textbooksFor(courseName: String, all: List<TextbookItem>): List<TextbookItem> {
        val target = courseName.normalizedCourseName()
        if (target.isEmpty()) return emptyList()
        val exact = all.filter { it.courseName.normalizedCourseName() == target }
        if (exact.isNotEmpty()) return exact.filter { it.hasSubstantiveTextbook }
        return all.filter {
            val n = it.courseName.normalizedCourseName()
            n.isNotEmpty() && (n.contains(target) || target.contains(n))
        }.filter { it.hasSubstantiveTextbook }
    }

    /**
     * 拿 ISBN 去全文库找这本书。
     *
     * ISBN 是唯一能做**精确**匹配的键：书名在两个系统里的写法（副标题、卷次、版次）
     * 对不上是常态，按书名搜出来的第一条经常是另一本书。所以没有 ISBN 就直接放弃，
     * 不做模糊回退——给错的书比不给更糟。
     */
    suspend fun fulltextByIsbn(manager: SessionManager?, isbn: String): Jiaocai1Book? {
        val key = isbn.filter { it.isDigit() || it.equals('X', ignoreCase = true) }
        if (key.length < 10) return null
        fulltextCache[key]?.let { return it.value }
        val site = manager.siteOrNull(LoginType.JIAOCAI) ?: return null
        return withContext(Dispatchers.IO) {
            val hit = runCatching {
                Jiaocai1Api(site).search(keyword = isbn, field = Jiaocai1SearchField.ISBN)
                    .books.firstOrNull()
            }.getOrElse {
                Log.w(TAG, "fulltext by isbn=$isbn failed", it)
                null
            }
            // 查不到也缓存：同一个面板反复开合不该反复打这个请求。
            fulltextCache[key] = Box(hit)
            hit
        }
    }

    // ── 课程回放 ──────────────────────────────────────────

    /**
     * 教务的课程号 → TronClass 上的同一门课。
     *
     * 课程号在两个系统里是同一个学校编号，能直接等值匹配；课名不能，TronClass 的
     * `displayName` 带教学班后缀。列表一次拉全再本地匹配，因为它没有按课程号查询的接口，
     * 而且一学期就那么几十门，拉一次缓存住比每门课打一次请求划算。
     */
    suspend fun replayFor(manager: SessionManager?, courseCode: String): ReplayCourse? {
        val code = courseCode.trim()
        if (code.isEmpty()) return null
        val all = replayCourses(manager) ?: return null
        return all.firstOrNull { it.courseCode.trim().equals(code, ignoreCase = true) }
    }

    private suspend fun replayCourses(manager: SessionManager?): List<ReplayCourse>? {
        replayCache?.let { return it.value }
        val site = manager.siteOrNull(LoginType.CLASS) ?: return null
        return withContext(Dispatchers.IO) {
            val list = runCatching {
                // 接口是分页的，一页 50。翻到取空或够 5 页为止：一个学生一学期不可能有
                // 250 门课，封顶只是防服务端 total 字段不可信时无限翻页。
                val acc = ArrayList<ReplayCourse>()
                var page = 1
                while (page <= 5) {
                    val (items, _) = fetchCourses(site, page = page, pageSize = 50)
                    acc += items
                    if (items.size < 50) break
                    page++
                }
                acc.toList()
            }.getOrElse {
                Log.w(TAG, "fetchCourses failed", it)
                null
            }
            // 失败不写缓存：网络抖一下不该让整个会话都查不到回放。
            if (list != null) replayCache = Box(list)
            list
        }
    }

    // ── 考勤 ──────────────────────────────────────────────

    /** 考勤流水与课表格子的联合键。 */
    data class SlotKey(val week: Int, val dayOfWeek: Int, val startSection: Int)

    /**
     * 一学期的考勤流水，按 [SlotKey] 建索引。
     *
     * 选这三个字段做联合键，是因为它们在考勤和课表两边都是**同源的教务排课数据**，逐字相等。
     * 教室不能当键：考勤那边拼的是 `"楼宇名-房间号"`，课表那边是教务原始的 `JASMC`，
     * 两者格式不同；课程名和教师只用于交叉校验，不参与匹配。
     *
     * 只有已结束的课才有流水，所以未来的格子天然没有角标——这正是想要的效果，
     * 一周看下来就是一条"进度线"。
     */
    class AttendanceIndex(
        private val byKey: Map<SlotKey, AttendanceWaterRecord>,
        private val byCourse: Map<String, List<AttendanceWaterRecord>>,
    ) {
        fun statusOf(week: Int, dayOfWeek: Int, startSection: Int): WaterType? =
            byKey[SlotKey(week, dayOfWeek, startSection)]?.status

        fun recordsOf(courseName: String): List<AttendanceWaterRecord> =
            byCourse[courseName.normalizedCourseName()].orEmpty()

        val isEmpty: Boolean get() = byKey.isEmpty()
    }

    /**
     * 拉一学期考勤并建索引。
     *
     * 调用方必须把它放在旁路协程里：考勤站点要单独登录一次，比教务慢得多，
     * **任何情况下都不能挡住课表渲染**。失败返回 null，界面就当没有角标。
     *
     * 研究生走 `pg_attendance` 站点，但模型和解析完全一致，所以这里只换 [LoginType]，
     * 一份实现同时覆盖本科和研究生。
     */
    suspend fun attendanceIndex(
        manager: SessionManager?,
        accountType: AccountType,
    ): AttendanceIndex? {
        attendanceCache?.let { return it.value }
        val type = if (accountType == AccountType.POSTGRADUATE) {
            LoginType.POSTGRADUATE_ATTENDANCE
        } else {
            LoginType.ATTENDANCE
        }
        val site = manager.siteOrNull(type) ?: return null
        return withContext(Dispatchers.IO) {
            val index = runCatching {
                val records = AttendanceApi(site).getWaterRecords()
                val byKey = HashMap<SlotKey, AttendanceWaterRecord>()
                for (r in records) {
                    val dow = r.date.parseDayOfWeek() ?: continue
                    if (r.week <= 0 || r.startTime <= 0) continue
                    // 同一格重复上报时保留"更坏"的那条：缺勤 > 迟到 > 请假 > 正常。
                    // 只标异常的设计下，把异常盖成正常等于丢信息。
                    val key = SlotKey(r.week, dow, r.startTime)
                    val old = byKey[key]
                    if (old == null || r.status.severity() > old.status.severity()) byKey[key] = r
                }
                Log.d(TAG, "attendance index: ${records.size} 条流水 -> ${byKey.size} 格")
                AttendanceIndex(
                    byKey = byKey,
                    byCourse = records.groupBy { it.courseName.normalizedCourseName() },
                )
            }.getOrElse {
                Log.w(TAG, "attendance index failed", it)
                null
            }
            if (index != null) attendanceCache = Box(index)
            index
        }
    }

    // ── 缓存 ──────────────────────────────────────────────
    //
    // 进程内存活即可，不落盘：这些都是"打开详情时顺带看一眼"的辅助信息，冷启动重拉一次
    // 可以接受，而落盘就要跟着学期、账号一起做失效管理。切账号/切学期时调 [invalidate]。

    private class Box<T>(val value: T)

    private val fulltextCache = HashMap<String, Box<Jiaocai1Book?>>()
    private var replayCache: Box<List<ReplayCourse>>? = null
    private var attendanceCache: Box<AttendanceIndex>? = null

    fun invalidate() {
        fulltextCache.clear()
        replayCache = null
        attendanceCache = null
    }

    // ── 小工具 ────────────────────────────────────────────

    private suspend fun SessionManager?.siteOrNull(type: LoginType) =
        runCatching { this?.ensureSite(type, silent = true) }.getOrElse {
            Log.d(TAG, "ensureSite(${type.name}) 不可用：${it.javaClass.simpleName}")
            null
        }

    private fun WaterType.severity() = when (this) {
        WaterType.ABSENCE -> 3
        WaterType.LATE -> 2
        WaterType.LEAVE -> 1
        WaterType.NORMAL -> 0
    }

    /** 考勤的 `checkdate` 形如 `2025-09-15`，可能带时间后缀，取前 10 位解析。 */
    private fun String.parseDayOfWeek(): Int? = runCatching {
        LocalDate.parse(take(10)).dayOfWeek.value
    }.getOrNull()
}

/** 去掉空白和结尾括号后缀，让两个系统里同一门课的名字能对上。 */
internal fun String.normalizedCourseName(): String =
    trim().replace(Regex("[\\s　]"), "")
        .replace(Regex("[（(][^）)]*[）)]$"), "")
