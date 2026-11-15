package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.attendance.AttendanceRecordStore
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "CourseLinks"

/**
 * 把思源学堂、考勤挂回一门课上。
 * 只管解析，不管展示——两套日程布局共用同一份结果。每一项独立可失败，调用方按项渲染。
 * 所有登录都走 `silent = true`：点开课程详情不该让用户收到短信验证码。
 *
 * ## 纯判据都切到共享层了
 *
 * - 教材：`textbooksFor`（吃一份教材表 + 课名/课程号，不碰会话、网络、落盘）
 *   ⇒ `:shared:model` 的 [TextbookLinks]，三端同一份判据（含 `normalizedCourseName`）。
 * - 思源课程配对 + 考勤建索引：`lmsCourseMatched` / `attendanceMarksOf`
 *   ⇒ `:shared:model` 的 `CourseLinkMatching.kt`，那两份也是纯函数，wasm 能编。
 *
 * 这里只剩**要会话、要落盘**的取数：拉思源课程表、拉一学期考勤流水并缓存/落盘。
 * 判据一律调共享层，本文件不再持有任何匹配规则。
 */
object CourseLinks {

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
        return lmsCourseMatched(all, course)
    }

    // ── 考勤 ──────────────────────────────────────────────

    /**
     * 拉一学期考勤并建索引。
     *
     * 必须放在旁路协程里：考勤站点比教务慢得多，任何情况下都不能挡住课表渲染。
     * 失败返回 null，界面当作没有角标。研究生只换 `LoginType`，其余一致。
     * 索引本身的判据在共享层（`:shared:model` 的 `attendanceMarksOf`），这里只管取数与落盘。
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
    ): SlotAttendanceMarks? {
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
    ): SlotAttendanceMarks? {
        // 先看落盘缓存能不能免掉这次请求。存储和刷新策略在 AttendanceRecordStore，
        // 考勤页和课表角标共用同一份。
        val pg = accountType == AccountType.POSTGRADUATE
        // 没挂过宿主落盘（`attachStorage`）就不读缓存。
        val shard = if (storageAttached) AttendanceRecordStore.load(pg, termCode, c.accountId) else null
        val plan = AttendanceRecordStore.planFor(
            shard = shard,
            sealedTerm = sealedTerms.contains(termCode),
            force = false,
        )
        AttendanceRecordStore.logPlan(termCode, plan, shard)
        if (plan == AttendanceRecordStore.Plan.NONE && shard != null) {
            return attendanceMarksOf(shard.records)
        }
        return fetchAttendanceIndexInner(c, manager, accountType, termCode, userInitiated, shard, plan)
    }

    /**
     * 已结束、不会再变的学期。由日程页灌进来——这一层拿不到教务会话，自己判断不了。
     * 没灌就当所有学期都还活着：最多多拉几次，不会给出过期数据。
     */
    private val sealedTerms: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 宿主落盘接上了没有：课表角标读考勤记录、以及取到之后回写，都以它为闸门。 */
    private var storageAttached = false

    /**
     * 由日程页在首次调用前设好。
     *
     * @param enabled 宿主有可写的落盘（`:app` 与桌面端都是 true；离屏证据之类没有落盘的场景传 false）
     */
    fun attachStorage(enabled: Boolean, finishedTerms: Collection<String>) {
        storageAttached = enabled
        sealedTerms.addAll(finishedTerms)
    }
    private suspend fun fetchAttendanceIndexInner(
        c: Caches,
        manager: SessionManager?,
        accountType: AccountType,
        termCode: String,
        userInitiated: Boolean,
        shard: AttendanceRecordStore.Shard?,
        plan: AttendanceRecordStore.Plan,
    ): SlotAttendanceMarks? {
        val site = manager.siteOrNull(LoginType.ATTENDANCE, userInitiated) ?: return null
        return withContext(Dispatchers.IO) {
            val index = runCatching {
                val api = com.xjtu.toolbox.attendance.AttendanceApi(site)
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
                    java.time.LocalDate.now(com.xjtu.toolbox.util.CampusZoneId)
                        .minusDays(AttendanceRecordStore.INCREMENTAL_DAYS)
                        .toString()
                } else {
                    matched?.startDate.orEmpty()
                }
                val toDate = if (incremental) {
                    java.time.LocalDate.now(com.xjtu.toolbox.util.CampusZoneId).toString()
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
                if (storageAttached) {
                    val now = System.currentTimeMillis()
                    AttendanceRecordStore.save(
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
                attendanceMarksOf(records)
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
        @Volatile var lmsCourses: Box<List<com.xjtu.toolbox.lms.LmsCourseSummary>>? = null
        @Volatile var attendance: Pair<String, Box<SlotAttendanceMarks>>? = null

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

}
