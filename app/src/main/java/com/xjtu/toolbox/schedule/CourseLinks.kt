package com.xjtu.toolbox.schedule

import android.util.Log
import com.xjtu.toolbox.attendance.AttendanceApi
import com.xjtu.toolbox.attendance.AttendanceRecordStore
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val TAG = "CourseLinks"

/**
 * 把教材、回放、考勤挂回一门课上。
 *
 * 只管解析，不管展示——两套日程布局共用同一份结果。每一项独立可失败，调用方按项渲染。
 * 所有登录都走 `silent = true`：点开课程详情不该让用户收到短信验证码。
 */
object CourseLinks {

    // ── 教材 ──────────────────────────────────────────────

    /**
     * 按课程名匹配——教材接口没有课程号这一列。
     * 名字带「（甲）」这类后缀时两边未必一致，所以精确优先、包含兜底。
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
     * 只按 ISBN 精确查。书名在两个系统里的写法对不上是常态，按书名搜出来的第一条
     * 经常是另一本书——给错的书比不给更糟，所以没 ISBN 就放弃，不做模糊回退。
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
            }.rethrowCancellation().getOrElse {
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
     * 教务课程号 → TronClass 的同一门课。
     *
     * 两边的 code 不是同一个串，TronClass 在外面包了一层：
     * `202520262` + `PHYS405309` + `01`（学年学期 + 教务课程号 + 教学班号）。
     * 所以判据是包含，不是等值。同一门课跨学期重修会有多条，优先取学期前缀相符的。
     */
    suspend fun replayFor(
        manager: SessionManager?,
        courseCode: String,
        /** 教务学期码，如 `2025-2026-2`；用于在同名多学期时挑对的那一门。 */
        termCode: String = "",
    ): ReplayCourse? {
        val code = courseCode.trim()
        if (code.isEmpty()) return null
        val all = replayCourses(manager) ?: return null
        val candidates = all.filter { it.courseCode.contains(code, ignoreCase = true) }
        if (candidates.isEmpty()) {
            Log.d(
                TAG,
                "replay 未命中 code=$code；对方 ${all.size} 门，样本 codes=" +
                    all.take(5).joinToString { "${it.courseCode}(${it.name})" },
            )
            return null
        }
        // 学期前缀就是教务学期码去掉分隔符：2025-2026-2 -> 202520262
        val prefix = termCode.filter { it.isDigit() }
        val hit = candidates.firstOrNull { prefix.isNotEmpty() && it.courseCode.startsWith(prefix) }
            ?: candidates.first()
        Log.d(TAG, "replay 命中 $code -> ${hit.courseCode}(${hit.name}) 候选 ${candidates.size} 门")
        return hit
    }

