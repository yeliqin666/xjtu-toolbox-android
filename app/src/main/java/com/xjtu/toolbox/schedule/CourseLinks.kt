package com.xjtu.toolbox.schedule

import android.util.Log
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.attendance.AttendanceRecordStore
import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.jiaocai1.Jiaocai1Api
import com.xjtu.toolbox.jiaocai1.Jiaocai1Book
import com.xjtu.toolbox.jiaocai1.Jiaocai1SearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "CourseLinks"

/**
 * 把教材、思源学堂、考勤挂回一门课上。
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
    /**
     * 找这门课的教材。
     *
     * 课程号优先：它是教务系统里的主键，两边一致就是同一门课，不用跟课程名的各种
     * 写法（「大学物理」/「大学物理（一）」/「大学物理I」）较劲。只有拿不到课程号、
     * 或者报表里那一列是空的，才退回按名字模糊匹配。
     *
     * @param courseCode 课表侧的课程号，空串表示不可用
     */
    fun textbooksFor(
        courseName: String,
        all: List<TextbookItem>,
        courseCode: String = "",
    ): List<TextbookItem> {
        val code = courseCode.trim()
        if (code.isNotEmpty()) {
            // 课程号常带班号后缀（`MATH100101-05`），先精确、再取主段。
            val byCode = all.filter { it.courseCode.trim().equals(code, ignoreCase = true) }
                .ifEmpty {
                    val stem = code.substringBefore('-').trim()
                    if (stem.length < 4) emptyList()
                    else all.filter { it.courseCode.trim().substringBefore('-').equals(stem, ignoreCase = true) }
                }
            if (byCode.isNotEmpty()) return byCode.filter { it.hasSubstantiveTextbook }
        }
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
    /**
     * 书名归一化：去掉书名号、括注、空白与标点，统一大小写。
     *
     * 两边对同一本书的写法常有出入（《固体物理学》/ 固体物理学（第二版）），
     * 但去掉这些装饰之后应当完全相等——用等值而不是包含，
     * 是为了不把「固体物理学」配到「固体物理学导论」上去。
     */
    private fun normalizedTitle(raw: String): String =
        raw.replace(Regex("""[（(\[【][^）)\]】]*[）)\]】]"""), "")
            .filter { it.isLetterOrDigit() }
            .lowercase()

    suspend fun fulltextByIsbn(
        manager: SessionManager?,
        isbn: String,
        /** ISBN 查不到时用来兜底的书名；传 null 表示不兜底。 */
        byTitle: String? = null,
        /** 同名多版本时用来消歧的作者，可空。 */
        byAuthor: String? = null,
    ): Jiaocai1Book? {
        val key = isbn.filter { it.isDigit() || it.equals('X', ignoreCase = true) }
        if (key.length < 10) return null
        val c = caches()
        c.fulltext[key]?.let { return it.value }
        val site = manager.siteOrNull(LoginType.JIAOCAI) ?: return null
        return withContext(Dispatchers.IO) {
            // 两种写法都试。教材报表里的 ISBN 常带连字符（978-7-04-039663-9），
            // 而全文库存的是哪一种没有保证——之前只发原文，库里存纯数字时就一条也搜不到，
            // 表现就是"明明有 ISBN 却从来匹配不上全文"。
            // 先发规范化的纯数字/X 形态，再退回原文。
            val candidates = listOf(key, isbn.trim()).filter { it.isNotEmpty() }.distinct()
            val hit = runCatching {
                candidates.firstNotNullOfOrNull { kw ->
                    Jiaocai1Api(site).search(keyword = kw, field = Jiaocai1SearchField.ISBN)
                        .books.firstOrNull()
                } ?: byTitle?.takeIf { it.isNotBlank() }?.let { title ->
                    // ISBN 搜不到时按书名兜底，但**只认归一化后完全相同**的。
                    //
                    // 这里原本完全不做回退，理由是"按书名搜出来的第一条经常是另一本书"。
                    // 那个顾虑针对的是"取第一条"，不是书名检索本身：实测《固体物理学》
                    // 按 ISBN 搜 0 条（全文库没索引它的 ISBN），按书名搜到 5 条，
                    // 严格等值能准确挑出那一本，并自动排除「固体物理学（上册）」
                    // 「高等学校教材 固体物理学」这类。
                    val r = Jiaocai1Api(site).search(keyword = title, field = Jiaocai1SearchField.BOOK_NAME)
                    val exact = r.books.filter { normalizedTitle(it.title) == normalizedTitle(title) }
                    // 同名多版本时用作者消歧；作者也对不上就放弃，不猜版本。
                    val wantAuthor = byAuthor?.let { normalizedTitle(it) }?.takeIf { it.isNotEmpty() }
                    when {
                        exact.size <= 1 -> exact.firstOrNull()
                        wantAuthor == null -> exact.first()
                        else -> exact.firstOrNull { normalizedTitle(it.author).contains(wantAuthor) }
                            ?: exact.first()
                    }
                }
            }.rethrowCancellation().getOrElse {
                Log.w(TAG, "fulltext by isbn=$isbn failed", it)
                null
            }
            // 查不到也缓存：同一个面板反复开合不该反复打这个请求。
            if (c.isCurrent()) c.fulltext[key] = Box(hit)
            hit
        }
    }

    /**
     * 这门课在思源学堂对应哪门。
     *
     * 先按课程号精确配——两个系统用的是同一套教务课程号，这是唯一可靠的判据；
     * 课程号常带班号后缀（`MATH100101-05`），所以再退一步比主段。
     * 都不中才按课程名，且只认归一化后完全相同的，不做包含匹配：
     * 「大学物理」能包含到「大学物理实验」，那是两门课，给错比不给更糟。
     */
    suspend fun lmsCourseFor(
        manager: SessionManager?,
        course: CourseItem,
    ): com.xjtu.toolbox.lms.LmsCourseSummary? {
        val c = caches()
        val all = c.lmsCourses?.value ?: run {
            val site = manager.siteOrNull(LoginType.LMS) ?: return null
            withContext(Dispatchers.IO) {
                runCatching { com.xjtu.toolbox.lms.LmsApi(site).getMyCourses() }
                    .rethrowCancellation()
                    .onFailure { Log.w(TAG, "lms courses failed", it) }
                    .getOrNull()
            }
                // 失败不写缓存：以前失败也存一个空列表，整个进程生命周期里都再查不到。
                ?.also { if (c.isCurrent()) c.lmsCourses = Box(it) }
                ?: return null
        }
        if (all.isEmpty()) return null

        val code = course.courseCode.trim()
        if (code.isNotEmpty()) {
            all.firstOrNull { it.courseCode.trim().equals(code, ignoreCase = true) }?.let { return it }
            val stem = code.substringBefore('-').trim()
            if (stem.length >= 4) {
                all.firstOrNull {
                    it.courseCode.trim().substringBefore('-').equals(stem, ignoreCase = true)
                }?.let { return it }
            }
        }
        val target = course.courseName.normalizedCourseName()
        if (target.isEmpty()) return null
        return all.firstOrNull { it.name.normalizedCourseName() == target }
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
        val c = caches()
        c.attendance?.takeIf { it.first == termCode }?.let { return it.second.value }
        return c.attendanceLock.withLock {
            c.attendance?.takeIf { it.first == termCode }?.second?.value
                ?: fetchAttendanceIndex(c, manager, accountType, termCode, userInitiated)
        }
    }

    private suspend fun fetchAttendanceIndex(
        c: Caches,
        manager: SessionManager?,
        accountType: AccountType,
        termCode: String,
        userInitiated: Boolean,
    ): AttendanceIndex? {
        // 先看落盘缓存能不能免掉这次请求。存储和刷新策略在 AttendanceRecordStore，
        // 考勤页和课表角标共用同一份——原来两边各拉各的、各存各的。
        val ctx = appContext
        val pg = accountType == AccountType.POSTGRADUATE
        val shard = ctx?.let { AttendanceRecordStore.load(it, pg, termCode, c.accountId) }
        val plan = AttendanceRecordStore.planFor(
            shard = shard,
            sealedTerm = sealedTerms.contains(termCode),
            force = false,
        )
        AttendanceRecordStore.logPlan(termCode, plan, shard)
        if (plan == AttendanceRecordStore.Plan.NONE && shard != null) {
            return indexOf(shard.records)
        }
        return fetchAttendanceIndexInner(c, manager, accountType, termCode, userInitiated, shard, plan)
    }

    /**
     * 已结束、不会再变的学期。由日程页灌进来——这一层拿不到教务会话，自己判断不了。
     * 没灌就当所有学期都还活着：最多多拉几次，不会给出过期数据。
     */
    private val sealedTerms: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
        c: Caches,
        manager: SessionManager?,
        accountType: AccountType,
        termCode: String,
        userInitiated: Boolean,
        shard: AttendanceRecordStore.Shard?,
        plan: AttendanceRecordStore.Plan,
    ): AttendanceIndex? {
        val site = manager.siteOrNull(LoginType.NEW_ATTENDANCE, userInitiated) ?: return null
        return withContext(Dispatchers.IO) {
            val index = runCatching {
                val api = com.xjtu.toolbox.attendance.attendanceProvider(site)
                // 必须显式指定学期，不能用 getWaterRecords() 的默认值。
                // 默认走 getNearTerm，暑假期间它返回的是还没有任何流水的新学期，
                // 结果就是稳定拉到 0 条——这正是"考勤那一行始终不显示"的原因。
                val terms = runCatching { api.getTermList() }.getOrElse {
                    Log.w(TAG, "getTermList 失败", it)
                    emptyList()
                }
                Log.d(TAG, "attendance 学期表：" + terms.joinToString { "${it.bh}=${it.code}(${it.name})" })
                // 考勤的 bh（如 646）和教务的学期码（2025-2026-2）是两套编号，
                // 靠 TermInfo.code（"2025-2026-2"，与教务 termCode 同格式）直接对齐，
                // 不再用人类可读名字里的数字瞎凑——"2025-2026 第二学期"抽出数字
                // 是"202520262"，跟教务的"2025-2026-2"永远对不上，考勤记录会被整学期丢弃。
                val matched = terms.firstOrNull { it.code.isNotBlank() && it.code == termCode }
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
                if (!c.isCurrent()) {
                    // SessionManager 切账号是原地重配，跨越切换的请求可能已用上新账号的会话，
                    // 拿到的数据归属说不清，整份丢弃，既不落盘也不进缓存。
                    Log.d(TAG, "attendance: account switched mid-fetch, discard")
                    return@runCatching null
                }
                appContext?.let { ctx ->
                    val now = System.currentTimeMillis()
                    AttendanceRecordStore.save(
                        ctx,
                        accountType == AccountType.POSTGRADUATE,
                        AttendanceRecordStore.Shard(
                            termCode = termCode,
                            records = records,
                            fetchedAt = now,
                            // 增量不推进全量时间戳，否则永远轮不到重扫。
                            fullScanAt = if (incremental) shard?.fullScanAt ?: now else now,
                        ),
                        c.accountId,
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
            if (index != null && c.isCurrent()) c.attendance = termCode to Box(index)
            index
        }
    }

    // ── 缓存 ──────────────────────────────────────────────
    //
    // 进程内存活即可，不落盘：这些都是"打开详情时顺带看一眼"的辅助信息，冷启动重拉一次
    // 可以接受，而落盘就要跟着学期、账号一起做失效管理。

    private class Box<T>(val value: T)

    /**
     * 一个账号的全部缓存。
     *
     * 账号隔离靠整体替换这个对象：[caches] 发现激活账号变了就换一个新的。每个请求在
     * **发起时**拿到当时的 [Caches]，结果写回同一个对象——中途切了账号，旧请求只会写进
     * 已被丢弃的旧对象，不会污染新账号。以前这些是 object 上的裸字段、从不按账号失效
     * （[invalidate] 没有任何调用方），切账号后会看到上一个人的回放、考勤、思源课程。
     *
     * 多张课程卡片的 LaunchedEffect 会并发查询，所以容器必须线程安全。
     */
    private class Caches(val accountId: String?) {
        val fulltext = ConcurrentHashMap<String, Box<Jiaocai1Book?>>()
        @Volatile var lmsCourses: Box<List<com.xjtu.toolbox.lms.LmsCourseSummary>>? = null
        @Volatile var attendance: Pair<String, Box<AttendanceIndex>>? = null

        /**
         * 同一时刻只允许一次考勤拉取。
         *
         * 详情面板和课表角标可能同时想要索引，重组也会让同一个请求重来；没有这把锁的话
         * 就是几路并发登录同一个站点，既拖慢又容易被判成异常访问。锁内会再查一次缓存，
         * 所以后到的那几路直接拿现成结果，不会重复发请求。
         */
        val attendanceLock = Mutex()

        /** 激活账号仍是本容器的账号。结果回来时不是了，就不该再写任何地方。 */
        fun isCurrent() = AccountContext.activeAccountId == accountId
    }

    @Volatile
    private var current = Caches(AccountContext.activeAccountId)

    private fun caches(): Caches {
        val id = AccountContext.activeAccountId
        current.takeIf { it.accountId == id }?.let { return it }
        synchronized(this) {
            if (current.accountId != id) current = Caches(id)
            return current
        }
    }

    /** 丢弃当前账号的全部缓存（如切学期后想强制重拉）。 */
    fun invalidate() {
        synchronized(this) { current = Caches(AccountContext.activeAccountId) }
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
        // 未识别状态不确定好坏，按"最坏"处理，避免被一条正常记录悄悄盖掉。
        WaterType.UNKNOWN -> 4
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