    private suspend fun replayCourses(manager: SessionManager?): List<ReplayCourse>? {
        replayCache?.let { return it.value }
        // 注意：这里**不按学期过滤**。TronClass 的 my-courses 用 classify_type
        // "recently_started"，一次返回多个学期（课程回放页的学期筛选器就是这么来的），
        // 所以历史学期的课也在列表里，按课程号匹配即可，不需要额外的学期参数。
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
            }.rethrowCancellation().getOrElse {
                Log.w(TAG, "fetchCourses failed", it)
                null
            }
            // 失败不写缓存：网络抖一下不该让整个会话都查不到回放。
            if (list != null) replayCache = Box(list)
            list
        }
    }

    /**
     * 指定某一天的回放场次，不是"这个课格在整学期的所有周"——
     * 按星期几筛会把 7 天后、14 天后的全带进来。同一天多场是正常的（连堂各录一段）。
     */
    suspend fun replaySessionsOn(
        manager: SessionManager?,
        course: CourseItem,
        termCode: String,
        date: LocalDate,
    ): Pair<ReplayCourse, List<com.xjtu.toolbox.classreplay.LiveActivity>>? {
        val target = replayFor(manager, course.courseCode, termCode) ?: return null
        val activities = replaySessions(manager, target) ?: return null
        val mine = activities.filter { it.startTime.parseDateTime()?.toLocalDate() == date }
        Log.d(
            TAG,
            "replay 场次 ${target.name} @$date：共 ${activities.size} 场，命中 ${mine.size} 场",
        )
        // 那一天没有录播就一场不给。摆别的日子的出来比不摆更糟——
        // 用户会以为那就是这次课的。想看全部走"课程回放"那一行进回放页。
        return target to mine.sortedBy { it.startTime }
    }

    private suspend fun replaySessions(
        manager: SessionManager?,
        target: ReplayCourse,
    ): List<com.xjtu.toolbox.classreplay.LiveActivity>? {
        sessionCache[target.id]?.let { return it.value }
        val site = manager.siteOrNull(LoginType.CLASS) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val acc = ArrayList<com.xjtu.toolbox.classreplay.LiveActivity>()
                var page = 1
                while (page <= 5) {
                    val (items, _) = com.xjtu.toolbox.classreplay
                        .fetchLiveActivities(site, target.id, page = page, pageSize = 50)
                    acc += items
                    if (items.size < 50) break
                    page++
                }
                acc.toList()
            }.rethrowCancellation().getOrElse {
                Log.w(TAG, "fetchLiveActivities failed", it)
                null
            }?.also { sessionCache[target.id] = Box(it) }
        }
    }

    private const val SESSION_SLACK_MIN = 45

    /** 走 [parseDateTime] 而不是截字符串：原始值是 UTC，直接截会显示成 02:10。 */
    fun prettyLocalTime(raw: String): String =
        raw.parseDateTime()?.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            ?: raw

    /**
     * TronClass 的时间戳是 UTC（`2026-06-15T02:10:00Z`），必须按时区换算。
     * 不能用 `OffsetDateTime.toLocalDateTime()`——那是丢掉偏移而不是换算，
     * 小时和星期会一起算错。
     */
    private fun String.parseDateTime(): java.time.LocalDateTime? {
        val t = trim()
        if (t.isEmpty()) return null
        runCatching {
            java.time.OffsetDateTime.parse(t)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
        }.getOrNull()?.let { return it }
        return runCatching { java.time.LocalDateTime.parse(t) }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(t.replace(' ', 'T')) }.getOrNull()
    }

    // ── 考勤 ──────────────────────────────────────────────

    /** 考勤流水与课表格子的联合键。 */
    data class SlotKey(val week: Int, val dayOfWeek: Int, val startSection: Int)

    /**
     * 一学期的考勤流水，按 [SlotKey] 建索引。
     *
     * 这三个字段两边同源于教务排课数据，逐字相等。教室不能当键：考勤拼的是
     * 「楼宇名-房间号」，课表是教务原始的 `JASMC`，格式不同。
     */
    class AttendanceIndex(
        private val byKey: Map<SlotKey, AttendanceWaterRecord>,
        private val byCode: Map<String, List<AttendanceWaterRecord>>,
        private val byName: Map<String, List<AttendanceWaterRecord>>,
    ) {
        fun statusOf(week: Int, dayOfWeek: Int, startSection: Int): WaterType? =
            statusRecordOf(week, dayOfWeek, startSection)?.status

        fun statusRecordOf(week: Int, dayOfWeek: Int, startSection: Int): AttendanceWaterRecord? =
            byKey[SlotKey(week, dayOfWeek, startSection)]

        /**
         * 优先按课程号：实测考勤的 `sCode` 与教务 `courseCode` 逐字相同。
         * 名字只作兜底，个别记录的 sCode 会是空的。
         */
        fun recordsOf(course: CourseItem): List<AttendanceWaterRecord> {
            val code = course.courseCode.trim()
            if (code.isNotEmpty()) byCode[code]?.let { return it }
            return byName[course.courseName.normalizedCourseName()].orEmpty()
        }

        /** 这一个课格每周的考勤，按周次升序。一门课一周上两次时能分开看。 */
        /** 指定周次的那一次考勤。没有记录 = 还没上，或这门课不考勤。 */
        fun recordOn(course: CourseItem, week: Int): AttendanceWaterRecord? =
            statusRecordOf(week, course.dayOfWeek, course.startSection)

        fun weeklyOf(course: CourseItem): List<AttendanceWaterRecord> =
            recordsOf(course)
                .filter { r ->
                    r.startTime == course.startSection &&
                        r.date.dayOfWeekOrNull() == course.dayOfWeek
                }
                .sortedBy { it.week }

        val isEmpty: Boolean get() = byKey.isEmpty()

    }

    /**
     * 拉一学期考勤并建索引。
     *
     * 必须放在旁路协程里：考勤站点比教务慢得多，任何情况下都不能挡住课表渲染。
     * 失败返回 null，界面当作没有角标。研究生只换 [LoginType]，其余一致。
     */
    suspend fun attendanceIndex(
        manager: SessionManager?,
        accountType: AccountType,
        /**
         * 日程页当前选中的教务学期码，如 `2025-2026-2`。
         *
         * 考勤流水自带 `termString`，格式**就是教务这一套**，所以按它本地过滤即可，
         * 不必去映射考勤系统自己的 `bh`（那是另一套编号，实测当前学期是 `646`）。
         * 传空则不过滤。
         */
        termCode: String,
        /** 详情面板是用户点开的、正在等结果，传 true 豁免站点级失败冷却。角标是后台的，保持 false。 */
        userInitiated: Boolean = false,
    ): AttendanceIndex? {
        // 缓存必须带学期：不带的话切到别的学期还会拿到上一个学期的索引，
        // 表现就是"换了学期角标和考勤记录都不动"。
        attendanceCache?.takeIf { it.first == termCode }?.let { return it.second.value }
        return attendanceLock.withLock {
            attendanceCache?.takeIf { it.first == termCode }?.second?.value
                ?: fetchAttendanceIndex(manager, accountType, termCode, userInitiated)
        }
    }

    private suspend fun fetchAttendanceIndex(
        manager: SessionManager?,
        accountType: AccountType,
        termCode: String,
        userInitiated: Boolean,
    ): AttendanceIndex? {
        // 先看落盘缓存能不能免掉这次请求。存储和刷新策略在 AttendanceRecordStore，
        // 考勤页和课表角标共用同一份——原来两边各拉各的、各存各的。
        val ctx = appContext
        val pg = accountType == AccountType.POSTGRADUATE
        val shard = ctx?.let { AttendanceRecordStore.load(it, pg, termCode) }
        val plan = AttendanceRecordStore.planFor(
            shard = shard,
            sealedTerm = sealedTerms.contains(termCode),
            force = false,
        )
        AttendanceRecordStore.logPlan(termCode, plan, shard)
        if (plan == AttendanceRecordStore.Plan.NONE && shard != null) {
            return indexOf(shard.records)
        }
        return fetchAttendanceIndexInner(manager, accountType, termCode, userInitiated, shard, plan)
    }

    /**
     * 已结束、不会再变的学期。由日程页灌进来——这一层拿不到教务会话，自己判断不了。
     * 没灌就当所有学期都还活着：最多多拉几次，不会给出过期数据。
     */
    private val sealedTerms = mutableSetOf<String>()

    /** 落盘缓存要 Context；由日程页在首次调用前设好。 */
    private var appContext: android.content.Context? = null

    fun attachContext(ctx: android.content.Context, finishedTerms: Collection<String>) {
        appContext = ctx.applicationContext
        sealedTerms.addAll(finishedTerms)
    }

    private fun indexOf(records: List<AttendanceWaterRecord>): AttendanceIndex {
        val byKey = HashMap<SlotKey, AttendanceWaterRecord>()
        for (r in records) {
            val dow = r.date.parseDayOfWeek() ?: continue
            if (r.week <= 0 || r.startTime <= 0) continue
            // 同一格重复上报时保留"更坏"的那条：缺勤 > 迟到 > 请假 > 正常。
            // 把异常盖成正常等于丢信息。
            val key = SlotKey(r.week, dow, r.startTime)
            val old = byKey[key]
            if (old == null || r.status.severity() > old.status.severity()) byKey[key] = r
        }
        return AttendanceIndex(
            byKey = byKey,
            byCode = records.filter { it.courseCode.isNotBlank() }.groupBy { it.courseCode.trim() },
            byName = records.groupBy { it.courseName.normalizedCourseName() },
        )
    }

    private suspend fun fetchAttendanceIndexInner(
        manager: SessionManager?,
        accountType: AccountType,
        termCode: String,
        userInitiated: Boolean,
        shard: AttendanceRecordStore.Shard?,
        plan: AttendanceRecordStore.Plan,
    ): AttendanceIndex? {
        val type = if (accountType == AccountType.POSTGRADUATE) {
            LoginType.POSTGRADUATE_ATTENDANCE
        } else {
            LoginType.ATTENDANCE
        }
        val site = manager.siteOrNull(type, userInitiated) ?: return null
        return withContext(Dispatchers.IO) {
            val index = runCatching {
                val api = AttendanceApi(site)
                // 必须显式指定学期，不能用 getWaterRecords() 的默认值。
                // 默认走 getNearTerm，暑假期间它返回的是还没有任何流水的新学期，
                // 结果就是稳定拉到 0 条——这正是"考勤那一行始终不显示"的原因。
                val terms = runCatching { api.getTermList() }.getOrElse {
                    Log.w(TAG, "getTermList 失败", it)
                    emptyList()
                }
                Log.d(TAG, "attendance 学期表：" + terms.joinToString { "${it.bh}=${it.name}" })
                // 考勤的 bh（如 646）和教务的学期码（2025-2026-2）是两套编号，
                // 靠学期名里的数字对齐：两边都抽成纯数字 202520262 再比。
                val want = termCode.filter { it.isDigit() }
                val matched = terms.firstOrNull { it.name.filter { c -> c.isDigit() } == want }
                // 增量只回看最近几天，全量按学期起止取。
                // 老师改考勤没有时间限制（期末回头补第 3 周是常事），所以增量之外
                // 还有定期全量重扫兜底，见 AttendanceRecordStore 的分层说明。
                val incremental = plan == AttendanceRecordStore.Plan.INCREMENTAL
                val fromDate = if (incremental) {
                    java.time.LocalDate.now()
                        .minusDays(AttendanceRecordStore.INCREMENTAL_DAYS)
                        .toString()
                } else {
                    matched?.startDate.orEmpty()
                }
                val toDate = if (incremental) {
                    java.time.LocalDate.now().toString()
                } else {
                    matched?.endDate.orEmpty()
                }
                val all = if (matched != null) {
                    api.getWaterRecords(matched.bh, startDate = fromDate, endDate = toDate)
                } else {
                    Log.w(TAG, "attendance 没找到学期 $termCode 对应的 bh，退回默认学期")
                    api.getWaterRecords()
                }
                // 再按 termString 兜一层：接口偶尔会带回邻近学期的记录。
                val fresh = if (termCode.isBlank()) all
                else all.filter { it.termString.isBlank() || it.termString == termCode }
                // 增量只覆盖最近几天，必须跟旧的合并，否则整学期只剩这几天。
                // merge 是新盖旧，老师改过的记录会被新版本覆盖。
                val records = if (incremental && shard != null) {
                    AttendanceRecordStore.merge(fresh, shard.records)
                } else {
                    fresh
                }
                appContext?.let { c ->
                    val now = System.currentTimeMillis()
                    AttendanceRecordStore.save(
                        c,
                        accountType == AccountType.POSTGRADUATE,
                        AttendanceRecordStore.Shard(
                            termCode = termCode,
                            records = records,
                            fetchedAt = now,
                            // 增量不推进全量时间戳，否则永远轮不到重扫。
                            fullScanAt = if (incremental) shard?.fullScanAt ?: now else now,
                        ),
                    )
                }
                Log.d(
                    TAG,
                    "attendance: 学期 $termCode -> bh=${matched?.bh} " +
                        "(${matched?.startDate}~${matched?.endDate}) " +
                        "拉到 ${all.size} 条，命中 ${records.size} 条；" +
                        "样本 termString=${all.firstOrNull()?.termString} " +
                        "code=${all.firstOrNull()?.courseCode}",
                )
                Log.d(TAG, "attendance index: ${records.size} 条流水")
                indexOf(records)
            }.rethrowCancellation().getOrElse {
                Log.w(TAG, "attendance index failed", it)
                null
            }
            if (index != null) attendanceCache = termCode to Box(index)
            index
        }
    }

    // ── 缓存 ──────────────────────────────────────────────
    //
    // 进程内存活即可，不落盘：这些都是"打开详情时顺带看一眼"的辅助信息，冷启动重拉一次
    // 可以接受，而落盘就要跟着学期、账号一起做失效管理。切账号/切学期时调 [invalidate]。

    private class Box<T>(val value: T)

    /**
     * 同一时刻只允许一次考勤拉取。
     *
     * 详情面板和课表角标可能同时想要索引，重组也会让同一个请求重来；没有这把锁的话
     * 就是几路并发登录同一个站点，既拖慢又容易被判成异常访问。锁内会再查一次缓存，
     * 所以后到的那几路直接拿现成结果，不会重复发请求。
     */
    private val attendanceLock = kotlinx.coroutines.sync.Mutex()

    private val fulltextCache = HashMap<String, Box<Jiaocai1Book?>>()
    private var replayCache: Box<List<ReplayCourse>>? = null
    private val sessionCache = HashMap<Int, Box<List<com.xjtu.toolbox.classreplay.LiveActivity>>>()
    private var attendanceCache: Pair<String, Box<AttendanceIndex>>? = null

    fun invalidate() {
        fulltextCache.clear()
        replayCache = null
        sessionCache.clear()
        attendanceCache = null
    }

    // ── 小工具 ────────────────────────────────────────────

    /**
     * 取一个已登录的站点，拿不到返回 null。
     *
     * **CancellationException 必须原样抛，不能吞**：runCatching 捕获 Throwable，
     * 吞掉取消会让 Compose 每次重组都被记成一次"站点不可用"，重启的那次又发起登录，
     * 几秒内反复登录把站点打进 60 秒失败冷却。
     *
     * @param userInitiated 用户正在等结果时传 true，豁免站点级失败冷却；
     * CasGate 的串行、退避、密码熔断照旧生效。
     */
    private suspend fun SessionManager?.siteOrNull(
        type: LoginType,
        userInitiated: Boolean = false,
    ) = try {
        this?.ensureSite(type, userInitiated = userInitiated, silent = true)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.d(TAG, "ensureSite(${type.name}) 不可用：${e.javaClass.simpleName} ${e.message}")
        null
    }

    /** runCatching 会把协程取消也收进 Result。取消不是失败，必须继续往上传。 */
    private fun <T> Result<T>.rethrowCancellation(): Result<T> = also {
        (exceptionOrNull() as? kotlinx.coroutines.CancellationException)?.let { throw it }
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

    internal fun String.dayOfWeekOrNull(): Int? = parseDayOfWeek()
}

/** 去掉空白和结尾括号后缀，让两个系统里同一门课的名字能对上。 */
internal fun String.normalizedCourseName(): String =
    trim().replace(Regex("[\\s　]"), "")
        .replace(Regex("[（(][^）)]*[）)]$"), "")
